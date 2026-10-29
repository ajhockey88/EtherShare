package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// fakeThor stands in for the Thor's VPN: a TCP/IP stack of its own (as Android has) whose
// packets go through the tunnel exactly as the app sends them.
type fakeThor struct {
	s      *stack.Stack
	cancel context.CancelFunc
}

// newFakeThor speaks version 1 over one conn, or version 2 dealing packets across all conns.
func newFakeThor(t *testing.T, version byte, conns []net.Conn) *fakeThor {
	t.Helper()
	session := uint64(time.Now().UnixNano())
	for i, conn := range conns {
		h := []byte{'E', 'T', 'H', 'S', version}
		if version >= 2 {
			h = binary.BigEndian.AppendUint64(h, session)
			h = append(h, byte(i), byte(len(conns)))
		}
		conn.Write(h)
		head := make([]byte, 6)
		if _, err := io.ReadFull(conn, head); err != nil || string(head[:4]) != protoMagic || head[4] != version {
			t.Fatalf("bad welcome %q %v", head, err)
		}
		name := make([]byte, head[5])
		io.ReadFull(conn, name)
		if string(name) != "test-pc" {
			t.Fatalf("PC name %q", name)
		}
	}
	hl := 2
	if version >= 2 {
		hl = 6
	}

	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep := channel.New(1024, tunnelMTU, "")
	s.CreateNIC(1, ep)
	s.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          header.IPv4ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{10, 88, 0, 2}).WithPrefix(),
	}, stack.AddressProperties{})
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	ctx, cancel := context.WithCancel(context.Background())

	// Thor -> PC: numbered and dealt round robin, a writer per stream.
	queues := make([]chan []byte, len(conns))
	for i, conn := range conns {
		queues[i] = make(chan []byte, 256)
		go func(q chan []byte, w *bufio.Writer) {
			for f := range q {
				w.Write(f)
				if len(q) == 0 {
					w.Flush()
				}
			}
		}(queues[i], bufio.NewWriter(conn))
	}
	go func() {
		var seq uint32
		for i := 0; ; i++ {
			pkt := ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			v := pkt.ToView()
			pkt.DecRef()
			f := make([]byte, hl+v.Size())
			binary.BigEndian.PutUint16(f, uint16(v.Size()))
			if hl == 6 {
				binary.BigEndian.PutUint32(f[2:], seq)
				seq++
			}
			v.Read(f[hl:])
			queues[i%len(queues)] <- f
		}
	}()

	// PC -> Thor: each stream's packets, put back in order.
	var mu sync.Mutex
	var next uint32
	pending := map[uint32][]byte{}
	inject := func(p []byte) {
		pb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p)})
		ep.InjectInbound(header.IPv4ProtocolNumber, pb)
		pb.DecRef()
	}
	for _, conn := range conns {
		go func(r *bufio.Reader) {
			head := make([]byte, 6)
			for {
				if _, err := io.ReadFull(r, head[:hl]); err != nil {
					return
				}
				p := make([]byte, binary.BigEndian.Uint16(head))
				if _, err := io.ReadFull(r, p); err != nil {
					return
				}
				mu.Lock()
				if hl == 2 {
					inject(p)
				} else if seq := binary.BigEndian.Uint32(head[2:]); seq != next {
					pending[seq] = p
				} else {
					for ok := true; ok; p, ok = pending[next] {
						delete(pending, next)
						inject(p)
						next++
					}
				}
				mu.Unlock()
			}
		}(bufio.NewReader(conn))
	}
	return &fakeThor{s: s, cancel: cancel}
}

// stopAndWait is a stand-in for one ADB stream: it passes at most chunk bytes at a time, and
// waits rtt for the far end's go-ahead before reading the next chunk.
func stopAndWait(t *testing.T, target string, chunk int, rtt time.Duration) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	pipe := func(dst, src net.Conn) {
		buf := make([]byte, chunk)
		for {
			n, err := src.Read(buf)
			if err != nil {
				dst.Close()
				return
			}
			if _, err := dst.Write(buf[:n]); err != nil {
				src.Close()
				return
			}
			time.Sleep(rtt)
		}
	}
	go func() {
		for {
			a, err := ln.Accept()
			if err != nil {
				return
			}
			b, err := net.Dial("tcp", target)
			if err != nil {
				a.Close()
				continue
			}
			go pipe(a, b)
			go pipe(b, a)
		}
	}()
	return ln.Addr().String()
}

