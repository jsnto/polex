#requires -Version 5.1
<#
.SYNOPSIS
    Builds the PoleX Android APK on Windows (Docker-based).

.DESCRIPTION
    Wraps `docker compose up --build apk` from android/docker-compose.yml.
    Docker Desktop for Windows is required. No local JDK/Android SDK needed.
    APKs land in android\apk\.

.PARAMETER Task
    debug (default) or release.

.PARAMETER Method
    docker (default) or local. "local" needs JDK 17 + Android SDK on the
    machine and uses the gradle wrapper.

.EXAMPLE
    .\scripts\windows\build-apk.ps1
    .\scripts\windows\build-apk.ps1 -Task release
    .\scripts\windows\build-apk.ps1 -Task debug -Method local
#>
[CmdletBinding()]
param(
    [ValidateSet("debug", "release")]
    [string]$Task = "debug",

    [ValidateSet("docker", "local")]
    [string]$Method = "docker"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$AndroidDir = Join-Path $Root "android"
$ApkDir = Join-Path $AndroidDir "apk"

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
        throw "ERROR: docker.exe not found on PATH or in the standard Docker Desktop install dir (install Docker Desktop, or use -Method local)"
    }
    $info = ""
    try { $info = & $exe info 2>&1 | Out-String } catch { $info = "$($_)" }
    if ($LASTEXITCODE -ne 0) {
        throw "ERROR: docker daemon not reachable (start Docker Desktop, or use -Method local)`n$info"
    }
}

if ($Method -eq "docker") {
    Test-Docker
    $gradleTask = if ($Task -eq "debug") { "assembleDebug" } else { "assembleRelease" }
    Write-Host "==> Building APK in container (gradle $gradleTask)" -ForegroundColor Cyan
    Set-Location $AndroidDir
    $env:TASKS = $gradleTask
    # Run directly so compose keeps its TTY progress display. On failure,
    # gradle already prints the `e: file:...` compiler lines above.
    # Drop any stale apk-1 container so `up` can't keep reusing it (the old
    # behaviour made stale-source errors persist), then rebuild the image
    # (COPY re-copies changed source; android/.dockerignore keeps stale host
    # build/gradle state out) and start a fresh container from it.
    docker compose down apk 2>$null
    docker compose up --build apk
    if ($LASTEXITCODE -ne 0) { throw "ERROR: docker compose build failed (see the `e:` lines above)" }
}
else {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "ERROR: need Java 17 for local build (jvmTarget 17)"
    }
    Set-Location $AndroidDir
    if (-not (Test-Path (Join-Path $AndroidDir "gradlew.bat"))) {
        Write-Host "==> Creating gradle wrapper (one-time)" -ForegroundColor Cyan
        gradle wrapper --gradle-version 8.7
    }
    $outRel = if ($Task -eq "debug") {
        "app\build\outputs\apk\debug\polex-debug.apk"
    } else {
        "app\build\outputs\apk\release\polex-release.apk"
    }
    Write-Host "==> Building APK locally" -ForegroundColor Cyan
    & ".\gradlew.bat" $Task
    if ($LASTEXITCODE -ne 0) { throw "ERROR: gradle build failed" }
    New-Item -ItemType Directory -Force -Path $ApkDir | Out-Null
    Copy-Item (Join-Path $AndroidDir $outRel) $ApkDir
}

Write-Host ""
$expectedApk = if ($Task -eq "debug") { "polex-debug.apk" } else { "polex-release.apk" }
if (-not (Test-Path (Join-Path $ApkDir $expectedApk))) {
    throw "ERROR: build finished but ${ApkDir}\$expectedApk was not produced"
}
Write-Host "==> APK(s) in ${ApkDir}:" -ForegroundColor Cyan
Get-ChildItem $ApkDir | Format-Table Name, Length, LastWriteTime

$apkName = if ($Task -eq "debug") { "polex-debug.apk" } else { "polex-release.apk" }
Write-Host "Install on a connected phone (USB debugging on):"
Write-Host "  adb install `"$ApkDir\$apkName`""
