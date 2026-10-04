[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Read-ProjectFile([string] $relativePath) {
    Get-Content -LiteralPath (Join-Path $projectRoot $relativePath) -Raw
}

function Assert-Contains([string] $actual, [string] $expected, [string] $message) {
    if (-not $actual.Contains($expected)) { throw "$message Missing: $expected" }
}

function Assert-NotContains([string] $actual, [string] $unexpected, [string] $message) {
    if ($actual.Contains($unexpected)) { throw "$message Unexpected: $unexpected" }
}

$backend = Read-ProjectFile 'scripts\Run-Backend.cmd'
$frontend = Read-ProjectFile 'scripts\Run-Frontend.cmd'
$start = Read-ProjectFile 'scripts\Start-Website.ps1'
$stop = Read-ProjectFile 'scripts\Stop-Website.ps1'
$startBat = Read-ProjectFile 'start-website.bat'
$stopBat = Read-ProjectFile 'stop-website.bat'
$vbs = Read-ProjectFile 'Open FilamentVision.vbs'

Assert-Contains $backend ':backend:run' 'Backend must use the Gradle application task.'
Assert-NotContains $backend '--scenario' 'Production launcher must not pass deleted simulator arguments.'
Assert-Contains $frontend 'npm.cmd run dev' 'Frontend must bypass npm.ps1 command resolution.'

Assert-Contains $start 'http://127.0.0.1:18080/api/v1/health' 'Launcher must probe Backend directly.'
Assert-Contains $start 'http://127.0.0.1:5173/' 'Launcher must probe the Frontend root independently.'
Assert-Contains $start 'http://127.0.0.1:5173/api/v1/health' 'Launcher must probe health through Vite.'
Assert-NotContains $start 'simulator-1' 'Readiness must not depend on a deleted simulator device.'
Assert-Contains $start 'FilamentVision.WebsiteLauncher' 'Launcher must serialize concurrent double-click attempts.'
Assert-Contains $start 'backend.log' 'Backend failures must have a component log.'
Assert-Contains $start 'frontend.log' 'Frontend failures must have a component log.'
Assert-Contains $start 'launcher.log' 'Readiness transitions must have a launcher log.'
Assert-Contains $start 'call `"$ScriptPath`" `"$LogPath`"' 'CMD /c must use call so quoted script and log paths execute reliably.'

Assert-Contains $startBat '-NoProfile' 'Start wrapper must not load the user PowerShell profile.'
Assert-Contains $stopBat '-NoProfile' 'Stop wrapper must not load the user PowerShell profile.'
Assert-NotContains "$start`n$stop" 'taskkill.exe /IM' 'Stop logic must never kill Java or Node by image name.'

$dialogCount = ([regex]::Matches($vbs, '(?im)^\s*MsgBox\b')).Count
if ($dialogCount -ne 1) { throw "Only the outer VBS may show a GUI error dialog; found $dialogCount MsgBox calls." }

Write-Output 'LAUNCHER_CONTRACT PASS'