type relayOpts struct {
	version byte
	streams int
	// When set, each stream goes through a stopAndWait stand-in for ADB.
	chunk int
	rtt   time.Duration
}

func startRelay(t *testing.T, dns string) (*fakeThor, *Stats) {
	return startRelayWith(t, dns, relayOpts{version: protoVersion, streams: 8})
}

func startRelayWith(t *testing.T, dns string, o relayOpts) (*fakeThor, *Stats) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	stats := &Stats{}
	relay := &Relay{PCName: "test-pc", Stats: stats, DNS: func() string { return dns }}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go relay.Handle(c)
		}
	}()
	addr := ln.Addr().String()
	if o.chunk > 0 {
		addr = stopAndWait(t, addr, o.chunk, o.rtt)
	}
	var conns []net.Conn
	for i := 0; i < o.streams; i++ {
		conn, err := net.Dial("tcp", addr)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, conn)
	}
	thor := newFakeThor(t, o.version, conns)
	t.Cleanup(func() {
		thor.cancel()
		for _, c := range conns {
			c.Close()
		}
		ln.Close()
	})
	return thor, stats
}

func gatewayAddr(port int) tcpip.FullAddress {
	return tcpip.FullAddress{NIC: 1, Addr: gateway4, Port: uint16(port)}
}

// download fetches total bytes from a local server through the tunnel, checks them, and
// returns the speed in Mbps.
func download(t *testing.T, total int, start func() *fakeThor) float64 {
	t.Helper()
	payload := make([]byte, 1<<20)
	rand.Read(payload)
	server, _ := net.Listen("tcp", "127.0.0.1:0")
	defer server.Close()
	go func() {
		c, err := server.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		for sent := 0; sent < total; sent += len(payload) {
			if _, err := c.Write(payload); err != nil {
				return
			}
		}
	}()
	thor := start()
	port := server.Addr().(*net.TCPAddr).Port
	c, err := gonet.DialTCP(thor.s, gatewayAddr(port), header.IPv4ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	began := time.Now()
	got := make([]byte, 1<<20)
	n := 0
	for {
		m, err := io.ReadFull(c, got)
		if m > 0 {
			if string(got[:m]) != string(payload[:m]) {
				t.Fatalf("corrupt data at %d", n)
			}
			n += m
		}
		if err != nil {
			break
		}
	}
	if n != total {
		t.Fatalf("got %d bytes, want %d", n, total)
	}
	return float64(n) * 8 / time.Since(began).Seconds() / 1e6
}

func TestTCPDownloadThroughTunnel(t *testing.T) {
	var stats *Stats
	mbps := download(t, 64<<20, func() *fakeThor {
		thor, s := startRelay(t, "")
		stats = s
		return thor
	})
	t.Logf("%.0f Mbps through the relay over 8 streams (down counter %d MB)", mbps, stats.Down.Load()>>20)
}

func TestVersion1AppStillWorks(t *testing.T) {
	mbps := download(t, 16<<20, func() *fakeThor {
		thor, _ := startRelayWith(t, "", relayOpts{version: 1, streams: 1})
		return thor
	})
	t.Logf("%.0f Mbps over one version 1 stream", mbps)
}

// Through stop-and-wait links like ADB's, several streams together beat one, even for a
// single download, because the packets are dealt across them and put back in order.
func TestStreamsBeatStopAndWait(t *testing.T) {
	speed := func(streams int) float64 {
		return download(t, 16<<20, func() *fakeThor {
			thor, _ := startRelayWith(t, "", relayOpts{version: protoVersion, streams: streams,
				chunk: 64 << 10, rtt: 2 * time.Millisecond})
			return thor
		})
	}
	one, eight := speed(1), speed(8)
	t.Logf("one stream %.0f Mbps, eight streams %.0f Mbps", one, eight)
	if eight < 2*one {
		t.Fatalf("eight streams (%.0f Mbps) weren't much faster than one (%.0f Mbps)", eight, one)
	}
}

func TestTCPUploadAndHalfClose(t *testing.T) {
	server, _ := net.Listen("tcp", "127.0.0.1:0")
	defer server.Close()
	got := make(chan int, 1)
	go func() {
		c, err := server.Accept()
		if err != nil {
			return
		}
		n, _ := io.Copy(io.Discard, c) // ends when the Thor half-closes
		c.Write([]byte("thanks"))
		c.Close()
		got <- int(n)
	}()
	thor, _ := startRelay(t, "")
	c, err := gonet.DialTCP(thor.s, gatewayAddr(server.Addr().(*net.TCPAddr).Port), header.IPv4ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	c.Write(make([]byte, 5<<20))
	c.CloseWrite()
	reply, _ := io.ReadAll(c)
	if string(reply) != "thanks" {
		t.Fatalf("reply %q", reply)
	}
	if n := <-got; n != 5<<20 {
		t.Fatalf("server got %d bytes", n)
	}
}

func TestRefusedConnectionResets(t *testing.T) {
	l, _ := net.Listen("tcp", "127.0.0.1:0")
	port := l.Addr().(*net.TCPAddr).Port
	l.Close() // nothing listens there now
	thor, _ := startRelay(t, "")
	start := time.Now()
	if _, err := gonet.DialTCP(thor.s, gatewayAddr(port), header.IPv4ProtocolNumber); err == nil {
		t.Fatal("connected to a closed port")
	}
	if time.Since(start) > 3*time.Second {
		t.Fatal("refusal took too long")
	}
}

func TestDNSGoesToPCResolver(t *testing.T) {
	// A fake "PC DNS server" that answers every query with "pong".
	pc, _ := net.ListenPacket("udp", "127.0.0.1:0")
	defer pc.Close()
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pc.WriteTo(append([]byte("pong:"), buf[:n]...), from)
		}
	}()
	thor, _ := startRelay(t, pc.LocalAddr().String())
	c, err := gonet.DialUDP(thor.s, nil, &tcpip.FullAddress{NIC: 1, Addr: gateway4, Port: 53}, header.IPv4ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.Write([]byte("query"))
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 100)
	n, err := c.Read(buf)
	if err != nil || string(buf[:n]) != "pong:query" {
		t.Fatalf("got %q %v", buf[:n], err)
	}
}

