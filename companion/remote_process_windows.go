package main

import (
	"crypto/sha256"
	"errors"
	"golang.org/x/sys/windows"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unsafe"
)

func (a *App) stopManagedRemote() error {
	payload, err := os.ReadFile(filepath.Join(a.directory, "runtime", "rustdesk", "rustdesk.exe"))
	if err != nil {
		return errors.New("远控组件不完整，请完整解压电脑端")
	}
	expected := sha256.Sum256(payload)
	snapshot, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(snapshot)
	entry := windows.ProcessEntry32{Size: uint32(unsafe.Sizeof(windows.ProcessEntry32{}))}
	handles := []windows.Handle{}
	defer func() {
		for _, handle := range handles {
			windows.CloseHandle(handle)
		}
	}()
	for err = windows.Process32First(snapshot, &entry); err == nil; err = windows.Process32Next(snapshot, &entry) {
		if !strings.EqualFold(windows.UTF16ToString(entry.ExeFile[:]), "rustdesk.exe") {
			continue
		}
		handle, e := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION|windows.PROCESS_TERMINATE|windows.SYNCHRONIZE, false, entry.ProcessID)
		if e != nil {
			return errors.New("远控组件权限不足，请以管理员方式启动望川管理工具")
		}
		handles = append(handles, handle)
		buffer := make([]uint16, 32768)
		n := uint32(len(buffer))
		if windows.QueryFullProcessImageName(handle, 0, &buffer[0], &n) != nil {
			return errors.New("无法确认现有远控程序身份，未更改设置")
		}
		path := windows.UTF16ToString(buffer[:n])
		managed := filepath.Join(a.directory, "runtime", "rustdesk", "rustdesk.exe")
		legacy := filepath.Join(os.Getenv("LOCALAPPDATA"), "rustdesk", "rustdesk.exe")
		if !strings.EqualFold(path, managed) && !strings.EqualFold(path, legacy) {
			return errors.New("检测到另一套RustDesk，请先退出它后再一键准备；原设置尚未更改")
		}
		binary, e := os.ReadFile(path)
		if e != nil || sha256.Sum256(binary) != expected {
			return errors.New("现有RustDesk版本与内置组件不同，未更改它的设置")
		}
	}
	for _, handle := range handles {
		if err := windows.TerminateProcess(handle, 0); err != nil {
			return err
		}
		windows.WaitForSingleObject(handle, 3000)
	}
	// Wait releases the child slot; only the validated bundled process is stopped.
	for i := 0; i < 30; i++ {
		a.mu.Lock()
		_, active := a.children["rustdesk"]
		a.mu.Unlock()
		if !active {
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return errors.New("远控组件正在退出，请稍后重试")
}
