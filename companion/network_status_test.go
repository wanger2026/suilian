package main

import "testing"

func TestNetworkStatusDoesNotMistakeLocalListenerForPhone(t *testing.T) {
	local := parseNetworkStatus([]byte(`[{"ipv4":"10.144.77.1","cost":"Local"}]`))
	if local.EntryConnected || local.PhoneJoined {
		t.Fatal("local node was reported as connected")
	}
	relay := parseNetworkStatus([]byte(`[{"ipv4":"","cost":"p2p"},{"ipv4":"10.144.77.1","cost":"Local"}]`))
	if !relay.EntryConnected || relay.PhoneJoined {
		t.Fatal("relay was reported as phone")
	}
	phone := parseNetworkStatus([]byte(`[{"ipv4":"10.144.77.2","cost":"p2p"}]`))
	if !phone.PhoneJoined {
		t.Fatal("joined phone not detected")
	}
	for _, cost := range []string{"relay", "relay(1)", "", "unknown"} {
		phone = parseNetworkStatus([]byte(`[{"ipv4":"10.144.77.2","cost":"` + cost + `"}]`))
		if phone.PhoneJoined {
			t.Fatalf("non-direct route %q accepted", cost)
		}
	}
}
