package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

func adbRunner(directory, state string) func(context.Context, string, ...string) ([]byte, error) {
	return func(parent context.Context, input string, args ...string) ([]byte, error) {
		ctx, cancel := context.WithTimeout(parent, 20*time.Second)
		defer cancel()
		keyDirectory := filepath.Join(state, "development-adb")
		if err := privateDirectory(keyDirectory); err != nil {
			return nil, err
		}
		command := exec.CommandContext(ctx, filepath.Join(directory, "runtime", "adb", "adb.exe"), append([]string{"-P", "17885"}, args...)...)
		env := []string{}
		for _, entry := range os.Environ() {
			name := strings.ToUpper(strings.SplitN(entry, "=", 2)[0])
			if name == "ANDROID_USER_HOME" || strings.HasPrefix(name, "ADB_") || name == "ANDROID_ADB_SERVER_PORT" {
				continue
			}
			env = append(env, entry)
		}
		command.Env = append(env, "ANDROID_USER_HOME="+keyDirectory, "ADB_MDNS_AUTO_CONNECT=0")
		command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		command.Stdin = strings.NewReader(input)
		// Pairing codes are passed through stdin, never arguments or log files.
		return command.CombinedOutput()
	}
}
