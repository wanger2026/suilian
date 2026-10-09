$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Windows .NET Framework 4.x compiler is required.' }
$managerBuildRoot = if ($env:SUILIAN_WORK) { Join-Path $env:SUILIAN_WORK 'desktop' } else { Join-Path $sourceRoot '.build\desktop' }
New-Item -ItemType Directory -Force -Path $managerBuildRoot,(Join-Path $sourceRoot 'releases') | Out-Null
$iconTool = Join-Path $managerBuildRoot 'BrandIcon.exe'
$iconFile = Join-Path $managerBuildRoot 'suilian.ico'
Push-Location $sourceRoot
try {
    & $compiler /nologo /define:BUILD_ICON "/out:$iconTool" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll desktop\Branding.cs
    if ($LASTEXITCODE -ne 0) { throw 'Icon build failed.' }
    & $iconTool $iconFile
    if ($LASTEXITCODE -ne 0) { throw 'Icon generation failed.' }
    & $compiler /nologo /target:winexe "/win32icon:$iconFile" /out:releases\WangChuanManager.exe /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll desktop\Manager.cs desktop\ManagerLayout.cs desktop\HistoryView.cs desktop\Branding.cs desktop\ProcessLifetime.cs
    if ($LASTEXITCODE -ne 0) { throw 'Manager build failed.' }
} finally { Pop-Location }
