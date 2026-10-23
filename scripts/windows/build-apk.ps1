#requires -Version 5.1
<#
.SYNOPSIS
    Builds the PoleX Android APK on Windows (Docker-based).

.DESCRIPTION
    Wraps the same four docker compose calls as scripts\..\build-apk.sh:
    down -v, build --no-cache, the in-image source-fingerprint gate, then
    up --exit-code-from apk (android\docker-compose.yml). Docker Desktop
    for Windows is required. No local JDK/Android SDK needed.
    APKs land in android\dist.

    The docker-method calls are kept in lockstep with the bash script -
    the fingerprint gate is load-bearing (a stale COPY . . silently
    replays old build errors), so do not let the two drift apart.

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
$ApkDir = Join-Path $AndroidDir "dist"

# Source-fingerprint gate, executed INSIDE the freshly built image
# (identical to the one in scripts\..\build-apk.sh - no single quotes so
# it can sit in this single-quoted string). Compares the host tree
# (mounted read-only at /hostsrc, the live working tree) against the
# image's copy (COPY . . at build time); aborts if they differ.
$FingerprintGate = 'h=$(cd /hostsrc && find . -type f | sort | xargs md5sum | md5sum | awk "{print \$1}"); i=$(cd /workspace/app/src && find . -type f | sort | xargs md5sum | md5sum | awk "{print \$1}"); if [ "$h" != "$i" ]; then echo "ERROR: image source != host source - the build is using stale source." >&2; echo "  host : $h" >&2; echo "  image: $i" >&2; echo "  The Dockerfile COPY . . did not pick up your working tree." >&2; echo "  Check you are not shadowing the context; the compose file in" >&2; echo "  android/ builds from the android/ directory (build context)." >&2; exit 1; fi; echo "==> Source verified in image (md5 $i)"'

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
    # Same four calls as the bash script, in order.
    # 1) Remove containers AND the persistent gradle-cache volume (a
    #    poisoned Kotlin incremental-compile cache replays old `e:`
    #    errors, so a plain `down` without -v is not enough).
    docker compose down -v 2>$null
    # 2) Rebuild the image: --no-cache forces COPY . . to re-copy the
    #    current source (guaranteed-fresh copy).
    Write-Host "==> Building image (no cache - guarantees fresh source copy)..." -ForegroundColor Cyan
    docker compose build --no-cache apk
    if ($LASTEXITCODE -ne 0) { throw "ERROR: docker compose build failed" }
    # 3) Hard-gate: verify the image actually contains the current source
    #    BEFORE spending a full compile (the in-container one-liner above,
    #    mounted from the live host tree at /hostsrc).
    $HostSrcAbs = (Resolve-Path (Join-Path $AndroidDir "app\src")).Path
    docker compose run --rm -T -v "${HostSrcAbs}:/hostsrc:ro" apk sh -c $FingerprintGate
    if ($LASTEXITCODE -ne 0) { throw "ERROR: source fingerprint gate failed (stale source copy)" }
    # 4) Build from the verified image. --exit-code-from propagates a
    #    failed gradle run (plain `up` would exit 0 and ship an empty
    #    dist/ as "success"). On failure, gradle already prints the
    #    `e: file:...` compiler lines above.
    $env:TASKS = $gradleTask
    docker compose up --exit-code-from apk apk
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
