package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	qrcode "github.com/skip2/go-qrcode"
	socks5 "github.com/things-go/go-socks5"
)

const computerIP = "10.144.77.1"

type Pairing struct {
	Version         int      `json:"version"`
	ComputerIP      string   `json:"computerIp"`
	NetworkName     string   `json:"networkName"`
	NetworkSecret   string   `json:"networkSecret"`
	ProxyPassword   string   `json:"proxyPassword"`
	Peers           []string `json:"peers"`
	ComputerDomains []string `json:"computerDomains"`
	DirectDomains   []string `json:"directDomains"`
	Fallback        string   `json:"fallback"`
}

func secret() string {
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
func newPairing() Pairing {
	return Pairing{1, computerIP, "wangchuan-" + secret()[:12], secret(), secret(), defaultDiscoveryPeers(), []string{"openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "anthropic.com", "claude.ai", "google.com", "googleapis.com", "gstatic.com", "youtube.com", "googlevideo.com", "ytimg.com", "github.com", "githubusercontent.com", "x.com", "twitter.com"}, []string{"qq.com", "weixin.qq.com", "taobao.com", "jd.com", "bilibili.com", "baidu.com"}, "DIRECT"}
}
func loadPairing(path string) (Pairing, error) {
	var p Pairing
	ciphertext, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		p = newPairing()
		plain, _ := json.Marshal(p)
		ciphertext, err = seal(plain)
		if err != nil {
			return p, err
		}
		return p, os.WriteFile(path, ciphertext, 0600)
	}
	if err != nil {
		return p, err
	}
	plain, err := unseal(ciphertext)
	if err != nil {
		return p, err
	}
	err = json.Unmarshal(plain, &p)
	if err == nil {
		p.Peers = repairDiscoveryPeers(p.Peers)
	}
	return p, err
}

type App struct {
	pair             Pairing
	directory        string
	token            string
	mu               sync.Mutex
	status           string
	pairingUntil     time.Time
	children         map[string]*exec.Cmd
	childWait        sync.WaitGroup
	stopping         bool
	cancel           context.CancelFunc
	development      *DevHub
	remoteSetupMu    sync.Mutex
	remoteConfigured atomic.Bool
	network          NetworkStatus
	phoneLastSeen    atomic.Int64
	metrics          ProxyMetrics
	streamPrep       streamingPreparation
}

func localRequest(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil || !net.ParseIP(host).IsLoopback() {
		return false
	}
	return r.Host == "127.0.0.1:17881" && (r.Header.Get("Origin") == "" || r.Header.Get("Origin") == "http://127.0.0.1:17881")
}
func (a *App) serveHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self'; frame-ancestors 'none'")
	if !localRequest(r) {
		http.Error(w, "forbidden", 403)
		return
	}
	switch r.URL.Path {
	case "/manager-token":
		json.NewEncoder(w).Encode(map[string]string{"token": a.token})
	case "/management":
		a.mu.Lock()
		state := a.status
		network := a.network
		a.mu.Unlock()
		dev := map[string]any{"active": false, "status": "未开启真机联调"}
		if a.development != nil {
			dev = a.development.state()
		}
		json.NewEncoder(w).Encode(map[string]any{"version": "0.3.17", "backendPid": os.Getpid(), "status": state, "networkReady": tcpReady(computerIP + ":10808"), "network": network, "phoneLastSeen": a.phoneLastSeen.Load(), "traffic": a.metrics.snapshot(), "streaming": a.streamPrep.snapshot(), "remoteReady": tcpReady("127.0.0.1:21118"), "remoteConfigured": a.remoteConfigured.Load(), "development": dev})
	case "/traffic-history":
		a.historyQuery(w, r)
	case "/development-status":
		if a.development == nil {
			http.Error(w, "unavailable", 503)
			return
		}
		json.NewEncoder(w).Encode(a.development.state())
	case "/":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, strings.ReplaceAll(page, "CSRF_TOKEN", a.token))
	case "/status":
		a.mu.Lock()
		state := a.status
		a.mu.Unlock()
		json.NewEncoder(w).Encode(map[string]string{"status": state, "computerIp": computerIP})
	case "/pair.png":
		a.mu.Lock()
		allowed := time.Now().Before(a.pairingUntil)
		a.mu.Unlock()
		if !allowed {
			http.Error(w, "pairing closed", 403)
			return
		}
		data, _ := json.Marshal(a.pair)
		png, err := qrcode.Encode(string(data), qrcode.Medium, 640)
		if err != nil {
			http.Error(w, "QR generation failed", 500)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Write(png)
	case "/action":
		if r.Method != "POST" || subtle.ConstantTimeCompare([]byte(r.Header.Get("X-CSRF")), []byte(a.token)) != 1 {
			http.Error(w, "forbidden", 403)
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 1024))
		if err != nil {
			http.Error(w, "invalid request", 400)
			return
		}
		switch string(body) {
		case "prepare", "rustdesk":
			if err := a.prepareRemote(); err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
		case "stop-development":
			if a.development != nil {
				a.development.Close()
			}
		case "quit":
			if a.cancel != nil {
				go a.cancel()
			}
		case "pair":
			a.mu.Lock()
			a.pairingUntil = time.Now().Add(2 * time.Minute)
			a.mu.Unlock()
		case "sunshine":
			a.beginStreamingPreparation()
			w.WriteHeader(http.StatusAccepted)
			return
		default:
			http.Error(w, "unknown action", 400)
			return
		}
		w.WriteHeader(204)
	default:
		http.NotFound(w, r)
	}
}
func (a *App) startChild(name string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.stopping {
		return errors.New("电脑端正在退出")
	}
	if _, ok := a.children[name]; ok {
		return nil
	}
	binary := filepath.Join(a.directory, "runtime", name, name+".exe")
	if _, err := os.Stat(binary); err != nil {
		return errors.New("component missing")
	}
	cmd := exec.Command(binary)
	if name == "rustdesk" {
		cmd = exec.Command(binary, "--server")
	}
	cmd.Dir = filepath.Dir(binary)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err := cmd.Start(); err != nil {
		return err
	}
	a.children[name] = cmd
	a.childWait.Add(1)
	go func() {
		defer a.childWait.Done()
		cmd.Wait()
		a.mu.Lock()
		if a.children[name] == cmd {
			delete(a.children, name)
		}
		a.mu.Unlock()
	}()
	return nil
}
func (a *App) overlay(ctx context.Context) {
	delay := time.Second
	for ctx.Err() == nil {
		binary := filepath.Join(a.directory, "runtime", "easytier", "easytier-core.exe")
		args := []string{"-i", computerIP, "--no-listener", "--p2p-only", "true", "--rpc-portal", "127.0.0.1:15888",
			"--stun-servers", "stun.miwifi.com:3478", "stun.chat.bilibili.com:3478"}
		for _, peer := range a.pair.Peers {
			args = append(args, "-p", peer)
		}
		cmd := exec.CommandContext(ctx, binary, args...)
		cmd.Dir = filepath.Dir(binary)
		cmd.Env = append(os.Environ(), "ET_NETWORK_NAME="+a.pair.NetworkName, "ET_NETWORK_SECRET="+a.pair.NetworkSecret)
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		started := time.Now()
		err := cmd.Run()
		if ctx.Err() != nil {
			return
		}
		a.mu.Lock()
		if err != nil {
			a.status = "组网连接未就绪；请检查管理员权限、组件和网络"
		}
		a.mu.Unlock()
		if time.Since(started) > time.Minute {
			delay = time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		if delay < time.Minute {
			delay *= 2
		}
	}
}
func (a *App) proxy(ctx context.Context) {
	server := socks5.NewServer(socks5.WithCredential(socks5.StaticCredentials{"phone": a.pair.ProxyPassword}), socks5.WithBindIP(net.ParseIP(computerIP)), socks5.WithDial(a.metrics.dial))
	for ctx.Err() == nil {
		listener, err := net.Listen("tcp", net.JoinHostPort(computerIP, "10808"))
		if err != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}
		a.mu.Lock()
		a.status = "电脑代理入口已就绪；手机连接仍需验收"
		a.mu.Unlock()
		go func() { <-ctx.Done(); listener.Close() }()
		server.Serve(listener)
		if ctx.Err() == nil {
			time.Sleep(time.Second)
		}
	}
}
func main() {
	managerPID := flag.Uint("manager-pid", 0, "Owning manager process, if started from the GUI")
	flag.Parse()
	executable, err := os.Executable()
	if err != nil {
		return
	}
	state := filepath.Join(os.Getenv("LOCALAPPDATA"), "WangChuanLink")
	if err = privateDirectory(state); err != nil {
		fmt.Fprintln(os.Stderr, "无法创建受保护的配置目录")
		return
	}
	pair, err := loadPairing(filepath.Join(state, "pairing.dpapi"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "无法读取配对配置")
		return
	}
	a := &App{pair: pair, directory: filepath.Dir(executable), token: secret(), status: "正在准备电脑连接", children: map[string]*exec.Cmd{}}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()
	a.cancel = cancel
	a.metrics.history, err = openHistory(filepath.Join(state, "traffic-history.json"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "无法读取历史流量文件，原文件已保留")
		return
	}
	if *managerPID != 0 {
		if err := watchManager(ctx, cancel, uint32(*managerPID), a.directory); err != nil {
			fmt.Fprintln(os.Stderr, "管理窗口已退出或身份无法确认，未启动组件")
			return
		}
	}
	a.development = newDevHub(pair.ProxyPassword)
	a.development.runADB = adbRunner(a.directory, state)
	server := &http.Server{Addr: "127.0.0.1:17881", Handler: http.HandlerFunc(a.serveHTTP), ReadHeaderTimeout: 5 * time.Second}
	listener, err := net.Listen("tcp", server.Addr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "电脑端已运行或端口被占用")
		return
	}
	if err := guardProcessTree(); err != nil {
		listener.Close()
		fmt.Fprintln(os.Stderr, "无法启用进程退出保护，未启动组件:", err)
		return
	}
	overlayDone := make(chan struct{})
	go func() { defer close(overlayDone); a.overlay(ctx) }()
	go a.monitorNetwork(ctx)
	go a.proxy(ctx)
	go a.rules(ctx)
	historyDone := make(chan struct{})
	go func() { defer close(historyDone); a.metrics.history.run(ctx, &a.metrics) }()
	go func() {
		<-ctx.Done()
		server.Close()
	}()
	fmt.Println("望川电脑端：http://127.0.0.1:17881")
	server.Serve(listener)
	cancel()
	cleanupDone := make(chan struct{})
	go func() {
		defer close(cleanupDone)
		a.stopChildren()
		a.development.Close()
		<-overlayDone
	}()
	// Give normal shutdown time to reap children and revoke the development
	// session. The lifetime job also kills descendants if cleanup stalls/crashes.
	select {
	case <-cleanupDone:
	case <-time.After(25 * time.Second):
	}
	<-historyDone
	a.metrics.checkpoint()
	a.metrics.history.save()
}

