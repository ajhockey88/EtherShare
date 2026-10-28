package main

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const (
	appPackage    = "com.ethershare"
	socketName    = "ethershare" // the abstract socket the Thor's app connects to
	connectIntent = "com.ethershare.CONNECT"
)

func adbName() string {
	if runtime.GOOS == "windows" {
		return "adb.exe"
	}
	return "adb"
}

// findADB looks for adb next to this program, in an Android SDK, on the PATH, and in
// EtherShare's own cache, and otherwise downloads Google's platform-tools into that cache.
func findADB(logf func(string, ...any)) (string, error) {
	var candidates []string
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		candidates = append(candidates, filepath.Join(dir, adbName()), filepath.Join(dir, "platform-tools", adbName()))
	}
	home, _ := os.UserHomeDir()
	switch runtime.GOOS {
	case "windows":
		candidates = append(candidates, filepath.Join(os.Getenv("LOCALAPPDATA"), "Android", "Sdk", "platform-tools", adbName()))
	case "darwin":
		candidates = append(candidates, filepath.Join(home, "Library", "Android", "sdk", "platform-tools", adbName()))
	default:
		candidates = append(candidates, filepath.Join(home, "Android", "Sdk", "platform-tools", adbName()))
	}
	if sdk := os.Getenv("ANDROID_HOME"); sdk != "" {
		candidates = append(candidates, filepath.Join(sdk, "platform-tools", adbName()))
	}
	for _, c := range candidates {
		if isFile(c) {
			return c, nil
		}
	}
	if p, err := exec.LookPath("adb"); err == nil {
		return p, nil
	}
	cache, err := os.UserCacheDir()
	if err != nil {
		cache = os.TempDir()
	}
	dir := filepath.Join(cache, "EtherShare")
	cached := filepath.Join(dir, "platform-tools", adbName())
	if isFile(cached) {
		return cached, nil
	}
	logf("adb isn't on this PC yet. Downloading Google's platform-tools (about 7 MB)…")
	if err := downloadPlatformTools(dir); err != nil {
		return "", fmt.Errorf("couldn't download adb: %w", err)
	}
	if !isFile(cached) {
		return "", errors.New("the platform-tools download had no adb in it")
	}
	return cached, nil
}

func isFile(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

func downloadPlatformTools(dir string) error {
	osName := map[string]string{"windows": "windows", "darwin": "darwin"}[runtime.GOOS]
	if osName == "" {
		osName = "linux"
	}
	url := "https://dl.google.com/android/repository/platform-tools-latest-" + osName + ".zip"
	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("HTTP %d from %s", resp.StatusCode, url)
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	z, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return err
	}
	for _, f := range z.File {
		name := filepath.Clean(f.Name)
		if !strings.HasPrefix(name, "platform-tools") || strings.Contains(name, "..") {
			continue
		}
		dst := filepath.Join(dir, name)
		if f.FileInfo().IsDir() {
			os.MkdirAll(dst, 0o755)
			continue
		}
		os.MkdirAll(filepath.Dir(dst), 0o755)
		in, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, f.Mode()|0o600)
		if err != nil {
			in.Close()
			return err
		}
		_, err = io.Copy(out, in)
		in.Close()
		out.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

type adb struct{ path string }

func (a adb) run(args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, a.path, args...)
	hideWindow(cmd)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

// devices returns each attached device's serial and state ("device", "unauthorized", ...).
func (a adb) devices() (map[string]string, error) {
	out, err := a.run("devices")
	if err != nil {
		return nil, fmt.Errorf("adb devices: %v: %s", err, strings.TrimSpace(out))
	}
	found := map[string]string{}
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Fields(line)
		if len(fields) == 2 && !strings.HasPrefix(line, "List of") && !strings.HasPrefix(line, "*") {
			found[fields[0]] = fields[1]
		}
	}
	return found, nil
}

func (a adb) model(serial string) string {
	out, err := a.run("-s", serial, "shell", "getprop", "ro.product.model")
	if err != nil || strings.TrimSpace(out) == "" {
		return serial
	}
	return strings.TrimSpace(out)
}

var errNotInstalled = errors.New("EtherShare isn't installed on it")

// connect points the device's EtherShare socket at the relay and asks the app to start its VPN.
func (a adb) connect(serial string, port int, pcName string) error {
	if out, err := a.run("-s", serial, "reverse", "localabstract:"+socketName, fmt.Sprintf("tcp:%d", port)); err != nil {
		return fmt.Errorf("adb reverse: %v: %s", err, strings.TrimSpace(out))
	}
	out, err := a.run("-s", serial, "shell", "am", "start", "-a", connectIntent,
		"-n", appPackage+"/.ConnectActivity", "--es", "pc", shellQuote(pcName))
	if strings.Contains(out, "does not exist") || strings.Contains(out, "Error type 3") {
		return errNotInstalled
	}
	if err != nil {
		return fmt.Errorf("starting EtherShare: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (a adb) install(serial, apk string) error {
	out, err := a.run("-s", serial, "install", "-r", apk)
	if err != nil || !strings.Contains(out, "Success") {
		return fmt.Errorf("%v %s", err, strings.TrimSpace(out))
	}
	return nil
}

// shellQuote makes a value safe inside the device shell's command line.
func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}
