package main

import (
	"context"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestStreamingPreparationDoesNotBlockOrStartDuplicateEncoderProbes(t *testing.T) {
	var preparation streamingPreparation
	var calls atomic.Int32
	release := make(chan struct{})
	work := func() error { calls.Add(1); <-release; return nil }
	preparation.start(false, work)
	preparation.start(false, work)
	if preparation.snapshot()["running"] != true {
		t.Fatal("must report ongoing cold boot")
	}
	close(release)
	deadline := time.Now().Add(time.Second)
	for preparation.snapshot()["running"] == true && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if preparation.snapshot()["ready"] != true || calls.Load() != 1 {
		t.Fatal("cold boot was duplicated or not reported ready")
	}
	preparation.start(false, work)
	if calls.Load() != 1 {
		t.Fatal("already prepared service should not restart")
	}
}

func TestSunshineManagementPinsExactLocalCertificate(t *testing.T) {
	app := &App{directory: t.TempDir(), pair: Pairing{ProxyPassword: "fixture-only"}}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		username, password, ok := r.BasicAuth()
		if !ok || username != "suilian" || len(password) != 64 || r.Header.Get("X-CSRF-Token") != "fixture-csrf" {
			w.WriteHeader(403)
			return
		}
		w.WriteHeader(200)
	}))
	defer server.Close()
	certPath := filepath.Join(app.directory, "runtime", "sunshine", "config", "credentials", "cacert.pem")
	if err := os.MkdirAll(filepath.Dir(certPath), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw}), 0600); err != nil {
		t.Fatal(err)
	}
	client, err := app.sunshineClient()
	if err != nil {
		t.Fatal(err)
	}
	client.baseURL = server.URL
	client.csrf = "fixture-csrf"
	if code, _, err := client.request(context.Background(), "GET", "/api/config", nil); err != nil || code != 200 {
		t.Fatalf("trusted certificate failed: %d %v", code, err)
	}
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: []byte("different-local-instance")}), 0600); err != nil {
		t.Fatal(err)
	}
	untrusted, err := app.sunshineClient()
	if err != nil {
		t.Fatal(err)
	}
	untrusted.baseURL = server.URL
	if _, _, err := untrusted.request(context.Background(), "GET", "/api/config", nil); err == nil {
		t.Fatal("mismatched certificate must not receive credentials")
	}
}

func TestStreamingControlRequiresPairedPhoneAndToken(t *testing.T) {
	app := &App{pair: Pairing{ProxyPassword: "synthetic-test-credential"}}
	for _, c := range []struct{ method, remote, token string }{{"POST", "127.0.0.1:3000", "Bearer synthetic-test-credential"}, {"POST", "10.144.77.3:3000", "Bearer synthetic-test-credential"}, {"POST", "10.144.77.2:3000", "wrong"}, {"GET", "10.144.77.2:3000", "Bearer synthetic-test-credential"}} {
		req := httptest.NewRequest(c.method, "http://10.144.77.1:10809/streaming/pair", strings.NewReader(`{"pin":"1234"}`))
		req.RemoteAddr = c.remote
		req.Header.Set("Authorization", c.token)
		out := httptest.NewRecorder()
		app.serveRules(out, req)
		if out.Code != 403 {
			t.Fatalf("unexpected control access: %d", out.Code)
		}
	}
}
