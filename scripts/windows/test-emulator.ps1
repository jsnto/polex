#requires -Version 5.1
<#
.SYNOPSIS
    Smoke-tests the PoleX APK on a Dockerized Android emulator (Windows).

.DESCRIPTION
    Pipeline:
      1. build the debug APK (if missing or -Build)
      2. start the backend (skipped if already answering on :8080)
      3. boot an Android emulator in Docker (Docker Desktop + WSL2, KVM)
      4. install the APK and launch the app
      5. verify: app window focused, no fatal crashes, grab a screenshot

    Requires Docker Desktop (WSL2 backend) with KVM enabled (it is, by
    default, when Hyper-V/WSL2 is available on the host).

.EXAMPLE
    .\scripts\windows\test-emulator.ps1
    .\scripts\windows\test-emulator.ps1 -Build
    .\scripts\windows\test-emulator.ps1 -Keep
    .\scripts\windows\test-emulator.ps1 -ApkPath C:\some\app.apk
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$Keep,
    [string]$ApkPath
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$AndroidDir = Join-Path $Root "android"
$ApkDir = Join-Path $AndroidDir "apk"
$Compose = Join-Path $AndroidDir "docker-compose.emulator.yml"
$Apk = if ($ApkPath) { $ApkPath } else { Join-Path $ApkDir "polex-debug.apk" }
$Package = "com.polex.app"
$Activity = "com.polex.app.MainActivity"
$BackendUrl = "http://localhost:8080"

function Find-DockerExe {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # Freshly installed Docker Desktop: current session's PATH is stale, so
    # fall back to the standard install location and extend the session PATH.
    foreach ($dir in @("C:\Program Files\Docker\Docker\resources\bin",
                       "$env:ProgramFiles\Docker\Docker\resources\bin")) {
        if (Test-Path (Join-Path $dir "docker.exe")) {
            $env:PATH = "$dir;$env:PATH"
            return (Join-Path $dir "docker.exe")
        }
    }
    return $null
}

function Test-Docker {
    $exe = Find-DockerExe
    if (-not $exe) {
        throw "ERROR: docker.exe not found on PATH or in the standard Docker Desktop install dir (install Docker Desktop)"
    }
    $info = ""
    try { $info = & $exe info 2>&1 | Out-String } catch { $info = "$($_)" }
    if ($LASTEXITCODE -ne 0) {
        throw "ERROR: docker daemon not reachable (start Docker Desktop)`n$info"
    }
}
# The image ships its own adb client+server (emulator runs with
# -skip-adb-auth); use the full path since PATH inside the image is
# not guaranteed to include platform-tools.
function Adb([string[]]$Args_) {
    docker compose -f $Compose exec -T emulator /android/sdk/platform-tools/adb @Args_
}
function Test-BackendUp {
    try {
        $null = curl.exe -sf -m 2 "$BackendUrl/api/health"
        return ($LASTEXITCODE -eq 0)
    } catch { return $false }
}

# --- prerequisites -----------------------------------------------------------
Test-Docker

$Cleanup = {
    if ($script:Keep) { return }
    Write-Host "==> Tearing down" -ForegroundColor Cyan
    # docker compose writes progress to stderr; captured stderr is delivered
    # as error records, which become terminating under
    # $ErrorActionPreference = "Stop" (PowerShell 5.1). Drop the preference
    # for the duration of teardown.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        docker compose -f $script:Compose down --remove-orphans *> $null
        if ($script:BackendJob) {
            Stop-Process -Id $script:BackendJob.Id -Force -ErrorAction SilentlyContinue
        }
    }
    finally { $ErrorActionPreference = $prevEap }
}

# --- 1. APK ------------------------------------------------------------------
if ($Build -or -not (Test-Path $Apk)) {
    Write-Host "==> Building debug APK" -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot "build-apk.ps1") -Task debug
}
if (-not (Test-Path $Apk)) { throw "ERROR: APK not found: $Apk" }
Write-Host "==> APK: $Apk"

# Container mounts .\apk at /apk (read-only); copy a custom APK in if needed.
$ApkBase = Split-Path -Leaf $Apk
$ApkDirOf = Split-Path -Parent $Apk
$ApkDirResolved = $null
if (Test-Path $ApkDir) { $ApkDirResolved = (Resolve-Path $ApkDir).Path }
if (-not $ApkDirResolved -or $ApkDirOf -ne $ApkDirResolved) {
    New-Item -ItemType Directory -Force -Path $ApkDir | Out-Null
    Copy-Item $Apk $ApkDir
    $ApkBase = Split-Path -Leaf $Apk
}

