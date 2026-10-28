// EtherShare for PC: shares this computer's internet connection with an AYN Thor (or any
// Android device running the EtherShare app) over its USB cable.
package main

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var version = "dev" // set by the release build

const releasesURL = "https://github.com/ajhockey88/EtherShare/releases/latest"

var printMu sync.Mutex
var statusShown bool

// logf prints a timestamped line, clearing the live speed line first.
func logf(format string, args ...any) {
	printMu.Lock()
	defer printMu.Unlock()
	if statusShown {
		fmt.Print("\r" + strings.Repeat(" ", 78) + "\r")
		statusShown = false
	}
	fmt.Printf("%s  %s\n", time.Now().Format("15:04:05"), fmt.Sprintf(format, args...))
}

// status rewrites the live speed line in place.
func status(s string) {
	printMu.Lock()
	defer printMu.Unlock()
	fmt.Printf("\r%-78s", s)
	statusShown = true
}

func main() {
	port := flag.Int("port", 27183, "local TCP port the tunnel uses")
	adbPath := flag.String("adb", "", "path to adb (found or downloaded automatically if empty)")
	noADB := flag.Bool("no-adb", false, "don't run adb; only wait for tunnels (set up 'adb reverse localabstract:ethershare tcp:PORT' yourself)")
	flag.Parse()

	fmt.Printf("EtherShare %s for %s\n", version, prettyOS())
	fmt.Println("Shares this PC's internet with your Thor over USB. Leave this window open.")
	fmt.Println()

	if err := run(*port, *adbPath, *noADB); err != nil {
		logf("Stopped: %v", err)
		if runtime.GOOS == "windows" {
			fmt.Println("\nPress Enter to close.")
			bufio.NewReader(os.Stdin).ReadString('\n')
		}
		os.Exit(1)
	}
}

func prettyOS() string {
	switch runtime.GOOS {
	case "windows":
		return "Windows"
	case "darwin":
		return "macOS"
	}
	return "Linux"
}

func run(port int, adbPath string, noADB bool) error {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return fmt.Errorf("can't use port %d (is EtherShare already open?): %w", port, err)
	}
	pcName, _ := os.Hostname()
	if pcName == "" {
		pcName = "PC"
	}

	var active atomic.Int32
	stats := &Stats{}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				n := active.Add(1)
				logf("Thor connected. It's now using this PC's internet (%d connected).", n)
				err := Serve(conn, pcName, stats, pcDNS)
				n = active.Add(-1)
				if err != nil {
					logf("Thor disconnected: %v", err)
				} else {
					logf("Thor disconnected.")
				}
			}()
		}
	}()
	go showSpeed(stats, &active)

	if noADB {
		logf("Waiting on 127.0.0.1:%d.", port)
		select {}
	}
	if adbPath == "" {
		adbPath, err = findADB(logf)
		if err != nil {
			return err
		}
	}
	a := adb{adbPath}
	logf("Using adb at %s", adbPath)
	logf("Waiting for your Thor. Plug it in with USB debugging on.")
	watchDevices(a, port, pcName)
	return nil
}

// watchDevices notices Thors being plugged in and starts EtherShare on each.
func watchDevices(a adb, port int, pcName string) {
	type device struct {
		state   string
		started bool
		told    string // the last problem shown for it, so it's said once
		name    string
		nextTry time.Time
	}
	known := map[string]*device{}
	failures := 0
	for {
		found, err := a.devices()
		if err != nil {
			failures++
			if failures == 1 || failures%30 == 0 {
				logf("adb isn't answering (%v). Retrying…", err)
			}
			time.Sleep(2 * time.Second)
			continue
		}
		failures = 0
		for serial, state := range found {
			d := known[serial]
			if d == nil {
				d = &device{}
				known[serial] = d
			}
			if state != d.state {
				d.state = state
				switch state {
				case "unauthorized":
					logf("A device is plugged in. Tap Allow on the \"Allow USB debugging?\" prompt on your Thor.")
				case "device":
					d.nextTry = time.Time{}
				}
			}
			if state != "device" || d.started || time.Now().Before(d.nextTry) {
				continue
			}
			if d.name == "" {
				d.name = a.model(serial)
			}
			name := d.name
			err := a.connect(serial, port, pcName)
			if errors.Is(err, errNotInstalled) {
				if apk := bundledAPK(); apk != "" {
					logf("Installing EtherShare on %s from %s…", name, filepath.Base(apk))
					if ierr := a.install(serial, apk); ierr == nil {
						err = a.connect(serial, port, pcName)
					} else {
						err = fmt.Errorf("install failed: %v", ierr)
					}
				}
			}
			if err != nil {
				msg := err.Error()
				if errors.Is(err, errNotInstalled) {
					msg = fmt.Sprintf("%s is plugged in, but %v. Get the app from %s", name, err, releasesURL)
				} else {
					msg = fmt.Sprintf("Couldn't start EtherShare on %s: %v", name, err)
				}
				if msg != d.told {
					logf("%s", msg)
					d.told = msg
				}
				d.nextTry = time.Now().Add(5 * time.Second)
				continue
			}
			d.started = true
			d.told = ""
			logf("Found %s. Starting EtherShare on it (approve the VPN prompt on the Thor the first time).", name)
		}
		for serial, d := range known {
			if _, ok := found[serial]; !ok {
				if d.state == "device" {
					name := d.name
					if name == "" {
						name = serial
					}
					logf("%s was unplugged.", name)
				}
				delete(known, serial)
			}
		}
		time.Sleep(1500 * time.Millisecond)
	}
}

// bundledAPK is an EtherShare.apk next to this program, installed on a Thor that lacks the app.
func bundledAPK() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	p := filepath.Join(filepath.Dir(exe), "EtherShare.apk")
	if isFile(p) {
		return p
	}
	return ""
}

func showSpeed(stats *Stats, active *atomic.Int32) {
	var lastDown, lastUp int64
	last := time.Now()
	for range time.Tick(time.Second) {
		down, up := stats.Down.Load(), stats.Up.Load()
		now := time.Now()
		secs := now.Sub(last).Seconds()
		if active.Load() > 0 {
			status(fmt.Sprintf("  Down %s   Up %s   Total %s",
				rate(float64(down-lastDown)/secs), rate(float64(up-lastUp)/secs), size(down+up)))
		}
		lastDown, lastUp, last = down, up, now
	}
}

func rate(bytesPerSec float64) string {
	mbps := bytesPerSec * 8 / 1e6
	if mbps >= 100 {
		return fmt.Sprintf("%4.0f Mbps", mbps)
	}
	return fmt.Sprintf("%4.1f Mbps", mbps)
}

func size(b int64) string {
	switch {
	case b >= 1<<30:
		return fmt.Sprintf("%.2f GB", float64(b)/(1<<30))
	case b >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(b)/(1<<20))
	}
	return fmt.Sprintf("%d KB", b>>10)
}
