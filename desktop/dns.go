package main

import (
	"net"
	"sync"
	"time"
)

// pcDNS returns the PC's own DNS server as host:port, so the Thor looks names up exactly as
// the PC does (a Pi-hole, a work VPN's DNS, the router). It's re-read every minute in case
// the PC's network changes.
var pcDNS = func() func() string {
	var mu sync.Mutex
	var cached string
	var at time.Time
	return func() string {
		mu.Lock()
		defer mu.Unlock()
		if cached == "" || time.Since(at) > time.Minute {
			cached = "1.1.1.1:53" // when the PC's can't be found
			if servers := systemDNSServers(); len(servers) > 0 {
				cached = net.JoinHostPort(servers[0], "53")
			}
			at = time.Now()
		}
		return cached
	}
}()
