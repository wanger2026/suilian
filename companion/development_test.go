package main

import (
	"bytes"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestDevelopmentCannotOpenWithoutAuthOrRemoteSession(t *testing.T) {
	hub := newDevHub("unit-test-fixture")
	hub.adbAddress = "127.0.0.1:0"
	server := httptest.NewServer(hub)
	defer server.Close()
	defer hub.Close()
	address := "ws" + strings.TrimPrefix(server.URL, "http") + "/development/control"
	_, response, err := websocket.DefaultDialer.Dial(address, nil)
	if err == nil || response.StatusCode != 403 {
		t.Fatal("unauthenticated development accepted")
	}
	header := http.Header{"Authorization": []string{"Bearer " + hub.token}}
	ws, _, err := websocket.DefaultDialer.Dial(address, header)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	ws.WriteJSON(devHello{Type: "hello", RemoteReady: false, ADBPort: 45678})
	ws.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err := ws.ReadMessage(); err == nil {
		t.Fatal("idle remote page accepted")
	}
	if hub.state()["active"].(bool) {
		t.Fatal("listener active without remote session")
	}
}

func TestDevelopmentCarriesBytesAndRevokesEstablishedStreams(t *testing.T) {
	for _, reason := range []string{"remote-closed", "control-disconnected", "heartbeat-expired"} {
		t.Run(reason, func(t *testing.T) {
			hub := newDevHub("unit-test-fixture")
			hub.adbAddress = "127.0.0.1:0"
			server := httptest.NewServer(hub)
			defer server.Close()
			defer hub.Close()
			base := "ws" + strings.TrimPrefix(server.URL, "http")
			header := http.Header{"Authorization": []string{"Bearer " + hub.token}}
			control, _, err := websocket.DefaultDialer.Dial(base+"/development/control", header)
			if err != nil {
				t.Fatal(err)
			}
			defer control.Close()
			control.WriteJSON(devHello{Type: "hello", RemoteReady: true, ADBPort: 45678})
			var address string
			deadline := time.Now().Add(2 * time.Second)
			for time.Now().Before(deadline) {
				hub.mu.Lock()
				session := hub.active
				hub.mu.Unlock()
				if session != nil {
					session.mu.Lock()
					if len(session.listeners) > 0 {
						address = session.listeners[0].Addr().String()
					}
					session.mu.Unlock()
				}
				if address != "" {
					break
				}
				time.Sleep(5 * time.Millisecond)
			}
			if address == "" {
				t.Fatal("local listener not created")
			}
			connection, err := net.Dial("tcp", address)
			if err != nil {
				t.Fatal(err)
			}
			defer connection.Close()
			connection.SetDeadline(time.Now().Add(3 * time.Second))
			control.SetReadDeadline(time.Now().Add(3 * time.Second))
			var opened map[string]string
			if control.ReadJSON(&opened) != nil || opened["type"] != "open" || opened["kind"] != "adb" {
				t.Fatal("open handshake failed")
			}
			data, _, err := websocket.DefaultDialer.Dial(base+"/development/data?id="+opened["id"], header)
			if err != nil {
				t.Fatal(err)
			}
			defer data.Close()
			_, response, err := websocket.DefaultDialer.Dial(base+"/development/data?id="+opened["id"], header)
			if err == nil || response.StatusCode != 403 {
				t.Fatal("stream capability replay accepted")
			}
			done := make(chan struct{})
			go func() {
				defer close(done)
				for {
					kind, payload, err := data.ReadMessage()
					if err != nil {
						return
					}
					if data.WriteMessage(kind, payload) != nil {
						return
					}
				}
			}()
			payload := bytes.Repeat([]byte("adb-byte-integrity"), 10000)
			go connection.Write(payload)
			actual := make([]byte, len(payload))
			if _, err = io.ReadFull(connection, actual); err != nil || !bytes.Equal(actual, payload) {
				t.Fatal("forwarded bytes corrupted", err)
			}
			if reason == "remote-closed" {
				control.WriteJSON(map[string]any{"type": "heartbeat", "remoteReady": false})
			} else if reason == "control-disconnected" {
				control.Close()
			}
			wait := 2 * time.Second
			if reason == "heartbeat-expired" {
				wait += devHeartbeatTimeout
			}
			connection.SetReadDeadline(time.Now().Add(wait))
			if n, err := connection.Read(make([]byte, 1)); n != 0 || err == nil {
				t.Fatal("existing data stream survived revocation")
			}
			select {
			case <-done:
			case <-time.After(2 * time.Second):
				t.Fatal("data WebSocket survived revocation")
			}
			if c, err := net.DialTimeout("tcp", address, 100*time.Millisecond); err == nil {
				c.Close()
				t.Fatal("listener survived revocation")
			}
		})
	}
}

func TestPairingValidationAndTokenSeparation(t *testing.T) {
	if developmentToken("fixture") == "fixture" || len(developmentToken("fixture")) != 64 {
		t.Fatal("development credential not derived")
	}
	for _, h := range []devHello{
		{Type: "hello", RemoteReady: true, ADBPort: 5555, PairPort: 4444, PairCode: "12345x"},
		{Type: "hello", RemoteReady: true, ADBPort: 0},
		{Type: "hello", RemoteReady: true, ADBPort: 5555, PairPort: 4444},
	} {
		if h.valid() {
			t.Fatal("invalid pairing accepted")
		}
	}
}
