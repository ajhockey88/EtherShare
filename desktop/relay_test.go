package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
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
)

// fakeThor stands in for the Thor's VPN: a TCP/IP stack of its own (as Android has) whose
// packets go through the tunnel exactly as the app sends them.
type fakeThor struct {
	s      *stack.Stack
	cancel context.CancelFunc
}

func newFakeThor(t *testing.T, conn net.Conn) *fakeThor {
	t.Helper()
	conn.Write([]byte{'E', 'T', 'H', 'S', protoVersion})
	head := make([]byte, 6)
	if _, err := io.ReadFull(conn, head); err != nil || string(head[:4]) != protoMagic {
		t.Fatalf("bad welcome %q %v", head, err)
	}
	name := make([]byte, head[5])
	io.ReadFull(conn, name)
	if string(name) != "test-pc" {
		t.Fatalf("PC name %q", name)
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
	go func() { // Thor -> PC
		w := bufio.NewWriter(conn)
		for {
			pkt := ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			v := pkt.ToView()
			pkt.DecRef()
			var n [2]byte
			binary.BigEndian.PutUint16(n[:], uint16(v.Size()))
			w.Write(n[:])
			v.WriteTo(w)
			if ep.NumQueued() == 0 {
				w.Flush()
			}
		}
	}()
	go func() { // PC -> Thor
		r := bufio.NewReader(conn)
		var n [2]byte
		for {
			if _, err := io.ReadFull(r, n[:]); err != nil {
				return
			}
			p := make([]byte, binary.BigEndian.Uint16(n[:]))
			if _, err := io.ReadFull(r, p); err != nil {
				return
			}
			pb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p)})
			ep.InjectInbound(header.IPv4ProtocolNumber, pb)
			pb.DecRef()
		}
	}()
	return &fakeThor{s: s, cancel: cancel}
}

func startRelay(t *testing.T, dns string) (*fakeThor, *Stats) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	stats := &Stats{}
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		Serve(c, "test-pc", stats, func() string { return dns })
	}()
	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	thor := newFakeThor(t, conn)
	t.Cleanup(func() {
		thor.cancel()
		conn.Close()
		ln.Close()
	})
	return thor, stats
}

func gatewayAddr(port int) tcpip.FullAddress {
	return tcpip.FullAddress{NIC: 1, Addr: gateway4, Port: uint16(port)}
}

func TestTCPDownloadThroughTunnel(t *testing.T) {
	const total = 64 << 20
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
	thor, stats := startRelay(t, "")
	port := server.Addr().(*net.TCPAddr).Port
	c, err := gonet.DialTCP(thor.s, gatewayAddr(port), header.IPv4ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	start := time.Now()
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
	secs := time.Since(start).Seconds()
	if n != total {
		t.Fatalf("got %d bytes, want %d", n, total)
	}
	t.Logf("%d MB in %.2fs = %.0f Mbps through the relay (down counter %d MB)",
		n>>20, secs, float64(n)*8/secs/1e6, stats.Down.Load()>>20)
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
