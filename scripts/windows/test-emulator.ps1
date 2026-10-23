#requires -Version 5.1
<#
.SYNOPSIS
    Smoke-tests the PoleX APK on a Dockerized Android emulator (Windows).

.DESCRIPTION
    Pipeline:
      1. build the debug APK (if missing or -Build)
      2. start the backend (skipped if already answering on :8080)
      3. boot an Android emulator in Docker (Docker Desktop + WSL2, KVM)
      4. run the shared in-container pipeline (scripts\emulator-pipeline.sh,
         mounted at /pipeline by android\docker-compose.emulator.yml):
         wait for boot, install the APK, launch the app, verify window
         focus + no fatal crashes, capture a screenshot
      5. copy the screenshot out to android\dist

    The boot/install/verify logic lives in scripts\emulator-pipeline.sh so
    the bash and PowerShell entry points drive the same single
    implementation (no drift on the part that actually drives the
    emulator).

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
$ApkDir = Join-Path $AndroidDir "dist"
$Compose = Join-Path $AndroidDir "docker-compose.emulator.yml"
$Apk = if ($ApkPath) { $ApkPath } else { Join-Path $ApkDir "polex-debug.apk" }
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

# Container mounts .\dist at /apk (read-only); copy a custom APK in if needed.
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
Write-Host "==> Emulator container up; running the shared smoke pipeline in it" -ForegroundColor Cyan

# --- 4. shared in-container pipeline -------------------------------------------
# The same scripts\emulator-pipeline.sh the bash entry point execs: it waits
# for boot, installs /apk/<name>, launches the app, checks window focus and
# fatal logcat lines, and captures a screenshot to /tmp/screenshot.png.
# (No output redirection: captured stderr would become terminating error
# records under $ErrorActionPreference = "Stop" in PowerShell 5.1.)
docker compose -f $Compose exec -T emulator sh /pipeline/emulator-pipeline.sh $ApkBase
$failed = if ($LASTEXITCODE -ne 0) { 1 } else { 0 }

# --- 5. screenshot ---------------------------------------------------------------
# The pipeline wrote it to /tmp/screenshot.png inside the container; adb
# exec-out emits binary PNG and PowerShell capture mangles it, so redirect
# at the cmd level where the stream stays byte-exact.
$shot = Join-Path $ApkDir ("screenshot-{0}.png" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
Remove-Item $shot -ErrorAction SilentlyContinue
cmd /c "docker compose -f `"$Compose`" exec -T emulator cat /tmp/screenshot.png > `"$shot`" 2>nul"
if (Test-Path $shot -and (Get-Item $shot).Length -gt 0) {
    Write-Host "PASS: screenshot saved: $shot" -ForegroundColor Green
}
else {
    Remove-Item $shot -ErrorAction SilentlyContinue
    Write-Host "WARN: no screenshot captured" -ForegroundColor Yellow
}

$Cleanup.Invoke()

if ($Keep) {
    Write-Host "Emulator left running. Useful commands:"
    Write-Host "  docker compose -f android\docker-compose.emulator.yml exec emulator /android/sdk/platform-tools/adb shell"
    Write-Host "  docker compose -f android\docker-compose.emulator.yml down"
}
exit $failed
