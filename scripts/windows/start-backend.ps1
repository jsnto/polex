#requires -Version 5.1
<#
.SYNOPSIS
    Starts the PoleX backend on Windows.
    Prefers Docker Desktop (correct for WOL: host networking).
    Falls back to a local Go build if docker isn't available.

.DESCRIPTION
    Environment variables (optional):
        ADDR            listen address, default ":8080"
        CHECK_INTERVAL  health-check interval, default "10s"

.EXAMPLE
    .\scripts\windows\start-backend.ps1
    $env:ADDR=":9000"; $env:CHECK_INTERVAL="5s"; .\scripts\windows\start-backend.ps1
#>
[CmdletBinding()]
param(
    [string]$Addr = $(if ($env:ADDR) { $env:ADDR } else { ":8080" }),
    [string]$CheckInterval = $(if ($env:CHECK_INTERVAL) { $env:CHECK_INTERVAL } else { "10s" })
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$BackendDir = Join-Path $Root "backend"
$DataDir = Join-Path $BackendDir "data"

function Test-Docker {
    try {
        $null = docker info 2>$null
        return ($LASTEXITCODE -eq 0)
    } catch { return $false }
}

if (Test-Docker) {
    Write-Host "==> Starting backend via docker compose (host networking, WOL-safe)" -ForegroundColor Cyan
    Set-Location $BackendDir
    if (-not (Test-Path $DataDir)) {
        Write-Host "==> Creating data dir"
        New-Item -ItemType Directory -Path $DataDir | Out-Null
    }
    # NOTE: on Windows the container (uid 10001) needs to write to .\data.
    # Docker Desktop file sharing (gRPC-FUSE/WSL2) usually allows this; if the
    # backend fails with permission errors, run this PowerShell as Admin once:
    #   (not strictly required on WSL2-based Docker Desktop)
    docker compose down 2>$null
    docker compose up --build
}
else {
    if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
        throw "ERROR: docker and go are both unavailable (install Go: https://go.dev/dl)"
    }
    Write-Host "==> docker unavailable, starting backend with local Go build" -ForegroundColor Cyan
    Set-Location $BackendDir
    go build -o polex-backend.exe .
    $env:ADDR = $Addr
    $env:CHECK_INTERVAL = $CheckInterval
    Write-Host "==> Running .\polex-backend.exe (ADDR=$Addr CHECK_INTERVAL=$CheckInterval)" -ForegroundColor Cyan
    & ".\polex-backend.exe"
}
