$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\tools\usb-preflight.ps1')
function Assert($value,$message){if(-not $value){throw $message}}
$script:scenario='ready'
$script:calls=[Collections.Generic.List[string]]::new()
$fakeAdb={
    param([string[]]$Arguments)
    $line=$Arguments -join ' ';$script:calls.Add($line)
    switch($line){
        '-d get-serialno' {if($script:scenario -eq 'unauthorized'){return 'error: unauthorized'};return 'fixture-usb'}
        'devices -l' {return "List of devices attached`nfixture-usb unauthorized usb:fixture"}
        '-s fixture-usb get-state' {return 'device'}
        '-s fixture-usb shell getprop ro.product.model' {return 'Test Phone'}
        '-s fixture-usb shell getprop ro.build.version.sdk' {return '36'}
        '-s fixture-usb shell dumpsys package cn.wangchuan.link.test' {return 'versionName=0.3.8-test'}
        '-s fixture-usb shell settings get global adb_wifi_enabled' {if($script:scenario -eq 'off'){return '0'};return '1'}
        '-s fixture-usb shell getprop service.adb.tls.port' {if($script:scenario -eq 'hidden-port'){return ''};return '37821'}
        default {throw ('Unexpected ADB command: '+$line)}
    }
}
$r=Get-SuiLianUsbReport -Adb $fakeAdb
Assert $r.ready 'authorized USB was not detected'
Assert (($r.items -join '') -match '37821') 'wireless debug port missing'
$script:scenario='hidden-port';$r=Get-SuiLianUsbReport -Adb $fakeAdb
Assert (($r.items -join '') -match '已开启：无线调试') 'enabled wireless debugging must not be reported as disabled when its port is hidden'
$script:scenario='off';$r=Get-SuiLianUsbReport -Adb $fakeAdb
Assert (($r.items -join '') -match '待开启') 'disabled wireless debugging lacks guidance'
$script:calls.Clear();$script:scenario='unauthorized';$r=Get-SuiLianUsbReport -Adb $fakeAdb
Assert (-not $r.ready) 'unauthorized USB must not continue'
Assert ($script:calls.Count -eq 2) 'unauthorized phone must not receive shell commands'
Assert (-not ($script:calls -match 'install|settings put|tcpip|reboot|am start')) 'preflight must be read-only'
Write-Output 'PASS USB ready, unauthorized, wireless-off guidance; fake transport only, no phone access'
