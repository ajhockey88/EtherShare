//go:build !windows

package main

import (
	"bufio"
	"os"
	"strings"
)

// systemDNSServers reads the nameservers from /etc/resolv.conf (Linux and macOS). A local
// resolver such as systemd-resolved's 127.0.0.53 is fine: the relay runs on this PC.
func systemDNSServers() []string {
	f, err := os.Open("/etc/resolv.conf")
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []string
	s := bufio.NewScanner(f)
	for s.Scan() {
		fields := strings.Fields(s.Text())
		if len(fields) >= 2 && fields[0] == "nameserver" {
			out = append(out, fields[1])
		}
	}
	return out
}
