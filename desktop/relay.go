package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// The tunnel protocol, spoken over ADB reverse sockets:
//
//	Thor -> PC:  "ETHS" 0x02 <session u64> <index u8> <count u8>   (hello, one per stream)
//	PC -> Thor:  "ETHS" 0x02 <len u8> <PC name>                     (welcome)
//	both ways:   <len u16> <seq u32> <one IPv4 or IPv6 packet>, repeated (big-endian)
//
// ADB carries each stream stop-and-wait: it sends one chunk and waits for the other end to
// confirm it before reading the next, which held a single stream to about 90 Mbps. So the
// Thor opens several streams (count, all with the same random session) and both sides deal
// packets across them in turn. seq numbers the packets in each direction, so the receiver
// puts them back in order before they reach the TCP/IP stack, and even a single download gets
// the speed of all the streams together.
//
// Version 1 (the app before 1.1) is one stream, frames without seq: "ETHS" 0x01 as the hello,
// and the same welcome with 0x01.
//
// The Thor's VPN hands every packet its apps send to this relay unchanged. The relay runs
// them through gVisor's user-space TCP/IP stack, which ends each TCP connection and UDP flow
// here on the PC and opens a real socket for it, so the traffic leaves through the PC's own
// network connection (its ethernet) and needs no admin rights or drivers.
const (
	protoMagic   = "ETHS"
	protoVersion = 2
	maxStreams   = 16
	// Matches the VPN's MTU on the Thor. Big packets mean fewer of them, which is most of
	// what decides the speed over ADB.
	tunnelMTU = 16384
)

// The Thor's VPN uses these addresses. Gateway is "the PC": DNS sent to it goes to the PC's
// own DNS server, and any other port on it reaches the PC itself (127.0.0.1).
var (
	gateway4 = tcpip.AddrFrom4([4]byte{10, 88, 0, 1})
	gateway6 = tcpip.AddrFrom16([16]byte{0xfd, 0, 0, 0x88, 15: 1})
)

// Stats are a tunnel's byte counters, as seen from the Thor: down = to the Thor.
type Stats struct {
	Down, Up atomic.Int64
}

// Relay takes the Thors' streams and runs a tunnel for each Thor.
type Relay struct {
	PCName string
	Stats  *Stats
	DNS    func() string
	// Called when a Thor's tunnel starts and when it ends (err nil when it closed normally).
	OnConnect    func()
	OnDisconnect func(err error)
	// Called when a stream is turned away before it joins a tunnel.
	OnRefused func(err error)

	mu       sync.Mutex
	sessions map[uint64]*tunnel
}

type hello struct {
	version      byte
	session      uint64
	index, count int
}

// Handle runs one stream from a Thor until it closes. The first stream of a session runs
// the tunnel; the others join it.
func (r *Relay) Handle(conn net.Conn) error {
	h, err := handshake(conn, r.PCName)
	if err == nil && h.index > 0 {
		err = r.join(h, conn)
		if err == nil {
			return nil
		}
	}
	var t *tunnel
	if err == nil {
		t, err = newTunnel(h, r.Stats, r.DNS)
	}
	if err != nil {
		conn.Close()
		if r.OnRefused != nil {
			r.OnRefused(err)
		}
		return err
	}
	if h.version >= 2 {
		r.mu.Lock()
		if r.sessions == nil {
			r.sessions = map[uint64]*tunnel{}
		}
		r.sessions[h.session] = t
		r.mu.Unlock()
		defer func() {
			r.mu.Lock()
			delete(r.sessions, h.session)
			r.mu.Unlock()
		}()
	}
	if r.OnConnect != nil {
		r.OnConnect()
	}
	t.attach(0, conn)
	<-t.ctx.Done()
	t.stack.Destroy()
	err = t.err
	if errors.Is(err, io.EOF) || errors.Is(err, net.ErrClosed) {
		err = nil
	}
	if r.OnDisconnect != nil {
		r.OnDisconnect(err)
	}
	return err
}

