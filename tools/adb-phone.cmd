@echo off
setlocal
powershell.exe -NoProfile -Command "try { $s=Invoke-RestMethod -Uri http://127.0.0.1:17881/development-status -TimeoutSec 2; if (-not $s.active) { exit 1 } } catch { exit 1 }"
if errorlevel 1 (
  echo Phone development session is not active. Enable it in the remote desktop screen. 1>&2
  exit /b 1
)
set "ANDROID_USER_HOME=%LOCALAPPDATA%\WangChuanLink\development-adb"
set "ADB_MDNS_AUTO_CONNECT=0"
set "ADB_SERVER_SOCKET="
set "ADB_VENDOR_KEYS="
"%~dp0runtime\adb\adb.exe" -P 17885 -s 127.0.0.1:17883 %*
exit /b %errorlevel%
