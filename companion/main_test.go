package main

import (
	"bytes"
	"context"
	socks5 "github.com/things-go/go-socks5"
	"io"
	"net"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestVaultRoundTrip(t *testing.T) {
	input := []byte("fixed-non-secret-test-fixture")
	ciphertext, err := seal(input)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(ciphertext, input) {
		t.Fatal("plaintext in DPAPI ciphertext")
	}
	plain, err := unseal(ciphertext)
	if err != nil || !bytes.Equal(plain, input) {
		t.Fatal("vault round trip failed")
	}
	ciphertext[len(ciphertext)-1] ^= 0xff
	if _, err = unseal(ciphertext); err == nil {
		t.Fatal("tampered vault accepted")
	}
}

func TestRulesRequireAuthAndRejectPathTraversal(t *testing.T) {
	dir := t.TempDir()
	os.Mkdir(filepath.Join(dir, "rules"), 0700)
	os.WriteFile(filepath.Join(dir, "rules", "manifest.json"), []byte(`{"providers":[]}`), 0600)
	app := &App{directory: dir, pair: Pairing{ProxyPassword: "fixture"}}
	for _, tc := range []struct {
		path, token string
		status      int
	}{
		{"/manifest.json", "", 403},
		{"/manifest.json", "Bearer wrong", 403},
		{"/../pairing.dpapi", "Bearer fixture", 404},
		{"/manifest.json", "Bearer fixture", 200},
	} {
		req := httptest.NewRequest("GET", "http://10.144.77.1:10809"+tc.path, nil)
		req.Header.Set("Authorization", tc.token)
		response := httptest.NewRecorder()
		app.serveRules(response, req)
		if response.Code != tc.status {
			t.Fatalf("%s: got %d want %d", tc.path, response.Code, tc.status)
		}
	}
}

func TestAuthenticatedProxyCarriesRealUDP(t *testing.T) {
	echo, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		b := make([]byte, 512)
		n, addr, e := echo.ReadFromUDP(b)
		if e == nil {
			echo.WriteToUDP(b[:n], addr)
		}
	}()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	server := socks5.NewServer(socks5.WithCredential(socks5.StaticCredentials{"test": "fixture"}), socks5.WithBindIP(net.ParseIP("127.0.0.1")))
	go server.Serve(listener)
	control, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	control.SetDeadline(time.Now().Add(3 * time.Second))
	control.Write([]byte{5, 1, 2})
	reply := make([]byte, 2)
	if _, err := io.ReadFull(control, reply); err != nil || reply[1] != 2 {
		t.Fatal("auth negotiation failed")
	}
	control.Write(append([]byte{1, 4, 't', 'e', 's', 't', 7}, []byte("fixture")...))
	if _, err := io.ReadFull(control, reply); err != nil || reply[1] != 0 {
		t.Fatal("authentication failed")
	}
	control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	bound := make([]byte, 10)
	if _, err := io.ReadFull(control, bound); err != nil || bound[1] != 0 || bound[3] != 1 {
		t.Fatal("UDP association failed")
	}
	relay := &net.UDPAddr{IP: net.IP(bound[4:8]), Port: int(bound[8])<<8 | int(bound[9])}
	client, err := net.DialUDP("udp4", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	client.SetDeadline(time.Now().Add(3 * time.Second))
	port := echo.LocalAddr().(*net.UDPAddr).Port
	payload := append([]byte{0, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)}, []byte("udp-route-proof")...)
	if _, err := client.Write(payload); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, 512)
	n, err := client.Read(data)
	if err != nil || n < 10 || string(data[10:n]) != "udp-route-proof" {
		t.Fatal("UDP echo did not pass through authenticated proxy", err)
	}
}
func TestAdminRejectsRebindingAndCrossOrigin(t *testing.T) {
	for _, tc := range []struct{ host, origin string }{{"evil.invalid:17881", ""}, {"127.0.0.1:17881", "https://evil.invalid"}} {
		app := &App{token: "unit-test-token"}
		request := httptest.NewRequest("POST", "http://127.0.0.1:17881/action", bytes.NewBufferString("pair"))
		request.RemoteAddr = "127.0.0.1:50000"
		request.Host = tc.host
		request.Header.Set("Origin", tc.origin)
		request.Header.Set("X-CSRF", app.token)
		response := httptest.NewRecorder()
		app.serveHTTP(response, request)
		if response.Code != 403 {
			t.Fatalf("unexpected status %d", response.Code)
		}
	}
}
func TestPairingWindowRequiresExplicitAction(t *testing.T) {
	app := &App{pair: newPairing(), token: "unit-test-token"}
	request := httptest.NewRequest("GET", "http://127.0.0.1:17881/pair.png", nil)
	request.RemoteAddr = "127.0.0.1:50000"
	response := httptest.NewRecorder()
	app.serveHTTP(response, request)
	if response.Code != 403 {
		t.Fatal("pairing exposed outside explicit pairing window")
	}
}
func TestManagerAPIRequiresLocalHostAndActionToken(t *testing.T) {
	app := &App{token: "manager-test-token"}
	for _, endpoint := range []string{"/manager-token", "/management"} {
		for _, tc := range []struct {
			remote, origin string
			status         int
		}{
			{"127.0.0.1:50000", "", 200},
			{"192.0.2.9:50000", "", 403},
			{"127.0.0.1:50000", "https://evil.invalid", 403},
		} {
			r := httptest.NewRequest("GET", "http://127.0.0.1:17881"+endpoint, nil)
			r.RemoteAddr = tc.remote
			r.Header.Set("Origin", tc.origin)
			w := httptest.NewRecorder()
			app.serveHTTP(w, r)
			if w.Code != tc.status {
				t.Fatalf("%s returned %d, want %d", endpoint, w.Code, tc.status)
			}
		}
	}
	// A same-origin request without the token must not reach prepareRemote or change settings.
	r := httptest.NewRequest("POST", "http://127.0.0.1:17881/action", bytes.NewBufferString("prepare"))
	r.RemoteAddr = "127.0.0.1:50000"
	w := httptest.NewRecorder()
	app.serveHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("remote preparation accepted without CSRF token")
	}
}
func TestProxyRequiresAuthentication(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	server := socks5.NewServer(socks5.WithCredential(socks5.StaticCredentials{"test": "test-fixture-only"}))
	go server.Serve(listener)
	connection, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(3 * time.Second))
	connection.Write([]byte{5, 1, 0})
	reply := make([]byte, 2)
	io.ReadFull(connection, reply)
	if reply[1] != 255 {
		t.Fatalf("no-auth proxy access accepted: %v", reply)
	}
}
func TestAuthenticatedProxyCarriesRealTCP(t *testing.T) {
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		c, e := echo.Accept()
		if e == nil {
			defer c.Close()
			io.Copy(c, c)
		}
	}()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	server := socks5.NewServer(socks5.WithCredential(socks5.StaticCredentials{"test": "fixture"}))
	go server.Serve(listener)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	connection, err := (&net.Dialer{}).DialContext(ctx, "tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(3 * time.Second))
	connection.Write([]byte{5, 1, 2})
	reply := make([]byte, 2)
	io.ReadFull(connection, reply)
	if reply[1] != 2 {
		t.Fatal("password method unavailable")
	}
	connection.Write(append([]byte{1, 4, 't', 'e', 's', 't', 7}, []byte("fixture")...))
	io.ReadFull(connection, reply)
	if reply[1] != 0 {
		t.Fatal("valid credentials rejected")
	}
	port := echo.Addr().(*net.TCPAddr).Port
	connection.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
	bound := make([]byte, 10)
	if _, err = io.ReadFull(connection, bound); err != nil {
		t.Fatal(err)
	}
	if bound[1] != 0 {
		t.Fatal("proxy connect failed")
	}
	connection.Write([]byte("route-proof"))
	payload := make([]byte, 11)
	if _, err = io.ReadFull(connection, payload); err != nil || string(payload) != "route-proof" {
		t.Fatal("TCP payload did not pass through proxy")
	}
}
