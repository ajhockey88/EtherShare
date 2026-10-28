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

// The tunnel protocol, spoken over the ADB reverse socket:
//
//	Thor -> PC:  "ETHS" 0x01                      (hello, protocol version 1)
//	PC -> Thor:  "ETHS" 0x01 <len u8> <PC name>   (welcome)
//	both ways:   <len u16 big-endian> <one IPv4 or IPv6 packet>, repeated
//
// The Thor's VPN hands every packet its apps send to this relay unchanged. The relay runs
// them through gVisor's user-space TCP/IP stack, which ends each TCP connection and UDP flow
// here on the PC and opens a real socket for it, so the traffic leaves through the PC's own
// network connection (its ethernet) and needs no admin rights or drivers.
const (
	protoMagic   = "ETHS"
	protoVersion = 1
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

type tunnel struct {
	conn    net.Conn
	stack   *stack.Stack
	ep      *channel.Endpoint
	stats   *Stats
	dns     func() string
	ctx     context.Context
	cancel  context.CancelFunc
	closing sync.Once
}

// Serve runs one Thor's tunnel until the connection closes.
func Serve(conn net.Conn, pcName string, stats *Stats, dns func() string) error {
	defer conn.Close()
	if err := handshake(conn, pcName); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	t := &tunnel{conn: conn, stats: stats, dns: dns, ctx: ctx, cancel: cancel}
	if err := t.buildStack(); err != nil {
		cancel()
		return err
	}
	defer t.stack.Destroy()
	go t.writeLoop()
	err := t.readLoop()
	t.close()
	if errors.Is(err, io.EOF) || errors.Is(err, net.ErrClosed) {
		return nil
	}
	return err
}

func (t *tunnel) close() {
	t.closing.Do(func() {
		t.cancel()
		t.conn.Close()
	})
}

func handshake(conn net.Conn, pcName string) error {
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	defer conn.SetDeadline(time.Time{})
	hello := make([]byte, 5)
	if _, err := io.ReadFull(conn, hello); err != nil {
		return fmt.Errorf("no hello from the Thor: %w", err)
	}
	if string(hello[:4]) != protoMagic {
		return errors.New("not an EtherShare connection")
	}
	if hello[4] != protoVersion {
		return fmt.Errorf("the Thor's EtherShare speaks version %d, this program speaks %d: update both", hello[4], protoVersion)
	}
	if len(pcName) > 255 {
		pcName = pcName[:255]
	}
	welcome := append([]byte(protoMagic), protoVersion, byte(len(pcName)))
	_, err := conn.Write(append(welcome, pcName...))
	return err
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

// readLoop feeds the Thor's packets into the stack.
func (t *tunnel) readLoop() error {
	r := bufio.NewReaderSize(t.conn, 256<<10)
	var size [2]byte
	for {
		if _, err := io.ReadFull(r, size[:]); err != nil {
			return err
		}
		n := int(binary.BigEndian.Uint16(size[:]))
		if n == 0 {
			continue // keep-alive
		}
		pkt := make([]byte, n)
		if _, err := io.ReadFull(r, pkt); err != nil {
			return err
		}
		t.stats.Up.Add(int64(n))
		var proto tcpip.NetworkProtocolNumber
		switch pkt[0] >> 4 {
		case 4:
			proto = header.IPv4ProtocolNumber
		case 6:
			proto = header.IPv6ProtocolNumber
		default:
			continue
		}
		pb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(pkt)})
		t.ep.InjectInbound(proto, pb)
		pb.DecRef()
	}
}

// writeLoop sends the stack's packets to the Thor, flushing whenever the queue runs dry.
func (t *tunnel) writeLoop() {
	defer t.close()
	w := bufio.NewWriterSize(t.conn, 256<<10)
	var size [2]byte
	for {
		pkt := t.ep.ReadContext(t.ctx)
		if pkt == nil {
			return
		}
		view := pkt.ToView()
		pkt.DecRef()
		n := view.Size()
		binary.BigEndian.PutUint16(size[:], uint16(n))
		w.Write(size[:])
		_, err := view.WriteTo(w)
		view.Release()
		if err != nil {
			return
		}
		t.stats.Down.Add(int64(n))
		if t.ep.NumQueued() == 0 {
			if err := w.Flush(); err != nil {
				return
			}
		}
	}
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
)

func (t *tunnel) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := t.target(id.LocalAddress, id.LocalPort)
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		return false
	}
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
