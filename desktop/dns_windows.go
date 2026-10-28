//go:build windows

package main

import (
	"net"
	"unsafe"

	"golang.org/x/sys/windows"
)

// systemDNSServers lists the DNS servers of the network adapters that are up, ethernet first.
func systemDNSServers() []string {
	size := uint32(15000)
	var buf []byte
	var first *windows.IpAdapterAddresses
	for i := 0; i < 3; i++ {
		buf = make([]byte, size)
		first = (*windows.IpAdapterAddresses)(unsafe.Pointer(&buf[0]))
		err := windows.GetAdaptersAddresses(windows.AF_UNSPEC, windows.GAA_FLAG_SKIP_ANYCAST|windows.GAA_FLAG_SKIP_MULTICAST, 0, first, &size)
		if err == nil {
			break
		}
		if err != windows.ERROR_BUFFER_OVERFLOW {
			return nil
		}
		first = nil
	}
	if first == nil {
		return nil
	}
	var wired, other []string
	for a := first; a != nil; a = a.Next {
		if a.OperStatus != windows.IfOperStatusUp || a.IfType == windows.IF_TYPE_SOFTWARE_LOOPBACK {
			continue
		}
		for d := a.FirstDnsServerAddress; d != nil; d = d.Next {
			ip := d.Address.IP()
			if ip == nil || ip.IsUnspecified() || isSiteLocalV6(ip) {
				continue
			}
			if a.IfType == windows.IF_TYPE_ETHERNET_CSMACD {
				wired = append(wired, ip.String())
			} else {
				other = append(other, ip.String())
			}
		}
	}
	return append(wired, other...)
}

// fec0::/10 are Windows' placeholder DNS addresses on adapters without IPv6 DNS.
func isSiteLocalV6(ip net.IP) bool {
	return ip.To4() == nil && len(ip) == net.IPv6len && ip[0] == 0xfe && ip[1]&0xc0 == 0xc0
}
