package main

import (
	"context"
	"net"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// Counts authenticated TCP proxy payload only; excludes overlay headers and UDP.
type ProxyMetrics struct {
	history     *TrafficHistory
	upload      atomic.Int64
	download    atomic.Int64
	active      atomic.Int64
	total       atomic.Int64
	failures    atomic.Int64
	lastSuccess atomic.Int64
	flowsMu     sync.Mutex
	flows       map[int64]*proxyFlow
}

type proxyFlow struct {
	accountMu          sync.Mutex
	savedUp, savedDown int64
	accounted          bool
	ID                 int64  `json:"id"`
	Target             string `json:"target"`
	Started            int64  `json:"started"`
	upload, download   atomic.Int64
	closed             atomic.Bool
}

func (m *ProxyMetrics) snapshot() map[string]any {
	m.flowsMu.Lock()
	ids := make([]int64, 0, len(m.flows))
	for id := range m.flows {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] > ids[j] })
	rows := make([]map[string]any, 0, 100)
	for _, id := range ids {
		if len(rows) >= 100 {
			break
		}
		f := m.flows[id]
		rows = append(rows, map[string]any{"id": f.ID, "target": f.Target, "started": f.Started, "uploadBytes": f.upload.Load(), "downloadBytes": f.download.Load(), "active": !f.closed.Load()})
	}
	m.flowsMu.Unlock()
	return map[string]any{"uploadBytes": m.upload.Load(), "downloadBytes": m.download.Load(),
		"activeConnections": m.active.Load(), "totalConnections": m.total.Load(),
		"failedConnections": m.failures.Load(), "lastSuccess": m.lastSuccess.Load(), "scope": "tcp-proxy-since-start", "flows": rows}
}
func (m *ProxyMetrics) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	c, e := (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext(ctx, network, addr)
	if e != nil {
		m.failures.Add(1)
		if m.history != nil {
			m.history.addPC(addr, 0, 0, 1, 1)
		}
		return nil, e
	}
	m.active.Add(1)
	id := m.total.Add(1)
	f := &proxyFlow{ID: id, Target: addr, Started: time.Now().Unix()}
	m.flowsMu.Lock()
	if m.flows == nil {
		m.flows = make(map[int64]*proxyFlow)
	}
	// Retain at most 200 recent flows; counters above remain cumulative.
	if len(m.flows) >= 200 {
		oldest := id
		for key := range m.flows {
			if key < oldest {
				oldest = key
			}
		}
		delete(m.flows, oldest)
	}
	m.flows[id] = f
	m.flowsMu.Unlock()
	return &meteredConn{Conn: c, metrics: m, flow: f}, nil
}

type meteredConn struct {
	net.Conn
	metrics *ProxyMetrics
	flow    *proxyFlow
	once    sync.Once
}

func (c *meteredConn) Read(p []byte) (int, error) {
	n, e := c.Conn.Read(p)
	c.metrics.download.Add(int64(n))
	if c.flow != nil {
		c.flow.download.Add(int64(n))
	}
	if n > 0 {
		c.metrics.lastSuccess.Store(time.Now().Unix())
	}
	return n, e
}
func (c *meteredConn) Write(p []byte) (int, error) {
	n, e := c.Conn.Write(p)
	c.metrics.upload.Add(int64(n))
	if c.flow != nil {
		c.flow.upload.Add(int64(n))
	}
	return n, e
}
func (c *meteredConn) Close() error {
	c.once.Do(func() {
		c.metrics.active.Add(-1)
		if c.flow != nil {
			c.flow.closed.Store(true)
			c.metrics.recordFlow(c.flow)
		}
	})
	return c.Conn.Close()
}
func (c *meteredConn) CloseWrite() error {
	if h, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return h.CloseWrite()
	}
	return nil
}
func (c *meteredConn) CloseRead() error {
	if h, ok := c.Conn.(interface{ CloseRead() error }); ok {
		return h.CloseRead()
	}
	return nil
}
