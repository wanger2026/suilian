param([switch]$NoBrowser, [int]$ManagerPid = 0)
$ErrorActionPreference = 'Stop'
$startupReport = Join-Path $PSScriptRoot 'startup-status.json'
function Report-Startup([string]$Stage, [string]$Failure = '') {
    @{ stage=$Stage; error=$Failure; time=[DateTime]::UtcNow.ToString('o') } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath $startupReport -Encoding UTF8
}
trap {
    Report-Startup 'failed' $_.Exception.Message
    exit 1
}
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Report-Startup 'awaiting-administrator'
    Start-Process powershell.exe -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"' + $(if ($NoBrowser) { ' -NoBrowser' } else { '' }) + ' -ManagerPid ' + $ManagerPid) -Verb RunAs -WindowStyle Hidden
    exit
}
Report-Startup 'preparing'
$available = $false
$previous = $null
try { $previous = Invoke-RestMethod 'http://127.0.0.1:17881/management' -TimeoutSec 2 } catch { }
if ($previous -and $previous.version -ne '0.3.17') {
    $owner = Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 17881 -State Listen -ErrorAction Stop | Select-Object -First 1
    $backend = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $owner.OwningProcess)
    if ([IO.Path]::GetFileName($backend.ExecutablePath) -ne 'WangChuanLink.exe') { throw 'Unexpected process owns the manager port.' }
    $previousRoot = Split-Path -Parent $backend.ExecutablePath
    $ownedPaths = @('WangChuanLink.exe','runtime\easytier\easytier-core.exe','runtime\rustdesk\rustdesk.exe','runtime\sunshine\sunshine.exe','runtime\adb\adb.exe') | ForEach-Object { Join-Path $previousRoot $_ }
    $managed = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $ownedPaths -contains $_.ExecutablePath })
    $csrf = (Invoke-RestMethod 'http://127.0.0.1:17881/manager-token' -TimeoutSec 3).token
    Invoke-WebRequest 'http://127.0.0.1:17881/action' -Method Post -Headers @{'X-CSRF'=$csrf} -Body 'quit' -UseBasicParsing -TimeoutSec 5 | Out-Null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if (-not (Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 17881 -State Listen -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 500
    }
    if (Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 17881 -State Listen -ErrorAction SilentlyContinue) { throw 'The previous backend has not exited.' }
    # A normal upgrade must wait for graceful shutdown, never force-kill elevated children.
    $deadline=[DateTime]::UtcNow.AddSeconds(35)
    do {
        $remaining=@(foreach ($child in $managed) {
            $current=Get-CimInstance Win32_Process -Filter ('ProcessId='+$child.ProcessId)
            if($current -and $current.ExecutablePath -eq $child.ExecutablePath -and $current.CreationDate -eq $child.CreationDate){$current}
        })
        if($remaining.Count -eq 0){break}
        Start-Sleep -Milliseconds 350
    } while([DateTime]::UtcNow -lt $deadline)
    if($remaining.Count -gt 0){throw '旧版组件尚未正常退出。请先在旧版管理窗口彻底退出，再重新准备；本次没有强制终止进程。'}

}
foreach ($entry in @(
    @{ Name = 'WangChuanLink-Private-Proxy'; Path = 'WangChuanLink.exe' },
    @{ Name = 'WangChuanLink-Private-RustDesk'; Path = 'runtime\rustdesk\rustdesk.exe' },
    @{ Name = 'WangChuanLink-Private-Sunshine'; Path = 'runtime\sunshine\sunshine.exe' }
)) {
    $componentPath = Join-Path $PSScriptRoot $entry.Path
    if (-not (Test-Path -LiteralPath $componentPath)) { throw 'Incomplete package. Extract all files before starting.' }
    if (-not (Get-NetFirewallRule -Name $entry.Name -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -Name $entry.Name -DisplayName $entry.Name -Group 'WangChuanLink' -Direction Inbound -Action Allow -Program $componentPath -LocalAddress '10.144.77.1' -RemoteAddress '10.144.77.0/24' -Profile Any | Out-Null
    } else {
        Get-NetFirewallRule -Name $entry.Name | Get-NetFirewallApplicationFilter | Set-NetFirewallApplicationFilter -Program $componentPath | Out-Null
    }
}
$p2pPath = Join-Path $PSScriptRoot 'runtime\easytier\easytier-core.exe'
if (-not (Test-Path -LiteralPath $p2pPath)) { throw 'The P2P component is missing.' }
if (-not (Get-NetFirewallRule -Name 'WangChuanLink-P2P' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name 'WangChuanLink-P2P' -DisplayName 'WangChuanLink-P2P' -Group 'WangChuanLink' -Direction Inbound -Action Allow -Program $p2pPath -Profile Any | Out-Null
} else {
    Get-NetFirewallRule -Name 'WangChuanLink-P2P' | Get-NetFirewallApplicationFilter | Set-NetFirewallApplicationFilter -Program $p2pPath | Out-Null
}
try { $available = (Invoke-WebRequest 'http://127.0.0.1:17881/status' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 } catch { }
if (-not $available) {
    if ($ManagerPid -gt 0) {
        $manager = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $ManagerPid)
        if (-not $manager -or $manager.ExecutablePath -ne (Join-Path $PSScriptRoot 'WangChuanManager.exe')) { throw 'Manager already exited. Startup cancelled.' }
    }
    Report-Startup 'starting-backend'
    $backendError = Join-Path $PSScriptRoot 'startup-backend-error.txt'
    $startedBackend = Start-Process -FilePath (Join-Path $PSScriptRoot 'WangChuanLink.exe') -ArgumentList ('--manager-pid=' + $ManagerPid) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardError $backendError
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 500
        try { if ((Invoke-WebRequest 'http://127.0.0.1:17881/status' -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200) { break } } catch { }
        if ($startedBackend.HasExited) {
            $reason = if (Test-Path -LiteralPath $backendError) { (Get-Content -LiteralPath $backendError -Encoding UTF8 | Select-Object -First 3) -join ' ' } else { '' }
            throw ('电脑后台启动后退出：' + $reason)
        }
    }
}
try { $available = (Invoke-WebRequest 'http://127.0.0.1:17881/status' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 } catch { $available=$false }
if (-not $available) { throw '电脑后台未在规定时间内就绪，请查看启动诊断。' }
Report-Startup 'ready'
if (-not $NoBrowser) { Start-Process -FilePath (Join-Path $PSScriptRoot 'WangChuanManager.exe') }
