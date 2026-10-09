param([string]$TargetDirectory = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$targetRoot = (Get-Item -LiteralPath $TargetDirectory -ErrorAction Stop).FullName.TrimEnd('\')
if (-not (Test-Path -LiteralPath (Join-Path $targetRoot 'WangChuanManager.exe')) -or
    -not (Test-Path -LiteralPath (Join-Path $targetRoot 'WangChuanLink.exe'))) {
    throw 'Select an extracted WangChuanLink program directory. Nothing was stopped.'
}
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    # This helper is an explicit user action; the manager never invokes it silently.
    $admin = Start-Process powershell.exe -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -TargetDirectory "' + $targetRoot + '"') -Verb RunAs -WindowStyle Hidden -PassThru -Wait
    if ($admin.ExitCode -ne 0) { throw 'Cleanup did not finish. Check administrator permission and try again.' }
    Write-Output 'Cleanup finished. Retry deleting the OLD directory in Explorer.'
    exit
}
$ownedPaths = @('WangChuanLink.exe','WangChuanManager.exe','runtime\easytier\easytier-core.exe','runtime\easytier\easytier-cli.exe','runtime\rustdesk\rustdesk.exe','runtime\sunshine\sunshine.exe','runtime\adb\adb.exe') | ForEach-Object { Join-Path $targetRoot $_ }
# Exact installed paths are required. Never terminate all processes by name.
$owned = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $ownedPaths -contains $_.ExecutablePath })
foreach ($entry in ($owned | Sort-Object @{Expression={if ($_.Name -eq 'WangChuanLink.exe') {0} else {1}}})) {
    $process = Get-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    try {
        $null = $process.Handle
        $current = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $entry.ProcessId)
        if ($current -and $current.CreationDate -eq $entry.CreationDate -and $current.ExecutablePath -eq $entry.ExecutablePath -and -not $process.HasExited) {
            $process.Kill()
            if (-not $process.WaitForExit(15000)) { throw ('Process did not exit: ' + $entry.ProcessId) }
        }
    } catch {
        # Stopping a guarded backend may already have ended this captured child.
        if (-not $process.HasExited) { throw }
    } finally { $process.Dispose() }
}
$remaining = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $ownedPaths -contains $_.ExecutablePath })
if ($remaining.Count -gt 0) { throw 'Some owned processes are still running. Cleanup is incomplete.' }
# No files, pairing data, services, firewall settings, or unrelated apps are deleted.
