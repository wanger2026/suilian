package main

import (
	"context"
	"crypto/subtle"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var ruleFile = regexp.MustCompile(`^(manifest\.json|[0-9]{2}\.(mrs|text))$`)

func (a *App) serveRules(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/traffic/history" {
		a.importHistory(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/streaming/") {
		a.serveStreaming(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/development/") {
		if a.development == nil {
			http.Error(w, "unavailable", 503)
			return
		}
		a.development.ServeHTTP(w, r)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.Method != http.MethodGet || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+a.pair.ProxyPassword)) != 1 {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	name := r.URL.Path[1:]
	if !ruleFile.MatchString(name) {
		http.NotFound(w, r)
		return
	}
	if source, _, err := net.SplitHostPort(r.RemoteAddr); err == nil && source == "10.144.77.2" {
		a.phoneLastSeen.Store(time.Now().Unix())
	}
	file, err := os.Open(filepath.Join(a.directory, "rules", name))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer file.Close()
	stat, err := file.Stat()
	if err != nil || stat.Size() > 16*1024*1024 {
		http.Error(w, "invalid rule", 500)
		return
	}
	http.ServeContent(w, r, name, stat.ModTime(), file)
}

func (a *App) rules(ctx context.Context) {
	for ctx.Err() == nil {
		listener, err := net.Listen("tcp", net.JoinHostPort(computerIP, "10809"))
		if err != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(5 * time.Second):
				continue
			}
		}
		server := &http.Server{Handler: http.HandlerFunc(a.serveRules), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 20 * time.Second}
		go func() { <-ctx.Done(); server.Close() }()
		server.Serve(listener)
	}
}
