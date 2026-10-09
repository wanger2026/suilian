package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

// This helper runs only inside isolated test processes; the test runner never
// joins the lifetime job. No installed app or user's running component is used.
func TestLifetimeHelper(t *testing.T) {
	role := os.Getenv("WANGCHUAN_LIFETIME_TEST_ROLE")
	if role == "" {
		return
	}
	dir := os.Getenv("WANGCHUAN_LIFETIME_TEST_DIR")
	spawn := func(role string) *exec.Cmd {
		cmd := exec.Command(os.Args[0], "-test.run=^TestLifetimeHelper$")
		cmd.Env = append(os.Environ(), "WANGCHUAN_LIFETIME_TEST_ROLE="+role)
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		if err := cmd.Start(); err != nil {
			os.Exit(41)
		}
		return cmd
	}
	if role == "owner" {
		if err := guardProcessTree(); err != nil {
			os.Exit(42)
		}
		child := spawn("child")
		os.WriteFile(filepath.Join(dir, "child.pid"), []byte(strconv.Itoa(child.Process.Pid)), 0600)
		for {
			if _, err := os.Stat(filepath.Join(dir, "exit")); err == nil {
				os.Exit(0)
			}
			time.Sleep(25 * time.Millisecond)
		}
	}
	if role == "child" {
		spawn("grandchild")
	}
	if role == "grandchild" {
		path, _ := windows.UTF16PtrFromString(filepath.Join(dir, "locked.bin"))
		handle, err := windows.CreateFile(path, windows.GENERIC_WRITE, 0, nil, windows.CREATE_ALWAYS, windows.FILE_ATTRIBUTE_NORMAL, 0)
		if err != nil {
			os.Exit(43)
		}
		defer windows.CloseHandle(handle)
		os.WriteFile(filepath.Join(dir, "grandchild.pid"), []byte(strconv.Itoa(os.Getpid())), 0600)
	}
	if role == "unrelated" {
		os.WriteFile(filepath.Join(dir, "unrelated.ready"), []byte("ready"), 0600)
	}
	for {
		time.Sleep(time.Second)
	}
}

func waitFixture(t *testing.T, path string) []byte {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if data, err := os.ReadFile(path); err == nil && len(data) > 0 {
			return data
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatalf("fixture not ready: %s", path)
	return nil
}

func TestLifetimeJobReleasesTreeAndFiles(t *testing.T) {
	for _, mode := range []string{"normal-exit", "forced-termination"} {
		t.Run(mode, func(t *testing.T) {
			dir := t.TempDir()
			start := func(role string) *exec.Cmd {
				cmd := exec.Command(os.Args[0], "-test.run=^TestLifetimeHelper$")
				cmd.Env = append(os.Environ(), "WANGCHUAN_LIFETIME_TEST_ROLE="+role, "WANGCHUAN_LIFETIME_TEST_DIR="+dir)
				cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
				if err := cmd.Start(); err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { cmd.Process.Kill(); cmd.Wait() })
				return cmd
			}
			other := start("unrelated")
			waitFixture(t, filepath.Join(dir, "unrelated.ready"))
			owner := start("owner")
			var handles []windows.Handle
			for _, name := range []string{"child.pid", "grandchild.pid"} {
				pid, err := strconv.Atoi(string(waitFixture(t, filepath.Join(dir, name))))
				if err != nil {
					t.Fatal(err)
				}
				handle, err := windows.OpenProcess(windows.SYNCHRONIZE, false, uint32(pid))
				if err != nil {
					t.Fatal(err)
				}
				handles = append(handles, handle)
				defer windows.CloseHandle(handle)
			}
			locked := filepath.Join(dir, "locked.bin")
			if err := os.Rename(locked, locked+".moved"); err == nil {
				t.Fatal("fixture failed to hold a real file lock")
			}
			if mode == "normal-exit" {
				os.WriteFile(filepath.Join(dir, "exit"), []byte("exit"), 0600)
			} else {
				owner.Process.Kill()
			}
			for _, handle := range handles {
				status, err := windows.WaitForSingleObject(handle, 10000)
				if err != nil || status != windows.WAIT_OBJECT_0 {
					t.Fatalf("descendant survived: %v %v", status, err)
				}
			}
			if err := os.Rename(locked, locked+".moved"); err != nil {
				t.Fatalf("file still locked: %v", err)
			}
			handle, err := windows.OpenProcess(windows.SYNCHRONIZE, false, uint32(other.Process.Pid))
			if err != nil {
				t.Fatal(err)
			}
			defer windows.CloseHandle(handle)
			status, _ := windows.WaitForSingleObject(handle, 0)
			if status != uint32(windows.WAIT_TIMEOUT) {
				t.Fatal("unrelated process was terminated")
			}
		})
	}
}

func TestManagerWatcherCancelsOnlyAfterOwnedManagerExits(t *testing.T) {
	dir := t.TempDir()
	data, err := os.ReadFile(os.Args[0])
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "WangChuanManager.exe")
	if err = os.WriteFile(path, data, 0700); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(path, "-test.run=^TestLifetimeHelper$")
	cmd.Env = append(os.Environ(), "WANGCHUAN_LIFETIME_TEST_ROLE=unrelated", "WANGCHUAN_LIFETIME_TEST_DIR="+dir)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { cmd.Process.Kill(); cmd.Wait() })
	waitFixture(t, filepath.Join(dir, "unrelated.ready"))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err = watchManager(ctx, cancel, uint32(cmd.Process.Pid), dir); err != nil {
		t.Fatal(err)
	}
	if err = watchManager(ctx, cancel, uint32(cmd.Process.Pid), filepath.Join(dir, "other")); err == nil {
		t.Fatal("accepted wrong directory")
	}
	if ctx.Err() != nil {
		t.Fatal("live manager incorrectly cancelled")
	}
	cmd.Process.Kill()
	select {
	case <-ctx.Done():
	case <-time.After(5 * time.Second):
		t.Fatal("manager exit did not cancel backend")
	}
}
