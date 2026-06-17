#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Run the Code Review Orchestrator with environment variables from .env
.DESCRIPTION
    Loads secrets from the .env file and starts the Spring Boot app.
    Use -NoRun to only set the env vars (dot-source for the current session).
    This is the "one-click" equivalent of pressing Run in an IDE.
.EXAMPLE
    ./run.ps1
    . ./run.ps1 -NoRun       # loads env into current session (for debugging)
.PARAMETER NoRun
    Only load env vars into the session, don't start the app.
#>
param(
    [switch]$NoRun
)

$ErrorActionPreference = "Stop"

# --- Load .env file ---
$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Host "ERROR: .env file not found at $envFile" -ForegroundColor Red
    Write-Host "Copy .env.template to .env and fill in your secrets first." -ForegroundColor Yellow
    exit 1
}

Write-Host "Loading environment from .env ..." -ForegroundColor Cyan
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $eqIndex = $line.IndexOf("=")
        if ($eqIndex -gt 0) {
            $key = $line.Substring(0, $eqIndex).Trim()
            $value = $line.Substring($eqIndex + 1).Trim()
            # Remove surrounding quotes if present
            $value = $value -replace '^["'']|["'']$', ''
            Set-Item -Path "env:$key" -Value $value
        }
    }
}

# --- Validate required vars ---
$required = @("DEEPSEEK_API_KEY", "GITHUB_TOKEN", "GITHUB_WEBHOOK_SECRET")
$missing = $required | Where-Object { -not (Get-Item "env:$_" -ErrorAction SilentlyContinue) }
if ($missing) {
    Write-Host "ERROR: Missing required env vars: $($missing -join ', ')" -ForegroundColor Red
    Write-Host "Fill them in .env and try again." -ForegroundColor Yellow
    exit 1
}

Write-Host "✓ DEEPSEEK_API_KEY  = $($env:DEEPSEEK_API_KEY.Substring(0, [Math]::Min(8, $env:DEEPSEEK_API_KEY.Length)))..." -ForegroundColor Green
Write-Host "✓ GITHUB_TOKEN      = $($env:GITHUB_TOKEN.Substring(0, [Math]::Min(8, $env:GITHUB_TOKEN.Length)))..." -ForegroundColor Green
Write-Host "✓ GITHUB_WEBHOOK_SECRET = $($env:GITHUB_WEBHOOK_SECRET.Substring(0, [Math]::Min(4, $env:GITHUB_WEBHOOK_SECRET.Length)))..." -ForegroundColor Green
Write-Host ""
if (-not $NoRun) {
    # Check and install local MCP npm dependencies if node_modules is missing
    $nodeModules = Join-Path $PSScriptRoot "node_modules"
    if (-not (Test-Path $nodeModules)) {
        Write-Host "node_modules not found. Running npm install to set up local MCP servers..." -ForegroundColor Cyan
        if (Get-Command npm -ErrorAction SilentlyContinue) {
            npm install
            if ($LASTEXITCODE -ne 0) {
                Write-Host "WARNING: npm install failed. The app might fail to start if it cannot download the MCP server on-the-fly." -ForegroundColor Yellow
            } else {
                Write-Host "✓ Local MCP dependencies installed successfully." -ForegroundColor Green
            }
        } else {
            Write-Host "WARNING: npm command not found. Make sure Node.js is installed." -ForegroundColor Yellow
        }
    }

    Write-Host "Starting ReviewerApplication ..." -ForegroundColor Cyan
    Write-Host ""
    & "$PSScriptRoot\gradlew.bat" bootRun
} else {
    Write-Host "Environment loaded. You can now launch VSCode from this terminal:" -ForegroundColor Cyan
    Write-Host "    code ." -ForegroundColor Yellow
    Write-Host "Then press F5 (Run → Start Debugging) in VSCode." -ForegroundColor Cyan
}