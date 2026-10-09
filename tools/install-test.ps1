param([string]$Serial = '', [string]$Apk = '')

$ErrorActionPreference='Stop'

[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)

$adb=Join-Path $PSScriptRoot 'runtime\adb\adb.exe'

if(-not(Test-Path -LiteralPath ([string]$adb))){$adb=if($env:ANDROID_HOME){Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'}else{(Get-Command adb.exe -ErrorAction SilentlyContinue).Source}}

if(-not $adb -or -not(Test-Path -LiteralPath ([string]$adb))){throw '缺少 ADB，请完整解压电脑端或设置 ANDROID_HOME。'}

if(-not $Serial){

 $rows=@(& $adb devices | Where-Object {$_ -match '^([^\s]+)\s+device$'})

 if($rows.Count -ne 1){throw '请连接一部手机并开启 USB 调试，在手机上允许此电脑。检测到多部手机时，请指定 Serial。'}

 $Serial=($rows[0] -split '\s+')[0]

}

if(-not $Apk){

 $candidate=Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*test-arm64.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1

 if(-not $candidate){throw '请将测试 APK 与本脚本放在同一文件夹，或指定 Apk 路径。'}

 $Apk=$candidate.FullName

}

$Apk=(Resolve-Path -LiteralPath $Apk).Path

$installed=& $adb -s $Serial shell pm path cn.wangchuan.link.test

if($installed -match '^package:'){

 & $adb -s $Serial shell am start --activity-clear-top -n cn.wangchuan.link.test/cn.wangchuan.link.HomeActivity | Out-Null

 & $adb -s $Serial shell am broadcast -n cn.wangchuan.link.test/cn.wangchuan.link.DeviceProbeReceiver --es mode stop | Out-Null

 $stopped=$false

 for($i=0;$i -lt 12;$i++){

  $start=0L

  try {$previous=(& $adb -s $Serial shell run-as cn.wangchuan.link.test cat files/device-probe.json | Out-String)|ConvertFrom-Json;$start=$previous.time}catch{}

  & $adb -s $Serial shell am broadcast -n cn.wangchuan.link.test/cn.wangchuan.link.DeviceProbeReceiver --es mode status | Out-Null

  Start-Sleep -Milliseconds 600

  try {

   $report=(& $adb -s $Serial shell run-as cn.wangchuan.link.test cat files/device-probe.json | Out-String)|ConvertFrom-Json

   if($report.time -gt $start -and $report.mode -eq 'status' -and $report.active -eq $false -and $report.connecting -eq $false -and -not @($report.networks|Where-Object {$_.vpn}).Count){$stopped=$true;break}

  }catch{}

 }

 if(-not $stopped){throw '未确认旧 VPN 已完全停止，已取消安装。请在手机断开连接后重试；旧版本可能需手动升级。'}

}

$result=& $adb -s $Serial install --no-streaming -r $Apk
if($LASTEXITCODE -ne 0 -or -not ($result -match 'Success')){throw '安装未完成，请查看手机是否要求允许 USB 安装。原应用数据保留。'}

& $adb -s $Serial shell am start --activity-clear-top -n cn.wangchuan.link.test/cn.wangchuan.link.HomeActivity | Out-Null

Write-Output '安装成功，配对和设置已保留。请在手机开启连接；本次安装前已确认旧 VPN 停止。'