const page = `<!doctype html><meta charset="utf-8"><title>望川连接 · 电脑端</title>
<style>body{font:17px system-ui;max-width:760px;margin:60px auto;background:#f4f7fb;color:#162333}button{padding:14px 24px;margin:8px;border:0;border-radius:12px;background:#2159bb;color:white;font-size:16px}section{background:white;padding:28px;border-radius:20px;margin:20px 0}img{max-width:100%}</style>
<h1>望川连接</h1><p>手机按规则借用电脑网络；远控与高帧率模式按需开启。</p>
<section><h2>连接状态</h2><p id="status">正在检查…</p><p>电脑虚拟地址：10.144.77.1</p></section>
<section><h2>配对手机</h2><p>只向自己的手机展示二维码。二维码含配对凭证，窗口两分钟后关闭。</p><button onclick="act('pair')">显示配对二维码</button><div id="qr"></div></section>
<section><h2>远控</h2><button onclick="act('rustdesk')">启动日常远控</button><button onclick="act('sunshine')">启动高帧率服务</button><p>首次仍需在 RustDesk 中启用直接 IP 访问并设置访问验证，在 Sunshine 中完成管理员及 PIN 配对。未完成这些步骤前不能认为远控已经可用。</p></section>
<section><h2>AI 真机联调</h2><p id="development">未开启</p><p>只能从手机已连接电脑的远控会话中授权。开启后使用运行包里的 adb-phone.cmd；退出远控或锁屏会关闭通道。</p><button onclick="act('stop-development');poll()">立即关闭手机联调</button></section>
<p>手机内置 42 个规则集；可在手机中调整分组去向，并通过加密组网从本机同步规则文件。规则来源与黑盒子对应，分组去向可针对手机独立设置。</p><button onclick="act('quit')">退出电脑端</button>
<script>async function act(name){let r=await fetch('/action',{method:'POST',headers:{'X-CSRF':'CSRF_TOKEN'},body:name});if(!r.ok){alert(await r.text());return}if(name==='pair'){document.querySelector('#qr').innerHTML='<img src="/pair.png">';setTimeout(()=>document.querySelector('#qr').replaceChildren(),120000)}}async function poll(){try{let r=await fetch('/status');document.querySelector('#status').textContent=(await r.json()).status;let d=await fetch('/development-status');document.querySelector('#development').textContent=(await d.json()).status}catch{document.querySelector('#status').textContent='电脑端已退出';document.querySelector('#development').textContent='联调不可用'}}poll();setInterval(()=>{if(!document.hidden)poll()},5000)</script>`
