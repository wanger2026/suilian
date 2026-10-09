# Pair only the uniquely selected, USB-authorized phone. Secrets stay in memory.
function Get-SuiLianSettingsNodes {
    param([string]$Xml)
    $start=$Xml.IndexOf('<?xml'); $end=$Xml.LastIndexOf('</hierarchy>')
    if($start -lt 0 -or $end -lt $start){throw '无法读取手机设置界面，请解锁手机后重试。'}
    $document=[xml]$Xml.Substring($start,$end+12-$start)
    $nodes=@($document.SelectNodes('//node'))
    if(-not ($nodes | Where-Object { $_.package -eq 'com.android.settings' })){throw '手机不在系统设置界面，请关闭遮挡窗口后重试。'}
    return @($nodes | Where-Object { $_.package -eq 'com.android.settings' })
}
function Get-SuiLianPairFields {
    param($Nodes)
    $codes=@($Nodes | ForEach-Object { [string]$_.text } | Where-Object {$_ -match '^\d{6}$'} | Select-Object -Unique)
    $ports=@($Nodes | ForEach-Object { [string]$_.text } | Where-Object {$_ -match '^(?:\d{1,3}\.){3}\d{1,3}:(\d{4,5})$'} | ForEach-Object {[int]($_.Split(':')[-1])} | Select-Object -Unique)
    if($codes.Count -ne 1 -or $ports.Count -ne 1 -or $ports[0] -lt 1024 -or $ports[0] -gt 65535){throw '未识别到唯一配对弹窗，请保持“使用配对码配对设备”窗口打开后重试。'}
    return @{code=$codes[0];port=$ports[0]}
}
function Connect-SuiLianWirelessDebug {
    param([string]$Serial,[string]$AdbPath,[scriptblock]$Usb)
    $state=Invoke-RestMethod 'http://127.0.0.1:17881/development-status' -TimeoutSec 3
    if($state.active){throw 'AI 联调正在使用，请先关闭本次联调，再做首次无线配对。'}
    $enabled=([string](& $Usb @('-s',$Serial,'shell','settings','get','global','adb_wifi_enabled'))).Trim()
    if($enabled -ne '1'){
        [void](& $Usb @('-s',$Serial,'shell','am','start','-a','android.settings.APPLICATION_DEVELOPMENT_SETTINGS'))
        throw '已打开开发者选项。请在手机开启“无线调试”并允许当前 Wi-Fi，然后再点“无线配对”。'
    }
    # Stream XML directly to stdout: never persist a pairing-code screenshot/XML.
    function Read-Settings {Get-SuiLianSettingsNodes ([string](& $Usb @('-s',$Serial,'exec-out','uiautomator','dump','/proc/self/fd/1')))}
    function Tap-Setting($nodes,$labels){
        $found=@($nodes | Where-Object { $_.text -in $labels })
        if($found.Count -ne 1 -or $found[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw '设置入口不唯一，请在手机打开无线调试页面后重试。'}
        $x=[int](([int]$Matches[1]+[int]$Matches[3])/2);$y=[int](([int]$Matches[2]+[int]$Matches[4])/2)
        [void](& $Usb @('-s',$Serial,'shell','input','tap',"$x","$y"))
    }
    $pairLabels=@('使用配对码配对设备','Pair device with pairing code')
    $nodes=$null
    try {$nodes=Read-Settings} catch {}
    if(-not ($nodes | Where-Object {$_.text -in $pairLabels})){
        [void](& $Usb @('-s',$Serial,'shell','am','start','-a','android.settings.APPLICATION_DEVELOPMENT_SETTINGS'))
        for($attempt=0;$attempt -lt 8;$attempt++){
            $nodes=Read-Settings
            if($nodes | Where-Object {$_.text -in @('无线调试','Wireless debugging')}){Tap-Setting $nodes @('无线调试','Wireless debugging');break}
            $size=([string](& $Usb @('-s',$Serial,'shell','wm','size')))
            if($size -notmatch '(\d+)x(\d+)'){throw '无法识别手机显示区域。'}
            $x=[int]([int]$Matches[1]/2);$y1=[int]([int]$Matches[2]*.78);$y2=[int]([int]$Matches[2]*.35)
            [void](& $Usb @('-s',$Serial,'shell','input','swipe',"$x","$y1","$x","$y2",'300'))
        }
        $nodes=Read-Settings
    }
    Tap-Setting $nodes $pairLabels
    $fields=Get-SuiLianPairFields (Read-Settings)
    $forwardPort=0
    $keyDirectory=Join-Path $env:LOCALAPPDATA 'WangChuanLink\development-adb'
    [void][IO.Directory]::CreateDirectory($keyDirectory)
    $acl=[Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    foreach($identity in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){
        $sid=[Security.Principal.SecurityIdentifier]::new($identity)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    }
    Set-Acl -LiteralPath $keyDirectory -AclObject $acl
    function Invoke-IsolatedAdb([string]$arguments,[string]$secret=''){
        $p=[Diagnostics.Process]::new()
        try {
            $p.StartInfo=[Diagnostics.ProcessStartInfo]::new($AdbPath,'-P 17885 '+$arguments)
            $p.StartInfo.UseShellExecute=$false;$p.StartInfo.CreateNoWindow=$true
            $p.StartInfo.RedirectStandardInput=$true;$p.StartInfo.RedirectStandardOutput=$true;$p.StartInfo.RedirectStandardError=$true
            foreach($key in @($p.StartInfo.EnvironmentVariables.Keys)){if($key -like 'ADB_*' -or $key -eq 'ANDROID_ADB_SERVER_PORT' -or $key -eq 'ANDROID_USER_HOME'){$p.StartInfo.EnvironmentVariables.Remove($key)}}
            $p.StartInfo.EnvironmentVariables['ANDROID_USER_HOME']=$keyDirectory
            $p.StartInfo.EnvironmentVariables['ADB_MDNS_AUTO_CONNECT']='0'
            [void]$p.Start();$out=$p.StandardOutput.ReadToEndAsync();$err=$p.StandardError.ReadToEndAsync()
            if($secret){$p.StandardInput.WriteLine($secret)};$p.StandardInput.Close()
            if(-not $p.WaitForExit(20000)){try{$p.Kill()}catch{};throw '无线配对超时，请重新打开配对弹窗后重试。'}
            if($p.ExitCode -ne 0){throw '无线配对失败，请确认手机弹窗仍在打开。'}
            return $out.Result
        } finally {$p.Dispose()}
    }
    try {
        $forward=([string](& $Usb @('-s',$Serial,'forward','tcp:0',('tcp:'+$fields.port)))).Trim()
        if($forward -notmatch '^\d{4,5}$'){throw 'USB 配对通道建立失败。'}
        $forwardPort=[int]$forward
        $result=Invoke-IsolatedAdb ('pair 127.0.0.1:'+$forwardPort) $fields.code
        if($result -notmatch 'Successfully paired'){throw '系统尚未确认配对成功，请重试。'}
        return '无线配对已完成。以后手机进入远控 → AI 联调，自动识别连接端口后勾选本次授权即可；配对码可留空。退出远控会关闭联调。'
    } finally {
        $fields.code='';$fields=$null;$result=$null
        if($forwardPort){[void](& $Usb @('-s',$Serial,'forward','--remove',('tcp:'+$forwardPort)))}
        try {[void](Invoke-IsolatedAdb 'kill-server')}catch{}
    }
}
