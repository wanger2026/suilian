package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"
)

var streamSetupMu sync.Mutex
var streamPIN = regexp.MustCompile(`^[0-9]{4}$`)

// Cold encoder discovery can take tens of seconds. Keep request handling fast
// and expose a pollable preparation state instead of timing out a healthy boot.
type streamingPreparation struct {
	mu             sync.Mutex
	running, ready bool
	failure        string
}

func (p *streamingPreparation) snapshot() map[string]any {
	p.mu.Lock()
	defer p.mu.Unlock()
	return map[string]any{"running": p.running, "ready": p.ready, "error": p.failure}
}
func (p *streamingPreparation) start(force bool, work func() error) {
	p.mu.Lock()
	if p.running || (p.ready && !force) {
		p.mu.Unlock()
		return
	}
	p.running = true
	p.ready = false
	p.failure = ""
	p.mu.Unlock()
	go func() {
		err := work()
		p.mu.Lock()
		defer p.mu.Unlock()
		p.running = false
		p.ready = err == nil
		if err != nil {
			p.failure = err.Error()
		}
	}()
}
func (a *App) beginStreamingPreparation() {
	a.mu.Lock()
	alive := a.children["sunshine"] != nil
	a.mu.Unlock()
	a.streamPrep.start(!alive, func() error { return a.prepareStreaming(context.Background()) })
}

type sunshineClient struct {
	client         *http.Client
	password, csrf string
	baseURL        string
}

