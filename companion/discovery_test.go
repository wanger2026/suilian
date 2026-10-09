package main

import (
	"reflect"
	"testing"
)

func TestLegacyDiscoveryMigrationPreservesCustomEntries(t *testing.T) {
	got := repairDiscoveryPeers([]string{"tcp://private.example:11010", "tcp://public.easytier.top:11010", "tcp://public.easytier.cn:11010"})
	want := append([]string{"tcp://private.example:11010"}, defaultDiscoveryPeers()...)
	if !reflect.DeepEqual(got, want) {
		t.Fatal("legacy migration lost custom entry or introduced duplicates")
	}
}
