package main

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestHistoryDurableCountersAndScope(t *testing.T) {
	p := filepath.Join(t.TempDir(), "history.json")
	h, e := openHistory(p)
	if e != nil {
		t.Fatal(e)
	}
	h.addPC("example.com:443", 5, 9, 1, 0)
	h.addPC("example.com:443", 3, 4, 0, 0)
	if e = h.save(); e != nil {
		t.Fatal(e)
	}
	h, e = openHistory(p)
	if e != nil {
		t.Fatal(e)
	}
	r := h.query("0000", "9999", "", "example", "电脑出口", "pc", "host")
	if r["up"] != int64(8) || r["down"] != int64(13) || r["count"] != int64(1) {
		t.Fatal(r)
	}
	if h.query("0000", "9999", "", "", "", "phone", "")["up"] != int64(0) {
		t.Fatal("mixed sources")
	}
	if h.query("9998", "9999", "", "", "", "pc", "")["count"] != int64(0) {
		t.Fatal("date filter")
	}
	if h.query("0000", "9999", "not-the-app", "", "", "pc", "")["count"] != int64(0) {
		t.Fatal("app filter")
	}
}
func TestPhoneHistoryAuthenticationRetryAndValidation(t *testing.T) {
	h, _ := openHistory(filepath.Join(t.TempDir(), "history.json"))
	a := &App{}
	a.pair.ProxyPassword = "test-only"
	a.metrics.history = h
	row := HistoryRow{ID: historyID("fixture"), Day: time.Now().Format("2006-01-02"), App: "Test browser", Package: "test.browser", Host: "example.com", Route: "电脑出口", Protocol: "TCP", Up: 10, Down: 50, Count: 1, First: time.Now().UnixMilli(), Last: time.Now().UnixMilli(), Revision: 1}
	send := func(auth, peer string, rows []HistoryRow) int {
		b, _ := json.Marshal(rows)
		req := httptest.NewRequest("POST", "/traffic/history", bytes.NewReader(b))
		req.Header.Set("Authorization", auth)
		req.RemoteAddr = peer
		w := httptest.NewRecorder()
		a.importHistory(w, req)
		return w.Code
	}
	for _, bad := range []struct{ auth, peer string }{{"", "10.144.77.2:123"}, {"Bearer test-only", "10.144.77.3:123"}} {
		if send(bad.auth, bad.peer, []HistoryRow{row}) != 403 {
			t.Fatal("unauthorized import")
		}
	}
	for i := 0; i < 2; i++ {
		if send("Bearer test-only", "10.144.77.2:123", []HistoryRow{row}) != 204 {
			t.Fatal("import")
		}
	}
	row.Revision = 2
	row.Down = 100
	send("Bearer test-only", "10.144.77.2:123", []HistoryRow{row})
	row.Revision = 1
	row.Down = 50
	send("Bearer test-only", "10.144.77.2:123", []HistoryRow{row})
	h2, e := openHistory(h.path)
	if e != nil {
		t.Fatal(e)
	}
	q := h2.query("0000", "9999", "browser", "example", "电脑出口", "phone", "app")
	if q["down"] != int64(100) || q["count"] != int64(1) {
		t.Fatal("retry or out-of-order inflated counters", q)
	}
	row.Host = "example.com/path?password=secret"
	if send("Bearer test-only", "10.144.77.2:123", []HistoryRow{row}) != 400 {
		t.Fatal("accepted URL path/query")
	}
}
func TestHistoryCorruptFileNotOverwritten(t *testing.T) {
	p := filepath.Join(t.TempDir(), "history.json")
	os.WriteFile(p, []byte("broken"), 0600)
	if _, e := openHistory(p); e == nil {
		t.Fatal("invalid history accepted")
	}
	b, _ := os.ReadFile(p)
	if string(b) != "broken" {
		t.Fatal("destroyed history")
	}
}
func TestPCCheckpointDoesNotCountClosedFlowTwice(t *testing.T) {
	h, _ := openHistory(filepath.Join(t.TempDir(), "history.json"))
	m := &ProxyMetrics{history: h}
	f := &proxyFlow{Target: "example.com:443"}
	f.upload.Store(10)
	m.recordFlow(f)
	m.recordFlow(f)
	f.download.Store(20)
	m.recordFlow(f)
	q := h.query("0000", "9999", "", "", "", "pc", "")
	if q["up"] != int64(10) || q["down"] != int64(20) || q["count"] != int64(1) {
		t.Fatal(q)
	}
}