func TestOldAppIsTurnedAway(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	go a.Write([]byte{'E', 'T', 'H', 'S', 99})
	if err := Serve(b, "pc", &Stats{}, pcDNS); err == nil {
		t.Fatal("accepted a newer protocol version")
	}
}

// A game stream sends each frame as a burst of packets back to back. The relay must pass a
// whole burst (a 150 KB video keyframe is 108 packets of 1392 bytes) without losing any: UDP
// has no resend, so a packet dropped in the relay's socket or gVisor buffers is lost for good.
func TestUDPBurstSurvivesTheRelay(t *testing.T) {
	const packets, size = 108, 1392
	game, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer game.Close()
	// The "game": waits for the Thor's first packet, then answers with one tight burst.
	go func() {
		buf := make([]byte, 2048)
		_, from, err := game.ReadFrom(buf)
		if err != nil {
			return
		}
		pkt := make([]byte, size)
		for i := 0; i < packets; i++ {
			binary.BigEndian.PutUint32(pkt, uint32(i))
			game.WriteTo(pkt, from)
		}
	}()

	thor, _ := startRelay(t, "")
	// An endpoint with room for the burst, as an Android socket has; DialUDP would give it
	// gVisor's 32 KiB default and the test would measure the fake Thor instead of the relay.
	var wq waiter.Queue
	ep, tcpErr := thor.s.NewEndpoint(udp.ProtocolNumber, header.IPv4ProtocolNumber, &wq)
	if tcpErr != nil {
		t.Fatal(tcpErr)
	}
	ep.SocketOptions().SetReceiveBufferSize(4<<20, false)
	if tcpErr := ep.Connect(gatewayAddr(game.LocalAddr().(*net.UDPAddr).Port)); tcpErr != nil {
		t.Fatal(tcpErr)
	}
	c := gonet.NewUDPConn(&wq, ep)
	defer c.Close()

	c.Write([]byte("hello"))
	got := make(map[uint32]bool)
	buf := make([]byte, 2048)
	for len(got) < packets {
		c.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := c.Read(buf)
		if err != nil {
			break
		}
		if n != size {
			t.Fatalf("packet of %d bytes, want %d", n, size)
		}
		got[binary.BigEndian.Uint32(buf)] = true
	}
	if len(got) != packets {
		t.Fatalf("%d of %d packets of the burst arrived", len(got), packets)
	}
}