// join adds a stream to its session's tunnel. The Thor opens stream 0 first, so the session
// is normally there already; a moment's wait covers streams arriving out of order.
func (r *Relay) join(h hello, conn net.Conn) error {
	for wait := 0; wait < 50; wait++ {
		r.mu.Lock()
		t := r.sessions[h.session]
		r.mu.Unlock()
		if t != nil {
			if t.count != h.count {
				return errors.New("streams disagree on how many there are")
			}
			t.attach(h.index, conn)
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return errors.New("a stream arrived for a connection that isn't open")
}

// Serve runs a single-stream Thor's tunnel on conn until it closes.
func Serve(conn net.Conn, pcName string, stats *Stats, dns func() string) error {
	r := &Relay{PCName: pcName, Stats: stats, DNS: dns}
	return r.Handle(conn)
}

func handshake(conn net.Conn, pcName string) (hello, error) {
	var h hello
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	defer conn.SetDeadline(time.Time{})
	head := make([]byte, 5)
	if _, err := io.ReadFull(conn, head); err != nil {
		return h, fmt.Errorf("no hello from the Thor: %w", err)
	}
	if string(head[:4]) != protoMagic {
		return h, errors.New("not an EtherShare connection")
	}
	h.version = head[4]
	switch h.version {
	case 1:
		h.count = 1
	case 2:
		rest := make([]byte, 10)
		if _, err := io.ReadFull(conn, rest); err != nil {
			return h, fmt.Errorf("short hello from the Thor: %w", err)
		}
		h.session = binary.BigEndian.Uint64(rest)
		h.index, h.count = int(rest[8]), int(rest[9])
		if h.count < 1 || h.count > maxStreams || h.index >= h.count {
			return h, fmt.Errorf("bad stream %d of %d", h.index, h.count)
		}
	default:
		return h, fmt.Errorf("the Thor's EtherShare speaks version %d, this program speaks %d: update both", h.version, protoVersion)
	}
	if len(pcName) > 255 {
		pcName = pcName[:255]
	}
	welcome := append([]byte(protoMagic), h.version, byte(len(pcName)))
	_, err := conn.Write(append(welcome, pcName...))
	return h, err
}

type tunnel struct {
	stack  *stack.Stack
	ep     *channel.Endpoint
	stats  *Stats
	dns    func() string
	ctx    context.Context
	cancel context.CancelFunc
	seq    bool // frames carry seq (version 2)
	count  int

	mu    sync.Mutex
	conns []net.Conn
	out   []chan []byte // frames waiting for each stream's writer
	err   error
	ended bool

	// Putting the Thor's packets back in order.
	inMu    sync.Mutex
	next    uint32
	pending map[uint32][]byte
}

func newTunnel(h hello, stats *Stats, dns func() string) (*tunnel, error) {
	ctx, cancel := context.WithCancel(context.Background())
	t := &tunnel{stats: stats, dns: dns, ctx: ctx, cancel: cancel, seq: h.version >= 2, count: h.count,
		conns: make([]net.Conn, h.count), out: make([]chan []byte, h.count), pending: map[uint32][]byte{}}
	for i := range t.out {
		t.out[i] = make(chan []byte, 512)
	}
	if err := t.buildStack(); err != nil {
		cancel()
		return nil, err
	}
	go t.writeLoop()
	return t, nil
}

// attach starts stream i on conn.
func (t *tunnel) attach(i int, conn net.Conn) {
	t.mu.Lock()
	if t.ended || t.conns[i] != nil {
		t.mu.Unlock()
		conn.Close()
		return
	}
	t.conns[i] = conn
	t.mu.Unlock()
	go t.sendLoop(i, conn)
	go func() { t.fail(t.readLoop(conn)) }()
}

// fail ends the tunnel and all its streams; the first error is the reason.
func (t *tunnel) fail(err error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.ended {
		return
	}
	t.ended = true
	t.err = err
	t.cancel()
	for _, c := range t.conns {
		if c != nil {
			c.Close()
		}
	}
}

func (t *tunnel) buildStack() error {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
	})
	t.stack = s
	t.ep = channel.New(1024, tunnelMTU, "")
	if err := s.CreateNIC(1, t.ep); err != nil {
		return fmt.Errorf("create NIC: %s", err)
	}
	// Accept packets for any address, and send replies from any address.
	s.SetPromiscuousMode(1, true)
	s.SetSpoofing(1, true)
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
		{Destination: header.IPv6EmptySubnet, NIC: 1},
	})

	// Room for fast downloads: SACK and big, self-tuning windows.
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	moderate := tcpip.TCPModerateReceiveBufferOption(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate)
	rcv := tcpip.TCPReceiveBufferSizeRangeOption{Min: 4 << 10, Default: 1 << 20, Max: 8 << 20}
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &rcv)
	snd := tcpip.TCPSendBufferSizeRangeOption{Min: 4 << 10, Default: 1 << 20, Max: 8 << 20}
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &snd)

	tcpFwd := tcp.NewForwarder(s, 0, 2048, t.handleTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s, t.handleUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)
	return nil
}

