package main

import (
	"context"
	"fmt"
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// A GUI-started backend cannot outlive that exact manager process. Keep a
// process handle rather than repeatedly trusting a PID that Windows may reuse.
func watchManager(ctx context.Context, cancel context.CancelFunc, pid uint32, directory string) error {
	handle, err := windows.OpenProcess(windows.SYNCHRONIZE|windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return err
	}
	buffer := make([]uint16, 32768)
	size := uint32(len(buffer))
	err = windows.QueryFullProcessImageName(handle, 0, &buffer[0], &size)
	if err != nil || !strings.EqualFold(windows.UTF16ToString(buffer[:size]), filepath.Join(directory, "WangChuanManager.exe")) {
		windows.CloseHandle(handle)
		return fmt.Errorf("manager process identity mismatch")
	}
	go func() {
		defer windows.CloseHandle(handle)
		ticker := time.NewTicker(250 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				status, err := windows.WaitForSingleObject(handle, 0)
				if err != nil || status != uint32(windows.WAIT_TIMEOUT) {
					cancel()
					return
				}
			}
		}
	}()
	return nil
}

// Guard the backend itself before it can create ANY child. Children and their
// descendants inherit the job atomically at creation, including daemonized ADB.
// The non-inheritable handle deliberately lives until OS process teardown: both
// normal exit and forced termination close it and kill the entire owned tree.
// Never enable either BREAKAWAY flag or give this handle to another process.
func guardProcessTree() error {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return fmt.Errorf("create lifetime job: %w", err)
	}
	limits := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	limits.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	_, err = windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&limits)), uint32(unsafe.Sizeof(limits)))
	if err == nil {
		err = windows.AssignProcessToJobObject(job, windows.CurrentProcess())
	}
	if err != nil {
		windows.CloseHandle(job)
		return fmt.Errorf("enable lifetime job: %w", err)
	}
	return nil
}

func (a *App) stopChildren() {
	a.mu.Lock()
	a.stopping = true
	for _, child := range a.children {
		_ = child.Process.Kill()
	}
	a.mu.Unlock()
	// Wait must not hold mu: each child's waiter removes its own map entry.
	a.childWait.Wait()
}
