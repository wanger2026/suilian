package main

import (
	"context"
	"encoding/json"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

type NetworkStatus struct {
	EntryConnected bool   `json:"entryConnected"`
	PhoneJoined    bool   `json:"phoneJoined"`
	Message        string `json:"message"`
}

func parseNetworkStatus(data []byte) NetworkStatus {
	var peers []struct {
		IPv4 string `json:"ipv4"`
		Cost string `json:"cost"`
	}
	result := NetworkStatus{Message: "组网状态暂时无法读取"}
	if json.Unmarshal(data, &peers) != nil {
		return result
	}
	for _, peer := range peers {
		if peer.Cost == "Local" {
			continue
		}
		result.EntryConnected = true
		if peer.IPv4 == "10.144.77.2" && strings.EqualFold(peer.Cost, "p2p") {
			result.PhoneJoined = true
		}
	}
	switch {
	case result.PhoneJoined:
		result.Message = "手机隧道已直连，实际出口请看流量和连通检测"
	case result.EntryConnected:
		result.Message = "发现通道已连接，等待手机建立直连"
	default:
		result.Message = "发现通道未接通，手机暂时无法直连"
	}
	return result
}

func (a *App) monitorNetwork(ctx context.Context) {
	for ctx.Err() == nil {
		probeCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		cmd := exec.CommandContext(probeCtx, filepath.Join(a.directory, "runtime", "easytier", "easytier-cli.exe"), "-o", "json", "peer")
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		output, err := cmd.Output()
		cancel()
		result := parseNetworkStatus(output)
		if err != nil {
			result = NetworkStatus{Message: "正在等待组网组件"}
		}
		a.mu.Lock()
		a.network = result
		a.mu.Unlock()
		select {
		case <-ctx.Done():
			return
		case <-time.After(10 * time.Second):
		}
	}
}
