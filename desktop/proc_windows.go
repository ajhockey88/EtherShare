//go:build windows

package main

import (
	"os/exec"
	"syscall"
)

// hideWindow keeps adb from flashing a console window of its own.
func hideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
}
