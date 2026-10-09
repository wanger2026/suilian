package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/pelletier/go-toml/v2"
	"golang.org/x/crypto/nacl/secretbox"
	"golang.org/x/sys/windows/registry"
)

func remotePassword(proxyPassword string) string {
	mac := hmac.New(sha256.New, []byte(proxyPassword))
	mac.Write([]byte("wangchuan-rustdesk-v1"))
	return hex.EncodeToString(mac.Sum(nil))[:24]
}

func encryptRemotePassword(password, salt, machineID string) (string, error) {
	if len(machineID) < 16 {
		return "", errors.New("无法取得电脑加密标识")
	}
	hash := sha256.Sum256([]byte(password + salt))
	var key [32]byte
	copy(key[:], []byte(machineID))
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return "", err
	}
	data := secretbox.Seal(nil, []byte("00"+base64.StdEncoding.EncodeToString(hash[:])), &nonce, &key)
	encrypted := append([]byte{1}, nonce[:]...)
	encrypted = append(encrypted, data...)
	return "01" + base64.StdEncoding.EncodeToString(encrypted), nil
}

func readTOML(path string) (map[string]any, error) {
	result := map[string]any{}
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return result, nil
	}
	if err != nil {
		return nil, err
	}
	err = toml.Unmarshal(data, &result)
	return result, err
}

func saveRemoteConfig(configDir, backupDir, password, machineID string) error {
	first := filepath.Join(configDir, "RustDesk.toml")
	second := filepath.Join(configDir, "RustDesk2.toml")
	config, err := readTOML(first)
	if err != nil {
		return errors.New("远控已有配置无法读取，原配置已保留")
	}
	optionsConfig, err := readTOML(second)
	if err != nil {
		return errors.New("远控已有设置无法读取，原设置已保留")
	}
	if err = privateDirectory(backupDir); err != nil {
		return err
	}
	if err = os.MkdirAll(configDir, 0700); err != nil {
		return err
	}
	for _, path := range []string{first, second} {
		dest := filepath.Join(backupDir, filepath.Base(path)+".dpapi")
		if _, err := os.Stat(dest); os.IsNotExist(err) {
			if original, err := os.ReadFile(path); err == nil {
				encrypted, err := seal(original)
				if err != nil {
					return err
				}
				if err = os.WriteFile(dest, encrypted, 0600); err != nil {
					return err
				}
			} else if !os.IsNotExist(err) {
				return err
			}
		}
	}
	salt := secret()[:32]
	stored, err := encryptRemotePassword(password, salt, machineID)
	if err != nil {
		return err
	}
	config["password"], config["salt"] = stored, salt
	options, ok := optionsConfig["options"].(map[string]any)
	if !ok {
		options = map[string]any{}
		optionsConfig["options"] = options
	}
	options["direct-server"] = "Y"
	options["stop-service"] = "N"
	options["direct-access-port"] = "21118"
	options["verification-method"] = "use-permanent-password"
	options["approve-mode"] = "password"
	// Restrict this managed remote endpoint to the paired phone and local probes.
	options["whitelist"] = "10.144.77.2/32,127.0.0.1/32"
	for path, data := range map[string]map[string]any{first: config, second: optionsConfig} {
		encoded, err := toml.Marshal(data)
		if err != nil {
			return err
		}
		if err = os.WriteFile(path+".wangchuan-new", encoded, 0600); err != nil {
			return err
		}
		if err = os.Rename(path+".wangchuan-new", path); err != nil {
			return err
		}
	}
	return nil
}

func (a *App) prepareRemote() error {
	a.remoteSetupMu.Lock()
	defer a.remoteSetupMu.Unlock()
	if tcpReady("127.0.0.1:21118") && a.remoteConfigured.Load() {
		return nil
	}
	if err := a.stopManagedRemote(); err != nil {
		return err
	}
	key, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\Microsoft\Cryptography`, registry.QUERY_VALUE|registry.WOW64_64KEY)
	if err != nil {
		return errors.New("无法读取电脑加密标识")
	}
	machineID, _, err := key.GetStringValue("MachineGuid")
	key.Close()
	if err != nil {
		return errors.New("无法读取电脑加密标识")
	}
	configDir := filepath.Join(os.Getenv("APPDATA"), "RustDesk", "config")
	backup := filepath.Join(os.Getenv("LOCALAPPDATA"), "WangChuanLink", "remote-backup")
	if err = saveRemoteConfig(configDir, backup, remotePassword(a.pair.ProxyPassword), machineID); err != nil {
		return err
	}
	if err = a.startChild("rustdesk"); err != nil {
		return err
	}
	for i := 0; i < 40; i++ {
		if tcpReady("127.0.0.1:21118") {
			a.remoteConfigured.Store(true)
			return nil
		}
		time.Sleep(250 * time.Millisecond)
	}
	return errors.New("远控设置已保存，但监听尚未就绪；请查看连接诊断")
}

func tcpReady(address string) bool {
	conn, err := net.DialTimeout("tcp", address, 180*time.Millisecond)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}
