[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $projectRoot '.filamentvision-runtime'
$logRoot = Join-Path $projectRoot 'logs'
$launcherLog = Join-Path $logRoot 'launcher.log'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
function Write-StopLog([string] $Message) {
    Add-Content -LiteralPath $launcherLog -Value "[$([DateTime]::Now.ToString('s'))] STOP $Message" -Encoding UTF8
}
$targets = @(
    @{ Name = 'frontend'; PidFile = (Join-Path $runtimeRoot 'frontend.pid'); Marker = 'Run-Frontend.cmd' },
    @{ Name = 'backend'; PidFile = (Join-Path $runtimeRoot 'backend.pid'); Marker = 'Run-Backend.cmd' }
)
$failed = $false

foreach ($target in $targets) {
    if (-not (Test-Path -LiteralPath $target.PidFile)) {
        Write-Host "$($target.Name) has no launcher PID file; nothing to stop."
        Write-StopLog "$($target.Name) no owned PID"
        continue
    }

    $savedPid = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $target.PidFile -Raw).Trim(), [ref] $savedPid)) {
        Write-Warning "Ignoring invalid $($target.Name) PID file."
        Remove-Item -LiteralPath $target.PidFile -Force
        $failed = $true
        continue
    }

    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $savedPid" -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Write-Host "$($target.Name) launcher is already stopped."
        Remove-Item -LiteralPath $target.PidFile -Force
        Write-StopLog "$($target.Name) already stopped pid=$savedPid"
        continue
    }
    if ($process.Name -ne 'cmd.exe' -or $process.CommandLine -notlike "*$($target.Marker)*") {
        Write-Warning "PID $savedPid no longer belongs to the FilamentVision $($target.Name) launcher; it was not terminated."
        Remove-Item -LiteralPath $target.PidFile -Force
        Write-StopLog "$($target.Name) refused stale/unowned pid=$savedPid"
        $failed = $true
        continue
    }

    & $env:ComSpec /d /c "taskkill.exe /PID $savedPid /T /F >nul 2>&1"
    $taskkillExit = $LASTEXITCODE
    if ($taskkillExit -ne 0) {
        Write-Warning "Could not stop the $($target.Name) process tree rooted at PID $savedPid."
        Write-StopLog "$($target.Name) stop failed pid=$savedPid"
        $failed = $true
    } else {
        Write-Host "Stopped $($target.Name) process tree rooted at PID $savedPid."
        Remove-Item -LiteralPath $target.PidFile -Force
        Write-StopLog "$($target.Name) stopped pid=$savedPid"
    }
}

if ($failed) { exit 1 }
exit 0
