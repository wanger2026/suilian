$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot = Join-Path $sourceRoot '.build\desktop-tests'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$testExe = Join-Path $testRoot 'ManagerTests.exe'
Push-Location $sourceRoot
try {
    & $compiler /nologo /define:TESTING /main:ManagerLifetimeTests "/out:$testExe" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll desktop\Manager.cs desktop\ManagerLayout.cs desktop\HistoryView.cs desktop\Branding.cs desktop\ProcessLifetime.cs tests\ManagerLifetimeTests.cs
    if ($LASTEXITCODE -ne 0) { throw 'Desktop test compilation failed.' }
    & $testExe
    if ($LASTEXITCODE -ne 0) { throw 'Desktop tests failed.' }
} finally { Pop-Location }
