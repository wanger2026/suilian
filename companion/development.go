package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"io"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const devHeartbeatTimeout = 15 * time.Second

type devHello struct {
	Type        string `json:"type"`
	RemoteReady bool   `json:"remoteReady"`
	ADBPort     int    `json:"adbPort"`
	PairPort    int    `json:"pairPort"`
	PairCode    string `json:"pairCode"`
}

func (h devHello) valid() bool {
	if h.Type != "hello" || !h.RemoteReady || h.ADBPort < 1024 || h.ADBPort > 65535 {
		return false
	}
	if h.PairCode == "" {
		return h.PairPort == 0
	}
	if h.PairPort < 1024 || h.PairPort > 65535 || len(h.PairCode) != 6 {
		return false
	}
	for _, c := range h.PairCode {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}
func developmentToken(password string) string {
	mac := hmac.New(sha256.New, []byte(password))
	mac.Write([]byte("wangchuan-development-v1"))
	return hex.EncodeToString(mac.Sum(nil))
}

type DevHub struct {
	mu                      sync.Mutex
	active                  *devSession
	token                   string
	status                  string
	adbAddress, pairAddress string
	runADB                  func(context.Context, string, ...string) ([]byte, error)
}
type devStream struct {
	conn  net.Conn
	ws    *websocket.Conn
	ready chan struct{}
}
type devSession struct {
	hub       *DevHub
	control   *websocket.Conn
	writeMu   sync.Mutex
	mu        sync.Mutex
	streams   map[string]*devStream
	listeners []net.Listener
	done      chan struct{}
	once      sync.Once
	ctx       context.Context
	cancel    context.CancelFunc
}

func newDevHub(password string) *DevHub {
	return &DevHub{token: developmentToken(password), status: "未开启真机联调", adbAddress: "127.0.0.1:17883", pairAddress: "127.0.0.1:17884"}
}
func (h *DevHub) state() map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	return map[string]any{"active": h.active != nil, "status": h.status, "adbServerPort": 17885, "device": "127.0.0.1:17883"}
}
func (h *DevHub) setStatus(s *devSession, status string) {
	h.mu.Lock()
	if h.active == s {
		h.status = status
	}
	h.mu.Unlock()
	s.send(map[string]string{"type": "status", "message": status})
}

var devUpgrade = websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" }}

