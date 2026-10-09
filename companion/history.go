package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// Daily counters are authoritative per source. Phone snapshots must never be
// added to PC payload counters: they observe different parts of the same flow.
type HistoryRow struct {
	ID       string `json:"id"`
	Source   string `json:"source"`
	Day      string `json:"day"`
	App      string `json:"app"`
	Package  string `json:"package"`
	Host     string `json:"host"`
	Route    string `json:"route"`
	Protocol string `json:"protocol"`
	Up       int64  `json:"up"`
	Down     int64  `json:"down"`
	Count    int64  `json:"count"`
	Failed   int64  `json:"failed"`
	First    int64  `json:"first"`
	Last     int64  `json:"last"`
	Revision int64  `json:"revision"`
}
type TrafficHistory struct {
	mu      sync.Mutex
	saveMu  sync.Mutex
	path    string
	rows    map[string]HistoryRow
	problem string
}

func openHistory(path string) (*TrafficHistory, error) {
	h := &TrafficHistory{path: path, rows: map[string]HistoryRow{}}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return h, nil
	}
	if err != nil {
		return nil, err
	}
	if err = json.Unmarshal(b, &h.rows); err != nil {
		return nil, err
	}
	if h.rows == nil {
		h.rows = map[string]HistoryRow{}
	}
	return h, nil
}
func (h *TrafficHistory) save() error {
	h.saveMu.Lock()
	defer h.saveMu.Unlock()
	h.mu.Lock()
	cutoff := time.Now().AddDate(0, 0, -365).Format("2006-01-02")
	for k, r := range h.rows {
		if r.Day < cutoff {
			delete(h.rows, k)
		}
	}
	b, err := json.Marshal(h.rows)
	h.mu.Unlock()
	if err == nil {
		err = os.WriteFile(h.path+".tmp", b, 0600)
	}
	if err == nil {
		err = os.Rename(h.path+".tmp", h.path)
	}
	h.mu.Lock()
	if err != nil {
		h.problem = "历史写入失败，当前数据尚未可靠保存"
	} else {
		h.problem = ""
	}
	h.mu.Unlock()
	return err
}
func (h *TrafficHistory) run(ctx context.Context, m *ProxyMetrics) {
	t := time.NewTicker(15 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			m.checkpoint()
			h.save()
			return
		case <-t.C:
			m.checkpoint()
			h.save()
		}
	}
}
func historyID(parts ...string) string {
	s := sha256.Sum256([]byte(strings.Join(parts, "\x00")))
	return hex.EncodeToString(s[:])
}
func (h *TrafficHistory) addPC(host string, up, down, count, failed int64) {
	now := time.Now()
	day := now.Format("2006-01-02")
	id := historyID("pc", day, host)
	h.mu.Lock()
	defer h.mu.Unlock()
	r, exists := h.rows[id]
	if !exists {
		if len(h.rows) >= 100000 {
			h.problem = "历史容量已满，请缩短保留范围后再采集"
			return
		}
		r = HistoryRow{ID: id, Source: "pc", Day: day, App: "来源应用未关联", Host: host, Route: "电脑出口", Protocol: "TCP", First: now.UnixMilli()}
	}
	r.Up += up
	r.Down += down
	r.Count += count
	r.Failed += failed
	r.Last = now.UnixMilli()
	h.rows[id] = r
}
func (m *ProxyMetrics) checkpoint() {
	m.flowsMu.Lock()
	defer m.flowsMu.Unlock()
	for _, f := range m.flows {
		m.recordFlow(f)
	}
}
func (m *ProxyMetrics) recordFlow(f *proxyFlow) {
	if m.history == nil {
		return
	}
	f.accountMu.Lock()
	defer f.accountMu.Unlock()
	up, down := f.upload.Load(), f.download.Load()
	count := int64(0)
	if !f.accounted {
		count = 1
		f.accounted = true
	}
	if up != f.savedUp || down != f.savedDown || count > 0 {
		m.history.addPC(f.Target, up-f.savedUp, down-f.savedDown, count, 0)
		f.savedUp = up
		f.savedDown = down
	}
}
func cleanHistoryRow(r HistoryRow) bool {
	_, e := time.Parse("2006-01-02", r.Day)
	return e == nil && len(r.ID) == 64 && len(r.App) <= 240 && len(r.Package) <= 240 && len(r.Host) <= 240 && len(r.Route) <= 40 && len(r.Protocol) <= 12 && r.Up >= 0 && r.Down >= 0 && r.Count >= 0 && r.Up < 1<<60 && r.Down < 1<<60 && r.Count < 1<<40 && r.Revision > 0 && r.First > 0 && r.Last >= r.First && !strings.ContainsAny(r.Host, "/?#@\r\n") && r.Day >= time.Now().AddDate(0, 0, -365).Format("2006-01-02") && r.Day <= time.Now().AddDate(0, 0, 1).Format("2006-01-02")
}
func (a *App) importHistory(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != "POST" || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+a.pair.ProxyPassword)) != 1 {
		http.Error(w, "forbidden", 403)
		return
	}
	source, _, _ := net.SplitHostPort(r.RemoteAddr)
	if source != "10.144.77.2" {
		http.Error(w, "peer required", 403)
		return
	}
	if a.metrics.history == nil {
		http.Error(w, "unavailable", 503)
		return
	}
	var rows []HistoryRow
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 512*1024)).Decode(&rows) != nil || len(rows) > 200 {
		http.Error(w, "invalid batch", 400)
		return
	}
	for _, row := range rows {
		if !cleanHistoryRow(row) {
			http.Error(w, "invalid record", 400)
			return
		}
	}
	h := a.metrics.history
	h.mu.Lock()
	for _, row := range rows {
		key := "phone:" + row.ID
		old, ok := h.rows[key]
		if !ok && len(h.rows) >= 100000 {
			h.mu.Unlock()
			http.Error(w, "history capacity", 507)
			return
		}
		if !ok || row.Revision > old.Revision {
			row.Source = "phone"
			h.rows[key] = row
		}
	}
	h.mu.Unlock()
	if h.save() != nil {
		http.Error(w, "history persistence failed", 507)
		return
	}
	w.WriteHeader(204)
}
func (h *TrafficHistory) query(from, to, app, host, route, source, group string) map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	rows := []HistoryRow{}
	groups := map[string]HistoryRow{}
	var up, down, count, failed int64
	for _, r := range h.rows {
		if r.Source != source || r.Day < from || r.Day > to || !strings.Contains(strings.ToLower(r.App+" "+r.Package), strings.ToLower(app)) || !strings.Contains(strings.ToLower(r.Host), strings.ToLower(host)) || (route != "" && r.Route != route) {
			continue
		}
		up += r.Up
		down += r.Down
		count += r.Count
		failed += r.Failed
		if group == "app" || group == "host" {
			key := r.App + "\x00" + r.Package
			if group == "host" {
				key = r.Host
			}
			g, ok := groups[key]
			if !ok {
				g = r
				g.Up = 0
				g.Down = 0
				g.Count = 0
				g.Failed = 0
				g.Day = "所选期间"
				g.Route = "按筛选汇总"
				g.Protocol = ""
				if group == "app" {
					g.Host = "全部匹配网站"
				} else {
					g.App = "全部匹配应用"
					g.Package = ""
				}
			}
			g.Up += r.Up
			g.Down += r.Down
			g.Count += r.Count
			g.Failed += r.Failed
			if r.Last > g.Last {
				g.Last = r.Last
			}
			if r.First < g.First {
				g.First = r.First
			}
			groups[key] = g
		} else {
			rows = append(rows, r)
		}
	}
	for _, r := range groups {
		rows = append(rows, r)
	}
	sort.Slice(rows, func(i, j int) bool {
		if group != "" && group != "detail" {
			return rows[i].Up+rows[i].Down > rows[j].Up+rows[j].Down
		}
		return rows[i].Last > rows[j].Last
	})
	return map[string]any{"rows": rows, "up": up, "down": down, "count": count, "failed": failed, "error": h.problem}
}
func (a *App) historyQuery(w http.ResponseWriter, r *http.Request) {
	if a.metrics.history == nil {
		http.Error(w, "unavailable", 503)
		return
	}
	q := r.URL.Query()
	source := q.Get("source")
	if source != "phone" {
		source = "pc"
	}
	from, to := q.Get("from"), q.Get("to")
	if from == "" {
		from = "0000-01-01"
	}
	if to == "" {
		to = "9999-12-31"
	}
	result := a.metrics.history.query(from, to, q.Get("app"), q.Get("host"), q.Get("route"), source, q.Get("group"))
	rows := result["rows"].([]HistoryRow)
	result["totalRows"] = len(rows)
	offset := 0
	fmtOffset := q.Get("offset")
	for _, c := range fmtOffset {
		if c < '0' || c > '9' {
			offset = 0
			break
		}
		offset = offset*10 + int(c-'0')
		if offset > 100000 {
			offset = 100000
			break
		}
	}
	if offset > len(rows) {
		offset = len(rows)
	}
	end := offset + 100
	if end > len(rows) {
		end = len(rows)
	}
	result["rows"] = rows[offset:end]
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(result)
}
