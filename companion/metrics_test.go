package main

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

func TestProxyMetricsCountsPayloadAndClosesOnce(t *testing.T) {
	listener, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer listener.Close()
	go func() {
		c, e := listener.Accept()
		if e != nil {
			return
		}
		defer c.Close()
		io.Copy(c, c)
	}()
	m := &ProxyMetrics{}
	c, e := m.dial(context.Background(), "tcp", listener.Addr().String())
	if e != nil {
		t.Fatal(e)
	}
	c.SetDeadline(time.Now().Add(time.Second))
	c.Write([]byte("hello"))
	b := make([]byte, 5)
	if _, e = io.ReadFull(c, b); e != nil {
		t.Fatal(e)
	}
	c.Close()
	c.Close()
	if string(b) != "hello" || m.upload.Load() != 5 || m.download.Load() != 5 || m.active.Load() != 0 || m.total.Load() != 1 || m.lastSuccess.Load() == 0 {
		t.Fatal(m.snapshot())
	}
	rows := m.snapshot()["flows"].([]map[string]any)
	if len(rows) != 1 || rows[0]["target"] != listener.Addr().String() || rows[0]["active"] != false || rows[0]["downloadBytes"] != int64(5) || rows[0]["uploadBytes"] != int64(5) {
		t.Fatalf("per-connection payload accounting mismatch: %v", rows)
	}
}

func TestProxyMetricsRetentionIsBoundedAndNewestFirst(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	m := &ProxyMetrics{}
	for i := 0; i < 205; i++ {
		c, err := m.dial(context.Background(), "tcp", l.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		c.Close()
	}
	rows := m.snapshot()["flows"].([]map[string]any)
	if len(m.flows) != 200 || len(rows) != 100 || rows[0]["id"] != int64(205) || rows[99]["id"] != int64(106) || m.total.Load() != 205 {
		t.Fatal("flow history must stay bounded without resetting cumulative counters")
	}
}
func TestProxyMetricsFailedDialIsNotSuccess(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	m := &ProxyMetrics{}
	if _, e := m.dial(ctx, "tcp", "127.0.0.1:1"); e == nil {
		t.Fatal("expected cancelled dial")
	}
	if m.failures.Load() != 1 || m.active.Load() != 0 || m.lastSuccess.Load() != 0 {
		t.Fatal(m.snapshot())
	}
}