func (h *DevHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+h.token)) != 1 {
		http.Error(w, "forbidden", 403)
		return
	}
	switch r.URL.Path {
	case "/development/control":
		h.control(w, r)
	case "/development/data":
		h.data(w, r)
	default:
		http.NotFound(w, r)
	}
}
func (h *DevHub) control(w http.ResponseWriter, r *http.Request) {
	ws, err := devUpgrade.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	ws.SetReadLimit(4096)
	ws.SetReadDeadline(time.Now().Add(5 * time.Second))
	var hello devHello
	if ws.ReadJSON(&hello) != nil || !hello.valid() {
		ws.Close()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &devSession{hub: h, control: ws, streams: map[string]*devStream{}, done: make(chan struct{}), ctx: ctx, cancel: cancel}
	h.mu.Lock()
	if h.active != nil {
		h.mu.Unlock()
		cancel()
		ws.Close()
		return
	}
	h.active = s
	h.status = "正在建立手机调试通道"
	h.mu.Unlock()
	defer s.close()
	adbListener, err := net.Listen("tcp", h.adbAddress)
	if err != nil {
		h.setStatus(s, "本机调试端口被占用")
		return
	}
	if !s.addListener(adbListener) {
		return
	}
	go s.accept(adbListener, "adb")
	var pairingListener net.Listener
	if hello.PairPort != 0 {
		pairingListener, err = net.Listen("tcp", h.pairAddress)
		if err != nil {
			h.setStatus(s, "本机配对端口被占用")
			return
		}
		if !s.addListener(pairingListener) {
			return
		}
		go s.accept(pairingListener, "pair")
	}
	if h.runADB != nil {
		go h.connectADB(s, hello, adbListener, pairingListener)
	}
	hello.PairCode = ""
	for {
		ws.SetReadDeadline(time.Now().Add(devHeartbeatTimeout))
		var message struct {
			Type        string `json:"type"`
			RemoteReady bool   `json:"remoteReady"`
		}
		if ws.ReadJSON(&message) != nil || message.Type != "heartbeat" || !message.RemoteReady {
			return
		}
	}
}
func (h *DevHub) connectADB(s *devSession, hello devHello, adb, pair net.Listener) {
	if pair != nil {
		h.setStatus(s, "正在与手机进行系统 ADB 配对")
		_, err := h.runADB(s.ctx, hello.PairCode+"\n", "pair", pair.Addr().String())
		hello.PairCode = ""
		if err != nil {
			h.setStatus(s, "配对失败：请保持配对窗口打开，并核对配对码及端口")
			return
		}
	}
	if _, err := h.runADB(s.ctx, "", "connect", adb.Addr().String()); err != nil {
		h.setStatus(s, "连接失败：请检查无线调试开关与连接端口")
		return
	}
	result, err := h.runADB(s.ctx, "", "-s", adb.Addr().String(), "shell", "echo", "wangchuan-adb-ready")
	if err != nil || stringTrim(result) != "wangchuan-adb-ready" {
		h.setStatus(s, "ADB 尚未授权或不可用，请完成手机系统确认")
		return
	}
	h.setStatus(s, "真机 ADB 已验证，可安装测试包、读取日志和运行自动化")
}
func stringTrim(b []byte) string {
	start, end := 0, len(b)
	for start < end && (b[start] == ' ' || b[start] == '\r' || b[start] == '\n') {
		start++
	}
	for end > start && (b[end-1] == ' ' || b[end-1] == '\r' || b[end-1] == '\n') {
		end--
	}
	return string(b[start:end])
}
func (s *devSession) send(message any) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	s.control.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return s.control.WriteJSON(message)
}
func (s *devSession) addListener(listener net.Listener) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	select {
	case <-s.done:
		listener.Close()
		return false
	default:
	}
	s.listeners = append(s.listeners, listener)
	return true
}
func (s *devSession) accept(listener net.Listener, kind string) {
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		id := secret()
		stream := &devStream{conn: conn, ready: make(chan struct{})}
		s.mu.Lock()
		select {
		case <-s.done:
			s.mu.Unlock()
			conn.Close()
			return
		default:
		}
		if len(s.streams) >= 8 {
			s.mu.Unlock()
			conn.Close()
			continue
		}
		s.streams[id] = stream
		s.mu.Unlock()
		if s.send(map[string]string{"type": "open", "id": id, "kind": kind}) != nil {
			conn.Close()
			s.close()
			return
		}
		go func() {
			defer func() { conn.Close(); s.mu.Lock(); delete(s.streams, id); s.mu.Unlock() }()
			select {
			case <-s.done:
				return
			case <-time.After(10 * time.Second):
				return
			case <-stream.ready:
			}
			defer stream.ws.Close()
			errors := make(chan error, 2)
			go func() {
				buffer := make([]byte, 65536)
				for {
					n, err := conn.Read(buffer)
					if n > 0 {
						stream.ws.SetWriteDeadline(time.Now().Add(15 * time.Second))
						if e := stream.ws.WriteMessage(websocket.BinaryMessage, buffer[:n]); e != nil {
							errors <- e
							return
						}
					}
					if err != nil {
						errors <- err
						return
					}
				}
			}()
			go func() {
				stream.ws.SetReadLimit(65536)
				for {
					kind, data, err := stream.ws.ReadMessage()
					if err != nil {
						errors <- err
						return
					}
					if kind != websocket.BinaryMessage {
						errors <- io.ErrUnexpectedEOF
						return
					}
					conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
					if _, err = conn.Write(data); err != nil {
						errors <- err
						return
					}
				}
			}()
			select {
			case <-errors:
			case <-s.done:
			}
		}()
	}
}
func (h *DevHub) data(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	h.mu.Lock()
	s := h.active
	h.mu.Unlock()
	if s == nil {
		http.Error(w, "no active session", 409)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	stream := s.streams[id]
	if stream == nil || stream.ws != nil {
		http.Error(w, "unknown stream", 403)
		return
	}
	select {
	case <-s.done:
		http.Error(w, "closed", 409)
		return
	default:
	}
	ws, err := devUpgrade.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	stream.ws = ws
	close(stream.ready)
}
func (s *devSession) close() {
	s.once.Do(func() {
		close(s.done)
		s.cancel()
		s.control.Close()
		s.mu.Lock()
		for _, listener := range s.listeners {
			listener.Close()
		}
		for _, stream := range s.streams {
			stream.conn.Close()
			if stream.ws != nil {
				stream.ws.Close()
			}
		}
		s.mu.Unlock()
		if s.hub.runADB != nil {
			s.hub.runADB(context.Background(), "", "kill-server")
		}
		// No listener or established data stream survives the remote session.
		s.hub.mu.Lock()
		if s.hub.active == s {
			s.hub.active = nil
			s.hub.status = "联调已关闭，电脑不能继续通过此通道操作手机"
		}
		s.hub.mu.Unlock()
	})
}
func (h *DevHub) Close() {
	h.mu.Lock()
	s := h.active
	h.mu.Unlock()
	if s != nil {
		s.close()
	}
}
