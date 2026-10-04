[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$frontendRoot = Join-Path $projectRoot 'frontend'
$runtimeRoot = Join-Path $projectRoot '.filamentvision-runtime'
$logRoot = Join-Path $projectRoot 'logs'
$backendPidFile = Join-Path $runtimeRoot 'backend.pid'
$frontendPidFile = Join-Path $runtimeRoot 'frontend.pid'
$errorFile = Join-Path $runtimeRoot 'last-error.txt'
$launcherLog = Join-Path $logRoot 'launcher.log'
$backendLog = Join-Path $logRoot 'backend.log'
$frontendLog = Join-Path $logRoot 'frontend.log'
$backendHealthUrl = 'http://127.0.0.1:18080/api/v1/health'
$frontendUrl = 'http://127.0.0.1:5173/'
$proxyHealthUrl = 'http://127.0.0.1:5173/api/v1/health'
$backendMarker = 'Run-Backend.cmd'
$frontendMarker = 'Run-Frontend.cmd'
$deadline = [DateTime]::UtcNow.AddSeconds(60)

New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
Remove-Item -LiteralPath $errorFile -Force -ErrorAction SilentlyContinue
Set-Content -LiteralPath $launcherLog -Value "[$([DateTime]::Now.ToString('s'))] launcher started; root=$projectRoot" -Encoding UTF8

function Write-LauncherLog([string] $Message) {
    Add-Content -LiteralPath $launcherLog -Value "[$([DateTime]::Now.ToString('s'))] $Message" -Encoding UTF8
}

function Get-LauncherPid($Process) {
    if ($null -ne $Process.PSObject.Properties['ProcessId']) { return [int] $Process.ProcessId }
    return [int] $Process.Id
}

function Get-OwnedLauncherProcess {
    param([string] $PidFile, [string] $Marker)
    if (-not (Test-Path -LiteralPath $PidFile)) { return $null }
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

function Test-Http200([string] $Uri) {
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2
        return $response.StatusCode -eq 200
    } catch { return $false }
}

function Test-ListeningPort([int] $Port) {
    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Test-ProcessAlive($Process) {
    if ($null -eq $Process) { return $false }
    return $null -ne (Get-Process -Id (Get-LauncherPid $Process) -ErrorAction SilentlyContinue)
}

function Get-JavaMajorVersion([string] $JavaHome) {
    $javaExecutable = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { return 0 }
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
    if ($versionText -match 'version\s+"(?:1\.)?(\d+)') { return [int] $Matches[1] }
    return 0
}

function Find-GradleJavaHome {
    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($candidate in @($env:JAVA_HOME, $env:STUDIO_JDK, 'C:\Program Files\Android\Android Studio\jbr')) {
        if (-not [string]::IsNullOrWhiteSpace($candidate)) { $candidates.Add($candidate) }
    }
    foreach ($gradleRoot in @($env:GRADLE_USER_HOME, (Join-Path $env:USERPROFILE '.gradle'))) {
        if ([string]::IsNullOrWhiteSpace($gradleRoot)) { continue }
        $gradleJdks = Join-Path $gradleRoot 'jdks'
        if (Test-Path -LiteralPath $gradleJdks) {
            Get-ChildItem -LiteralPath $gradleJdks -Filter java.exe -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
                $candidates.Add($_.Directory.Parent.FullName)
            }
        }
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ((Get-JavaMajorVersion $candidate) -ge 17) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw 'JDK not found or incompatible. Backend requires JDK 17 or newer; see logs\launcher.log.'
}

function Start-LauncherWindow {
    param([string] $ScriptPath, [string] $WorkingDirectory, [string] $PidFile, [string] $LogPath)
    Set-Content -LiteralPath $LogPath -Value "[$([DateTime]::Now.ToString('s'))] workingDirectory=$WorkingDirectory" -Encoding UTF8
    $arguments = @('/d', '/c', "call `"$ScriptPath`" `"$LogPath`"")
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList $arguments -WorkingDirectory $WorkingDirectory -PassThru
    Set-Content -LiteralPath $PidFile -Value $process.Id -NoNewline
    return $process
}

function Wait-ForHttp {
    param([string] $Uri, $OwnedProcess, [string] $ProcessFailure, [string] $TimeoutFailure)
    do {
        if (Test-Http200 $Uri) { return }
        if ($null -ne $OwnedProcess -and -not (Test-ProcessAlive $OwnedProcess)) { throw $ProcessFailure }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw $TimeoutFailure
}

function Stop-StartedTree($Process, [string] $PidFile, [string] $Name) {
    if ($null -eq $Process) { return }
    $pidValue = Get-LauncherPid $Process
    & taskkill.exe /PID $pidValue /T /F 2>$null | Out-Null
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    Write-LauncherLog "cleanup $Name pid=$pidValue exit=$LASTEXITCODE"
}

$mutex = [System.Threading.Mutex]::new($false, 'Local\FilamentVision.WebsiteLauncher')
$ownsMutex = $false
$startedBackend = $false
$startedFrontend = $false
$backendProcess = $null
$frontendProcess = $null

try {
    try { $ownsMutex = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) {
        Write-LauncherLog 'another launcher attempt is already running; this duplicate exits successfully'
        exit 0
    }

    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'package.json'))) { throw "Missing frontend/package.json under $projectRoot." }
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules'))) { throw "Frontend dependencies are not installed. Run 'npm.cmd ci' in $frontendRoot first." }
    if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'npm.cmd not found. Install Node.js and ensure npm.cmd is on PATH.' }
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'gradlew.bat'))) { throw "Missing gradlew.bat under $projectRoot." }

    $backendProcess = Get-OwnedLauncherProcess $backendPidFile $backendMarker
    if (Test-Http200 $backendHealthUrl) {
        Write-LauncherLog "BACKEND_HTTP_READY reused; url=$backendHealthUrl pid=$(if($backendProcess){Get-LauncherPid $backendProcess}else{'external'})"
    } else {
        if ($null -eq $backendProcess) {
            if (Test-ListeningPort 18080) { throw "Backend port already in use but health failed at $backendHealthUrl. See $backendLog." }
            $javaHome = Find-GradleJavaHome
            $env:JAVA_HOME = $javaHome
            $env:Path = "$javaHome\bin;$env:Path"
            Write-LauncherLog "STARTING_BACKEND executable=gradlew.bat javaHome=$javaHome workingDirectory=$projectRoot port=18080"
            $backendProcess = Start-LauncherWindow (Join-Path $PSScriptRoot 'Run-Backend.cmd') $projectRoot $backendPidFile $backendLog
            $startedBackend = $true
            Write-LauncherLog "BACKEND_PROCESS_STARTED pid=$(Get-LauncherPid $backendProcess)"
        }
        Wait-ForHttp $backendHealthUrl $backendProcess `
            "Backend process failed to start. See $backendLog." `
            "Backend health endpoint did not become ready at $backendHealthUrl within 60 seconds. See $backendLog."
        Write-LauncherLog "BACKEND_HTTP_READY url=$backendHealthUrl"
    }

    $frontendProcess = Get-OwnedLauncherProcess $frontendPidFile $frontendMarker
    if (Test-Http200 $frontendUrl) {
        Write-LauncherLog "FRONTEND_HTTP_READY reused; url=$frontendUrl pid=$(if($frontendProcess){Get-LauncherPid $frontendProcess}else{'external'})"
    } else {
        if ($null -eq $frontendProcess) {
            if (Test-ListeningPort 5173) { throw "Frontend port already in use but HTTP readiness failed at $frontendUrl. See $frontendLog." }
            Write-LauncherLog "STARTING_FRONTEND executable=npm.cmd workingDirectory=$frontendRoot port=5173"
            $frontendProcess = Start-LauncherWindow (Join-Path $PSScriptRoot 'Run-Frontend.cmd') $frontendRoot $frontendPidFile $frontendLog
            $startedFrontend = $true
            Write-LauncherLog "FRONTEND_PROCESS_STARTED pid=$(Get-LauncherPid $frontendProcess)"
        }
        Wait-ForHttp $frontendUrl $frontendProcess `
            "Frontend process failed to start. See $frontendLog." `
            "Frontend failed to start at $frontendUrl within 60 seconds. See $frontendLog."
        Write-LauncherLog "FRONTEND_HTTP_READY url=$frontendUrl"
    }

    if (-not (Test-Http200 $proxyHealthUrl)) {
        throw "Frontend proxy cannot reach Backend at $proxyHealthUrl although direct Backend and Frontend checks passed. See $frontendLog and $backendLog."
    }
    Write-LauncherLog "PROXY_READY url=$proxyHealthUrl"
    Write-LauncherLog "OPEN_BROWSER url=$frontendUrl"
    Start-Process $frontendUrl
    exit 0
} catch {
    $message = $_.Exception.Message
    Set-Content -LiteralPath $errorFile -Value $message -Encoding UTF8
    Write-LauncherLog "FAILED $message"
    if ($startedFrontend) { Stop-StartedTree $frontendProcess $frontendPidFile 'frontend' }
    if ($startedBackend) { Stop-StartedTree $backendProcess $backendPidFile 'backend' }
    [Console]::Error.WriteLine($message)
    exit 1
} finally {
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