// readLoop feeds one stream's packets into the stack, in order.
func (t *tunnel) readLoop(conn net.Conn) error {
	r := bufio.NewReaderSize(conn, 256<<10)
	head := make([]byte, 6)
	hl := t.headLen()
	for {
		if _, err := io.ReadFull(r, head[:2]); err != nil {
			return err
		}
		n := int(binary.BigEndian.Uint16(head))
		if n == 0 {
			continue // keep-alive
		}
		if _, err := io.ReadFull(r, head[2:hl]); err != nil {
			return err
		}
		pkt := make([]byte, n)
		if _, err := io.ReadFull(r, pkt); err != nil {
			return err
		}
		t.stats.Up.Add(int64(n))
		if !t.seq {
			t.inject(pkt)
			continue
		}
		if err := t.deliver(binary.BigEndian.Uint32(head[2:]), pkt); err != nil {
			return err
		}
	}
}

// deliver injects packets in seq order, holding any that arrive before their turn.
func (t *tunnel) deliver(seq uint32, pkt []byte) error {
	t.inMu.Lock()
	defer t.inMu.Unlock()
	if d := int32(seq - t.next); d < 0 {
		return nil // already delivered
	} else if d > 0 {
		if len(t.pending) > 1<<16 {
			return errors.New("the Thor's streams fell out of step")
		}
		t.pending[seq] = pkt
		return nil
	}
	for {
		t.inject(pkt)
		t.next++
		var ok bool
		if pkt, ok = t.pending[t.next]; !ok {
			return nil
		}
		delete(t.pending, t.next)
	}
}

func (t *tunnel) inject(pkt []byte) {
	var proto tcpip.NetworkProtocolNumber
	switch pkt[0] >> 4 {
	case 4:
		proto = header.IPv4ProtocolNumber
	case 6:
		proto = header.IPv6ProtocolNumber
	default:
		return
	}
	pb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(pkt)})
	t.ep.InjectInbound(proto, pb)
	pb.DecRef()
}

// writeLoop numbers the stack's packets for the Thor and deals them across the streams.
func (t *tunnel) writeLoop() {
	var seq uint32
	next := 0
	for {
		pkt := t.ep.ReadContext(t.ctx)
		if pkt == nil {
			return
		}
		view := pkt.ToView()
		pkt.DecRef()
		n := view.Size()
		hl := t.headLen()
		frame := make([]byte, hl+n)
		binary.BigEndian.PutUint16(frame, uint16(n))
		if t.seq {
			binary.BigEndian.PutUint32(frame[2:], seq)
			seq++
		}
		view.Read(frame[hl:])
		view.Release()
		// Round robin, skipping a stream whose queue is full while another has room.
		sent := false
		for k := 0; k < t.count && !sent; k++ {
			select {
			case t.out[(next+k)%t.count] <- frame:
				next = (next + k + 1) % t.count
				sent = true
			default:
			}
		}
		if !sent {
			select {
			case t.out[next] <- frame:
				next = (next + 1) % t.count
			case <-t.ctx.Done():
				return
			}
		}
	}
}