func (a *App) sunshineClient() (*sunshineClient, error) {
	certificate, err := os.ReadFile(filepath.Join(a.directory, "runtime", "sunshine", "config", "credentials", "cacert.pem"))
	if err != nil {
		return nil, errors.New("串流证书尚未准备好")
	}
	block, _ := pem.Decode(certificate)
	if block == nil {
		return nil, errors.New("串流证书无法读取")
	}
	expected := sha256.Sum256(block.Bytes)
	transport := &http.Transport{DisableKeepAlives: true, TLSClientConfig: &tls.Config{
		MinVersion: tls.VersionTLS12,
		// Sunshine's private certificate has no valid hostname. Verify the exact
		// bundled instance certificate instead; never accept an arbitrary peer.
		InsecureSkipVerify: true,
		VerifyConnection: func(state tls.ConnectionState) error {
			if len(state.PeerCertificates) == 0 {
				return errors.New("missing certificate")
			}
			actual := sha256.Sum256(state.PeerCertificates[0].Raw)
			if subtle.ConstantTimeCompare(actual[:], expected[:]) != 1 {
				return errors.New("unexpected local certificate")
			}
			return nil
		},
	}}
	mac := hmac.New(sha256.New, []byte(a.pair.ProxyPassword))
	mac.Write([]byte("suilian-sunshine-v1"))
	return &sunshineClient{client: &http.Client{Transport: transport, Timeout: 3 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, password: hex.EncodeToString(mac.Sum(nil)), baseURL: "https://127.0.0.1:47990"}, nil
}

func (s *sunshineClient) request(ctx context.Context, method, path string, body any) (int, []byte, error) {
	var data []byte
	if body != nil {
		var err error
		data, err = json.Marshal(body)
		if err != nil {
			return 0, nil, err
		}
	}
	req, err := http.NewRequestWithContext(ctx, method, s.baseURL+path, bytes.NewReader(data))
	if err != nil {
		return 0, nil, err
	}
	req.SetBasicAuth("suilian", s.password)
	req.Header.Set("Origin", s.baseURL)
	req.Header.Set("Content-Type", "application/json")
	if s.csrf != "" {
		req.Header.Set("X-CSRF-Token", s.csrf)
	}
	res, err := s.client.Do(req)
	if err != nil {
		return 0, nil, errors.New("串流管理接口暂时无法连接")
	}
	defer res.Body.Close()
	out, err := io.ReadAll(io.LimitReader(res.Body, 512*1024))
	return res.StatusCode, out, err
}

func (a *App) prepareStreaming(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	streamSetupMu.Lock()
	defer streamSetupMu.Unlock()
	if err := a.startChild("sunshine"); err != nil {
		return errors.New("串流组件启动失败")
	}
	var client *sunshineClient
	var err error
	var code int
	for {
		client, err = a.sunshineClient()
		if err == nil {
			code, _, err = client.request(ctx, "GET", "/api/config", nil)
			if err == nil && code < 500 {
				break
			}
			if err == nil {
				err = errors.New("串流管理服务仍在初始化，请稍后重试")
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(200 * time.Millisecond):
		}
	}
	if err != nil {
		return err
	}
	if code == 301 || code == 302 || code == 307 {
		code, raw, e := client.request(ctx, "POST", "/api/password", map[string]string{"currentUsername": "", "currentPassword": "", "newUsername": "suilian", "newPassword": client.password, "confirmNewPassword": client.password})
		if e != nil {
			return e
		}
		var result struct {
			Status bool `json:"status"`
		}
		json.Unmarshal(raw, &result)
		if code != 200 || !result.Status {
			return errors.New("首次串流账户初始化未完成")
		}
	} else if code != 200 {
		return errors.New("Sunshine 已有其他管理账户，请先处理账户归属")
	}
	return nil
}

func (a *App) pairStreaming(ctx context.Context, pin string) error {
	if !streamPIN.MatchString(pin) {
		return errors.New("配对码格式无效")
	}
	client, err := a.sunshineClient()
	if err != nil {
		return err
	}
	code, raw, err := client.request(ctx, "GET", "/api/csrf-token", nil)
	if err != nil || code != 200 {
		return errors.New("串流管理鉴权未完成")
	}
	var token struct {
		Token string `json:"csrf_token"`
	}
	if json.Unmarshal(raw, &token) != nil || token.Token == "" {
		return errors.New("串流管理验证失败")
	}
	client.csrf = token.Token
	for n := 0; n < 10; n++ {
		code, raw, err = client.request(ctx, "GET", "/api/pin", nil)
		if err != nil {
			return err
		}
		var pending struct {
			Pairings []struct {
				ID      any    `json:"id"`
				Address string `json:"address"`
			} `json:"pairings"`
		}
		if code != 200 || json.Unmarshal(raw, &pending) != nil {
			return errors.New("无法读取待配对请求")
		}
		var target any
		count := 0
		for _, p := range pending.Pairings {
			if p.Address == "10.144.77.2" || p.Address == "::ffff:10.144.77.2" {
				target = p.ID
				count++
			}
		}
		if count > 1 {
			return errors.New("检测到多条配对请求，请重新进入高帧率模式")
		}
		if count == 1 {
			code, raw, err = client.request(ctx, "POST", "/api/pin", map[string]any{"pairing_id": target, "pin": pin, "name": "随连手机"})
			var result struct {
				Status bool `json:"status"`
			}
			json.Unmarshal(raw, &result)
			if err != nil || code != 200 || !result.Status {
				return errors.New("配对确认未完成")
			}
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(300 * time.Millisecond):
		}
	}
	return errors.New("未收到这部手机的配对请求，请重试")
}

func (a *App) serveStreaming(w http.ResponseWriter, r *http.Request) {
	source, _, _ := net.SplitHostPort(r.RemoteAddr)
	if r.Method != "POST" || source != "10.144.77.2" || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+a.pair.ProxyPassword)) != 1 {
		http.Error(w, "forbidden", 403)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 7*time.Second)
	defer cancel()
	var err error
	switch r.URL.Path {
	case "/streaming/prepare":
		a.beginStreamingPreparation()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(a.streamPrep.snapshot())
		return
	case "/streaming/status":
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(a.streamPrep.snapshot())
		return
	case "/streaming/pair":
		var input struct {
			PIN string `json:"pin"`
		}
		if json.NewDecoder(io.LimitReader(r.Body, 128)).Decode(&input) != nil {
			http.Error(w, "invalid", 400)
			return
		}
		err = a.pairStreaming(ctx, input.PIN)
	default:
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		w.WriteHeader(503)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	json.NewEncoder(w).Encode(map[string]bool{"ready": true})
}
