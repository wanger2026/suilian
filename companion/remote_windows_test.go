package main

import (
	"crypto/sha256"
	"encoding/base64"
	"golang.org/x/crypto/nacl/secretbox"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRemoteConfigurationPreservesSettingsAndKeepsPasswordEncrypted(t *testing.T) {
	home := t.TempDir()
	configDir := filepath.Join(home, "config")
	backupDir := filepath.Join(home, "backup")
	os.MkdirAll(configDir, 0700)
	original := []byte("id = 'fixture-id'\nsalt = 'old-salt'\npassword = ''\n")
	os.WriteFile(filepath.Join(configDir, "RustDesk.toml"), original, 0600)
	os.WriteFile(filepath.Join(configDir, "RustDesk2.toml"), []byte("rendezvous_server = 'preserved.example'\n[options]\ncustom = 'preserved'\nstop-service = 'Y'\n"), 0600)
	password := remotePassword("unit-test-proxy-password")
	machineID := "test-machine-uuid-not-a-real-machine"
	if err := saveRemoteConfig(configDir, backupDir, password, machineID); err != nil {
		t.Fatal(err)
	}
	first, _ := readTOML(filepath.Join(configDir, "RustDesk.toml"))
	second, _ := readTOML(filepath.Join(configDir, "RustDesk2.toml"))
	if first["id"] != "fixture-id" || second["rendezvous_server"] != "preserved.example" {
		t.Fatal("unrelated settings overwritten")
	}
	options := second["options"].(map[string]any)
	if options["custom"] != "preserved" || options["direct-server"] != "Y" || options["verification-method"] != "use-permanent-password" {
		t.Fatal("remote settings incorrect")
	}
	if options["whitelist"] != "10.144.77.2/32,127.0.0.1/32" {
		t.Fatal("unrestricted remote access")
	}
	if options["stop-service"] != "N" {
		t.Fatal("previous stop-service flag still disables direct access")
	}
	stored := first["password"].(string)
	blob, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(stored, "01"))
	if err != nil || blob[0] != 1 {
		t.Fatal("bad upstream encoding")
	}
	var key [32]byte
	copy(key[:], []byte(machineID))
	var nonce [24]byte
	copy(nonce[:], blob[1:25])
	plain, ok := secretbox.Open(nil, blob[25:], &nonce, &key)
	expected := sha256.Sum256([]byte(password + first["salt"].(string)))
	if !ok || string(plain) != "00"+base64.StdEncoding.EncodeToString(expected[:]) {
		t.Fatal("upstream salted hash mismatch")
	}
	for _, name := range []string{"RustDesk.toml", "RustDesk2.toml"} {
		data, _ := os.ReadFile(filepath.Join(configDir, name))
		if strings.Contains(string(data), password) {
			t.Fatal("plaintext password written")
		}
	}
	encrypted, _ := os.ReadFile(filepath.Join(backupDir, "RustDesk.toml.dpapi"))
	restored, err := unseal(encrypted)
	if err != nil || string(restored) != string(original) {
		t.Fatal("encrypted backup cannot be restored")
	}
	if err := saveRemoteConfig(configDir, backupDir, password, machineID); err != nil {
		t.Fatal(err)
	}
	encrypted2, _ := os.ReadFile(filepath.Join(backupDir, "RustDesk.toml.dpapi"))
	if string(encrypted2) != string(encrypted) {
		t.Fatal("original backup overwritten")
	}
}
