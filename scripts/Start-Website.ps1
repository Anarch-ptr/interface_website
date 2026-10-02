[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$frontendRoot = Join-Path $projectRoot 'frontend'
$runtimeRoot = Join-Path $projectRoot '.filamentvision-runtime'
$backendPidFile = Join-Path $runtimeRoot 'backend.pid'
$frontendPidFile = Join-Path $runtimeRoot 'frontend.pid'
$errorFile = Join-Path $runtimeRoot 'last-error.txt'
$frontendUrl = 'http://127.0.0.1:5173/'
$backendProxyUrl = 'http://127.0.0.1:5173/api/v1/devices/simulator-1/state'
$backendMarker = 'Run-Backend.cmd'
$frontendMarker = 'Run-Frontend.cmd'

New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
Remove-Item -LiteralPath $errorFile -Force -ErrorAction SilentlyContinue

function Get-OwnedLauncherProcess {
    param(
        [Parameter(Mandatory)] [string] $PidFile,
        [Parameter(Mandatory)] [string] $Marker
    )

    if (-not (Test-Path -LiteralPath $PidFile)) {
        return $null
    }

    $savedPid = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $PidFile -Raw).Trim(), [ref] $savedPid)) {
        Remove-Item -LiteralPath $PidFile -Force
        return $null
    }

    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $savedPid" -ErrorAction SilentlyContinue
    if ($null -eq $process -or $process.Name -ne 'cmd.exe' -or $process.CommandLine -notlike "*$Marker*") {
        Remove-Item -LiteralPath $PidFile -Force
        return $null
    }

    return $process
}

function Test-HttpEndpoint {
    param([Parameter(Mandatory)] [string] $Uri)

    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    } catch {
        return $false
    }
}

function Test-ListeningPort {
    param([Parameter(Mandatory)] [int] $Port)

    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Get-JavaMajorVersion {
    param([Parameter(Mandatory)] [string] $JavaHome)

    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) {
        return 0
    }
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $javaExecutable
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $versionText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $process.Dispose()
    if ($versionText -match 'version\s+"(?:1\.)?(\d+)') {
        return [int] $Matches[1]
    }
    return 0
}

function Find-GradleJavaHome {
    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($candidate in @($env:JAVA_HOME, $env:STUDIO_JDK, 'C:\Program Files\Android\Android Studio\jbr')) {
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            $candidates.Add($candidate)
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
        $gradleJdks = Join-Path $env:GRADLE_USER_HOME 'jdks'
        if (Test-Path -LiteralPath $gradleJdks) {
            Get-ChildItem -LiteralPath $gradleJdks -Filter java.exe -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
                $candidates.Add($_.Directory.Parent.FullName)
            }
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ((Get-JavaMajorVersion -JavaHome $candidate) -ge 17) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw 'Backend requires JDK 17 or newer. Configure JAVA_HOME, STUDIO_JDK, or a Gradle-managed JDK before starting FilamentVision.'
}

function Get-LauncherPid {
    param([Parameter(Mandatory)] $Process)

    if ($null -ne $Process.PSObject.Properties['ProcessId']) {
        return [int] $Process.ProcessId
    }
    return [int] $Process.Id
}

function Start-LauncherWindow {
    param(
        [Parameter(Mandatory)] [string] $ScriptPath,
        [Parameter(Mandatory)] [string] $WorkingDirectory,
        [Parameter(Mandatory)] [string] $PidFile
    )

    $process = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/k', "`"$ScriptPath`"") -WorkingDirectory $WorkingDirectory -PassThru
    Set-Content -LiteralPath $PidFile -Value $process.Id -NoNewline
    return $process
}

$startedBackend = $false
$startedFrontend = $false

try {
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'package.json'))) {
        throw "Missing frontend/package.json under $projectRoot."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules'))) {
        throw "Frontend dependencies are not installed. Run 'npm ci' in $frontendRoot first."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'gradlew.bat'))) {
        throw "Missing gradlew.bat under $projectRoot."
    }

    $backendProcess = Get-OwnedLauncherProcess -PidFile $backendPidFile -Marker $backendMarker
    if ($null -eq $backendProcess) {
        if (Test-ListeningPort -Port 18080) {
            throw 'Port 18080 is already used by a process not owned by this launcher. Stop it or change its configuration before starting FilamentVision.'
        }
        $javaHome = Find-GradleJavaHome
        $env:JAVA_HOME = $javaHome
        $env:Path = "$javaHome\bin;$env:Path"
        $backendProcess = Start-LauncherWindow -ScriptPath (Join-Path $PSScriptRoot 'Run-Backend.cmd') -WorkingDirectory $projectRoot -PidFile $backendPidFile
        $startedBackend = $true
        Write-Host "Started backend launcher (PID $(Get-LauncherPid $backendProcess), Java $javaHome)."
    } else {
        Write-Host "Backend launcher is already running (PID $(Get-LauncherPid $backendProcess))."
    }

    $frontendProcess = Get-OwnedLauncherProcess -PidFile $frontendPidFile -Marker $frontendMarker
    if ($null -eq $frontendProcess) {
        if (Test-ListeningPort -Port 5173) {
            throw 'Port 5173 is already used by a process not owned by this launcher. Stop it or change its configuration before starting FilamentVision.'
        }
        $frontendProcess = Start-LauncherWindow -ScriptPath (Join-Path $PSScriptRoot 'Run-Frontend.cmd') -WorkingDirectory $frontendRoot -PidFile $frontendPidFile
        $startedFrontend = $true
        Write-Host "Started frontend launcher (PID $(Get-LauncherPid $frontendProcess))."
    } else {
        Write-Host "Frontend launcher is already running (PID $(Get-LauncherPid $frontendProcess))."
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    $frontendReady = $false
    $backendReady = $false
    do {
        $frontendReady = Test-HttpEndpoint -Uri $frontendUrl
        if ($frontendReady) {
            $backendReady = Test-HttpEndpoint -Uri $backendProxyUrl
        }
        if ($frontendReady -and $backendReady) {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    if (-not $frontendReady) {
        throw "Frontend did not become reachable at $frontendUrl within 60 seconds."
    }
    if (-not $backendReady) {
        throw "Backend did not become reachable through the frontend proxy at $backendProxyUrl within 60 seconds."
    }

    Write-Host "FilamentVision is ready: $frontendUrl"
    Start-Process $frontendUrl
    exit 0
} catch {
    $message = $_.Exception.Message
    Set-Content -LiteralPath $errorFile -Value $message

    if ($startedFrontend -and $null -ne $frontendProcess) {
        & taskkill.exe /PID $frontendProcess.Id /T /F 2>$null | Out-Null
        Remove-Item -LiteralPath $frontendPidFile -Force -ErrorAction SilentlyContinue
    }
    if ($startedBackend -and $null -ne $backendProcess) {
        & taskkill.exe /PID $backendProcess.Id /T /F 2>$null | Out-Null
        Remove-Item -LiteralPath $backendPidFile -Force -ErrorAction SilentlyContinue
    }
    [Console]::Error.WriteLine($message)
    exit 1
}