// sendLoop writes stream i's frames, flushing whenever its queue runs dry.
func (t *tunnel) sendLoop(i int, conn net.Conn) {
	w := bufio.NewWriterSize(conn, 256<<10)
	for {
		var frame []byte
		select {
		case frame = <-t.out[i]:
		case <-t.ctx.Done():
			return
		}
		if _, err := w.Write(frame); err != nil {
			t.fail(err)
			return
		}
		t.stats.Down.Add(int64(len(frame) - t.headLen()))
		if len(t.out[i]) == 0 {
			if err := w.Flush(); err != nil {
				t.fail(err)
				return
			}
		}
	}
}

// headLen is a frame's header size: the length, then seq in version 2.
func (t *tunnel) headLen() int {
	if t.seq {
		return 6
	}
	return 2
}

// target is where a connection from the Thor really goes.
func (t *tunnel) target(addr tcpip.Address, port uint16) string {
	if addr == gateway4 || addr == gateway6 {
		if port == 53 {
			return t.dns()
		}
		return net.JoinHostPort("127.0.0.1", strconv.Itoa(int(port)))
	}
	return net.JoinHostPort(addr.String(), strconv.Itoa(int(port)))
}

func (t *tunnel) handleTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst := t.target(id.LocalAddress, id.LocalPort)
	go func() {
		remote, err := net.DialTimeout("tcp", dst, 10*time.Second)
		if err != nil {
			r.Complete(true) // reset: the app sees "connection refused" right away
			return
		}
		var wq waiter.Queue
		ep, tcpErr := r.CreateEndpoint(&wq)
		if tcpErr != nil {
			r.Complete(true)
			remote.Close()
			return
		}
		r.Complete(false)
		local := gonet.NewTCPConn(&wq, ep)
		splice(local, remote.(*net.TCPConn))
	}()
}

// splice copies both ways, passing on half-closes, until both directions finish.
func splice(local *gonet.TCPConn, remote *net.TCPConn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		io.Copy(remote, local)
		remote.CloseWrite()
	}()
	go func() {
		defer wg.Done()
		io.Copy(local, remote)
		local.CloseWrite()
	}()
	wg.Wait()
	local.Close()
	remote.Close()
}

const (
	udpIdle = 2 * time.Minute
	dnsIdle = 15 * time.Second

	// A game stream sends a frame as one burst: a 150 KB video keyframe is over a hundred
	// packets back to back. The OS default socket buffer (about 64 KB on Windows) and gVisor's
	// 32 KiB UDP buffers overflow before the pipe below drains them, and UDP has no resend, so
	// the stream loses packets. 4 MiB is gVisor's largest UDP buffer.
	udpBuf = 4 << 20
)

func (t *tunnel) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := t.target(id.LocalAddress, id.LocalPort)
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		return false
	}
	ep.SocketOptions().SetReceiveBufferSize(udpBuf, false)
	ep.SocketOptions().SetSendBufferSize(udpBuf, false)
	local := gonet.NewUDPConn(&wq, ep)
	idle := udpIdle
	if id.LocalPort == 53 {
		idle = dnsIdle
	}
	go func() {
		defer local.Close()
		remote, err := net.Dial("udp", dst)
		if err != nil {
			return
		}
		defer remote.Close()
		if uc, ok := remote.(*net.UDPConn); ok {
			// The OS may grant less than asked (Linux caps it at net.core.rmem_max); that's fine.
			uc.SetReadBuffer(udpBuf)
			uc.SetWriteBuffer(udpBuf)
		}
		// Each side's reads push both deadlines out; a quiet flow ends after idle.
		touch := func() {
			at := time.Now().Add(idle)
			local.SetReadDeadline(at)
			remote.SetReadDeadline(at)
		}
		touch()
		done := make(chan struct{}, 2)
		pipe := func(dst io.Writer, src interface{ Read([]byte) (int, error) }) {
			buf := make([]byte, 65535)
			for {
				n, err := src.Read(buf)
				if err != nil {
					break
				}
				touch()
				if _, err := dst.Write(buf[:n]); err != nil {
					break
				}
			}
			done <- struct{}{}
		}
		go pipe(remote, local)
		go pipe(local, remote)
		<-done
		local.Close()
		remote.Close()
		<-done
	}()
	return true
}