# --- 2. backend ---------------------------------------------------------------
if (Test-BackendUp) {
    Write-Host "==> Backend already running at $BackendUrl"
}
else {
    Write-Host "==> Starting backend (local Go build)" -ForegroundColor Cyan
    if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
        throw "ERROR: need go (or start the backend manually)"
    }
    Set-Location (Join-Path $Root "backend")
    go build -o polex-backend.exe .
    $env:CHECK_INTERVAL = "5s"
    $script:BackendJob = Start-Process -FilePath ".\polex-backend.exe" -PassThru -WindowStyle Hidden
    $up = $false
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 1
        if (Test-BackendUp) { $up = $true; break }
    }
    if (-not $up) {
        Stop-Process -Id $script:BackendJob.Id -Force -ErrorAction SilentlyContinue
        throw "ERROR: backend did not come up"
    }
    Write-Host "==> Backend up (pid $($script:BackendJob.Id))"
}

# --- 3. emulator ---------------------------------------------------------------
Write-Host "==> Booting Android emulator in Docker (first pull can take minutes)" -ForegroundColor Cyan
docker compose -f $Compose up -d
if ($LASTEXITCODE -ne 0) {
    $Cleanup.Invoke()
    throw "ERROR: 'docker compose up -d' failed (see compose output above - usually an image pull failure or /dev/kvm mount problem)"
}
# Fail fast with the container logs if the emulator container is dead/stopped.
# EAP is lowered around the captured-stderr calls (see $Cleanup note).
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $adbVer = Adb "version"
    if (-not $adbVer) {
        Write-Host "--- emulator container state ---" -ForegroundColor Yellow
        docker compose -f $Compose ps -a 2>&1 | Out-Host
        Write-Host "--- emulator container logs (last 50) ---" -ForegroundColor Yellow
        docker compose -f $Compose logs --tail 50 2>&1 | Out-Host
        Write-Host "--- docker daemon info (KVM/virt) ---" -ForegroundColor Yellow
        docker info --format '{{.Name}} {{.NCPU}} CPUs, {{.MemTotal}} total memory, {{.CgroupVersion}}' 2>&1 | Out-Host
    }
}
finally { $ErrorActionPreference = $prevEap }
if (-not $adbVer) {
    $Cleanup.Invoke()
    throw "ERROR: emulator container not responding to adb (see logs above)"
}
Write-Host "==> Waiting for boot (up to ~5 min)"
Adb "wait-for-device"
if ($LASTEXITCODE -ne 0) {
    $Cleanup.Invoke()
    throw "ERROR: 'adb wait-for-device' failed - emulator did not come up"
}
$booted = $false
for ($i = 0; $i -lt 60; $i++) {
    $b = (Adb "shell" "getprop" "sys.boot_completed" | Out-String).Trim()
    if ($b -eq "1") { $booted = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $booted) {
    $Cleanup.Invoke()
    throw "ERROR: emulator did not finish booting"
}
Write-Host "==> Emulator booted"

# --- 4. install + launch ---------------------------------------------------------
Write-Host "==> Installing APK" -ForegroundColor Cyan
Adb "install" "-r" "/apk/$ApkBase"

Write-Host "==> Launching $Activity" -ForegroundColor Cyan
Adb "shell" "am" "start" "-n" $Activity | Out-Null
Start-Sleep -Seconds 5

# --- 5. verify -------------------------------------------------------------------
$failed = 0

$focus = Adb "shell" "dumpsys" "window" | Select-String -Pattern "mCurrentFocus|mFocusedApp" | Select-Object -First 2
Write-Host "--- focused window ---"
$focus
if (-not ($focus -match $Package)) {
    Write-Host "FAIL: app window is not focused (or no focus info)" -ForegroundColor Red; $failed = 1
} else {
    Write-Host "PASS: app window is focused" -ForegroundColor Green
}

$crashes = Adb "logcat" "-d" "-s" "AndroidRuntime:E"
if ($crashes) {
    Write-Host "FAIL: fatal exception in logcat:" -ForegroundColor Red
    $crashes
    $failed = 1
} else {
    Write-Host "PASS: no fatal crashes in logcat" -ForegroundColor Green
}

$shot = Join-Path $ApkDir ("screenshot-{0}.png" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
# adb exec-out emits binary PNG; PowerShell capture mangles it, so redirect
# at the cmd level where the stream stays byte-exact.
Remove-Item $shot -ErrorAction SilentlyContinue
cmd /c "docker compose -f `"$Compose`" exec -T emulator /android/sdk/platform-tools/adb exec-out screencap -p > `"$shot`" 2>nul"
if (Test-Path $shot -and (Get-Item $shot).Length -gt 0) {
    Write-Host "PASS: screenshot saved: $shot" -ForegroundColor Green
} else {
    Remove-Item $shot -ErrorAction SilentlyContinue
    Write-Host "WARN: could not capture screenshot" -ForegroundColor Yellow
}

$Cleanup.Invoke()

Write-Host ""
if ($failed -eq 0) {
    Write-Host "=== SMOKE TEST PASSED ===" -ForegroundColor Green
} else {
    Write-Host "=== SMOKE TEST FAILED ===" -ForegroundColor Red
}
if ($Keep) {
    Write-Host "Emulator left running. Useful commands:"
    Write-Host "  docker compose -f android\docker-compose.emulator.yml exec emulator /android/sdk/platform-tools/adb shell"
    Write-Host "  docker compose -f android\docker-compose.emulator.yml down"
}
exit $failed
