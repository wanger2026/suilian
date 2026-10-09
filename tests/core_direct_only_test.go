package easytier

import (
	"context"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"

	host "github.com/easytier/easytier/easytier-go"
)

func TestWangChuanMandatoryDirectFlag(t *testing.T) {
	for _, input := range []string{"", "[flags]\np2p_only = false\n", "[flags]\np2p_only = true\n"} {
		actual := ApplyRequiredFlags(input)
		if strings.Count(actual, "p2p_only = true") != 1 || strings.Contains(actual, "p2p_only = false") {
			t.Fatal("direct-only flag could be bypassed")
		}
	}
}

// The relay control proves the route works before testing a fail-closed client.
func TestWangChuanActualCoreBlocksRelayButAllowsDirect(t *testing.T) {
	for _, tc := range []struct {
		name                 string
		strict, direct, want bool
	}{
		{"relay-control", false, false, true},
		{"relay-blocked", true, false, false},
		{"direct-allowed", true, true, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
			defer cancel()
			engine, err := host.New(ctx, host.Options{})
			if err != nil {
				t.Fatal(err)
			}
			defer engine.Close(context.Background())
			reserve := func() int {
				l, e := net.Listen("tcp4", "127.0.0.1:0")
				if e != nil {
					t.Fatal(e)
				}
				p := l.Addr().(*net.TCPAddr).Port
				l.Close()
				return p
			}
			relayPort, serverPort := reserve(), reserve()
			makeNode := func(name, ip string, port, peer int, strict bool) *host.Instance {
				listeners := "[]"
				if port != 0 {
					listeners = fmt.Sprintf("[\"tcp://127.0.0.1:%d\"]", port)
				}
				config := fmt.Sprintf("ipv4 = %q\nlisteners = %s\nstun_servers = []\nstun_servers_v6 = []\n[network_identity]\nnetwork_name = %q\nnetwork_secret = \"non-secret-isolated-test-fixture\"\n", ip, listeners, "wangchuan-core-test-"+tc.name)
				if peer != 0 {
					config += fmt.Sprintf("[[peer]]\nuri = \"tcp://127.0.0.1:%d\"\n", peer)
				}
				config += fmt.Sprintf("[flags]\nno_tun = true\nbind_device = false\ndisable_p2p = true\np2p_only = %t\n", strict)
				node, e := engine.CreateInstanceTOML(ctx, name, "", config)
				if e != nil {
					t.Fatal(e)
				}
				if e = node.Start(ctx); e != nil {
					t.Fatal(e)
				}
				return node
			}
			relay := makeNode("relay", "10.244.71.3", relayPort, 0, false)
			defer relay.Close(context.Background())
			serverPeer, clientPeer := relayPort, relayPort
			if tc.direct {
				serverPeer, clientPeer = 0, serverPort
			}
			server := makeNode("server", "10.244.71.1", serverPort, serverPeer, tc.strict)
			defer server.Close(context.Background())
			client := makeNode("client", "10.244.71.2", 0, clientPeer, tc.strict)
			defer client.Close(context.Background())
			var receive, send net.PacketConn
			until := time.Now().Add(8 * time.Second)
			for time.Now().Before(until) {
				if receive == nil {
					receive, _ = server.ListenPacket("udp4", ":38124")
				}
				if send == nil {
					send, _ = client.ListenPacket("udp4", ":38125")
				}
				if receive != nil && send != nil {
					break
				}
				time.Sleep(50 * time.Millisecond)
			}
			if receive == nil || send == nil {
				t.Fatal("core data plane did not start")
			}
			defer receive.Close()
			defer send.Close()
			target := &net.UDPAddr{IP: net.ParseIP("10.244.71.1"), Port: 38124}
			source := &net.UDPAddr{IP: net.ParseIP("10.244.71.2"), Port: 38125}
			payload := []byte("wangchuan-direct-policy-proof")
			observed := false
			until = time.Now().Add(8 * time.Second)
			for time.Now().Before(until) {
				receive.WriteTo([]byte("warmup"), source)
				send.WriteTo(payload, target)
				receive.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
				buf := make([]byte, 128)
				n, from, e := receive.ReadFrom(buf)
				if e == nil && string(buf[:n]) == string(payload) {
					observed = true
					if !tc.want {
						t.Fatal("business payload reached peer through relay")
					}
					if tc.direct {
						receive.WriteTo(payload, from)
						send.SetReadDeadline(time.Now().Add(time.Second))
						for {
							n, _, e = send.ReadFrom(buf)
							if e != nil || string(buf[:n]) != "warmup" {
								break
							}
						}
						if e != nil || string(buf[:n]) != string(payload) {
							t.Fatal("return path failed", e)
						}
					}
					break
				}
			}
			if !tc.want {
				routes, e := client.ListRoute(ctx)
				if e != nil {
					t.Fatal(e)
				}
				found := false
				for _, route := range routes {
					if route.GetIpv4Addr().GetAddress().GetAddr() == 183781121 && route.GetCost() >= 2 {
						found = true
					}
				}
				if !found {
					t.Fatal("negative test lacked a discovered relay route")
				}
			}
			if observed != tc.want {
				info, _ := client.ShowNodeInfo(ctx)
				peers, _ := client.ListPeer(ctx)
				routes, _ := client.ListRoute(ctx)
				t.Logf("node=%v peers=%v routes=%v", info, peers, routes)
				t.Fatalf("payload observed=%t, want=%t", observed, tc.want)
			}
		})
	}
}
