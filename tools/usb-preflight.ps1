function Get-SuiLianUsbReport {
    param([Parameter(Mandatory=$true)][scriptblock]$Adb)
    $items = [Collections.Generic.List[string]]::new()
    $serial = ([string](& $Adb @('-d','get-serialno'))).Trim()
    if (-not $serial -or $serial -match '(?i)(error|unknown|unauthorized|offline|\s)') {
        $devices = [string](& $Adb @('devices','-l'))
        if ($devices -match '\sunauthorized\b') {
            $items.Add('待确认：请解锁手机，在“允许 USB 调试”提示中允许此电脑，然后重新检查。')
        } elseif ($devices -match '\soffline\b') {
            $items.Add('待连接：手机调试通道离线，请重新插拔 USB 数据线并解锁。')
        } else {
            $items.Add('未找到唯一的 USB 手机。请只接一部手机，使用支持数据传输的线缆，并开启“开发者选项 → USB 调试”。')
        }
        return [pscustomobject]@{ ready=$false; serial=''; items=$items.ToArray() }
    }
    $state = ([string](& $Adb @('-s',$serial,'get-state'))).Trim()
    if ($state -ne 'device') {
        $items.Add('USB 设备尚未完成系统授权，请解锁手机确认后重新检查。')
        return [pscustomobject]@{ ready=$false; serial=''; items=$items.ToArray() }
    }
    $model = ([string](& $Adb @('-s',$serial,'shell','getprop','ro.product.model'))).Trim()
    $sdkText = ([string](& $Adb @('-s',$serial,'shell','getprop','ro.build.version.sdk'))).Trim()
    $sdk=0; [void][int]::TryParse($sdkText,[ref]$sdk)
    $items.Add('已通过：USB 调试已授权，设备 '+$model+'。')
    $package = [string](& $Adb @('-s',$serial,'shell','dumpsys','package','cn.wangchuan.link.test'))
    $version = [regex]::Match($package,'versionName=([^\s]+)')
    if ($version.Success) { $items.Add('已安装：随连 '+$version.Groups[1].Value+'。') }
    else { $items.Add('待安装：未检测到随连测试版，可点击“安装并检查”。') }
    if ($sdk -lt 30) {
        $items.Add('不支持：远程真机联调需要 Android 11 或更高版本；USB 本地调试仍可使用。')
    } else {
        $wireless = ([string](& $Adb @('-s',$serial,'shell','settings','get','global','adb_wifi_enabled'))).Trim()
        $portText = ([string](& $Adb @('-s',$serial,'shell','getprop','service.adb.tls.port'))).Trim()
        $port=0; [void][int]::TryParse($portText,[ref]$port)
        if ($wireless -eq '1' -and $port -ge 1024 -and $port -le 65535) {
            $items.Add('已通过：无线调试已开启，当前连接端口 '+$port+'。首次配对仍需手机显示的六位配对码。')
        } elseif ($wireless -eq '1') {
            $items.Add('已开启：无线调试。系统未通过 USB 提供连接端口，请在手机“无线调试”页核对“IP 地址和端口”；若显示未连接 Wi-Fi，请先完成网络授权。')
        } else {
            $items.Add('待开启：手机连接 Wi-Fi 后，打开“开发者选项 → 无线调试”，允许当前网络。仅移动网络时此系统功能可能不可用。')
        }
    }
    $items.Add('AI 联调：手机进入已配对电脑的远控画面后，再授权本次联调。检查通过不代表已授权远程 ADB。')
    $items.Add('若系统拦截安装或模拟点击，请按当次提示开启“USB 安装”或“USB 调试（安全设置）”；本工具不擅自修改这些权限。')
    return [pscustomobject]@{ ready=$true; serial=$serial; items=$items.ToArray() }
}
