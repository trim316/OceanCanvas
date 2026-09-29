[CmdletBinding()]
param([int]$TimeoutMinutes = 720)

$ErrorActionPreference = 'Stop'
$stateRoot = Join-Path $env:LOCALAPPDATA 'OceanCanvas\AutonomousSupervisor'
$workspace = Join-Path $stateRoot 'workspace'
$supervisor = Join-Path $workspace 'scripts\windows\oceancanvas-autonomous-supervisor.ps1'
$permanentRoot = Join-Path $env:LOCALAPPDATA 'OceanCanvas\PermanentGateHarness'
$ledger = Join-Path $permanentRoot 'gate-ledger.properties'
$evidence = Join-Path $permanentRoot 'evidence'
$finalMarker = Join-Path $stateRoot 'PROVEN.txt'
$attentionMarker = Join-Path $stateRoot 'NEEDS-ATTENTION.txt'
$desktop = [Environment]::GetFolderPath('Desktop')
$postLog = Join-Path $desktop 'OceanCanvas-POST-THIS-LOG.txt'
$outDir = Join-Path $env:GITHUB_WORKSPACE 'runtime-evidence'

function Write-GateStatus {
    $parts = @()
    if (Test-Path -LiteralPath $ledger) {
        foreach ($line in @(Get-Content -LiteralPath $ledger -ErrorAction SilentlyContinue)) {
            if ($line -match '^(G2|G4|G9|G16)=(.+)$') { $parts += "$($Matches[1])=$($Matches[2])" }
        }
    }
    if ($parts.Count -eq 0) { return 'no durable gate ledger yet' }
    return ($parts -join ' ')
}

function Get-LiveStatus {
    if (-not (Test-Path -LiteralPath $postLog)) { return $null }
    $lines = @(Get-Content -LiteralPath $postLog -ErrorAction SilentlyContinue)
    $note = ($lines | Where-Object { $_ -like 'Automation note:*' } | Select-Object -First 1)
    $stage = ($lines | Where-Object { $_ -like 'Journal stage:*' } | Select-Object -First 1)
    $x = ($lines | Where-Object { $_ -match '^\s*chunkX=' } | Select-Object -First 1)
    $z = ($lines | Where-Object { $_ -match '^\s*chunkZ=' } | Select-Object -First 1)
    $r = ($lines | Where-Object { $_ -match '^\s*verifiedRestarts=' } | Select-Object -First 1)
    return (@($note,$stage,$x,$z,$r) | Where-Object { $_ } | ForEach-Object { $_.Trim() }) -join ' | '
}

function Publish-Summary([string]$Result) {
    $gateStatus = Write-GateStatus
    $summary = @(
        '# Ocean Canvas runtime gates',
        '',
        "- Result: **$Result**",
        '- Frozen runtime: **26.2-core-v0.2.23**',
        '- Runtime policy: **no build / no Gradle / maxActiveChunks=1**',
        "- Gate ledger: $gateStatus",
        "- Evidence root: $evidence"
    )
    if ($env:GITHUB_STEP_SUMMARY) { $summary | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Encoding UTF8 }
}

if (-not (Test-Path -LiteralPath $supervisor)) { throw "Autonomous supervisor is not installed at $supervisor" }

Write-Host 'Ocean Canvas GitHub runtime bridge'
Write-Host "Supervisor: $supervisor"
Write-Host "Current gates: $(Write-GateStatus)"

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $supervisor
$initialExit = $LASTEXITCODE
Write-Host "Supervisor invocation returned code $initialExit; attaching to durable campaign state."

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$last = ''
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $finalMarker) {
        Write-Host 'PROVEN: autonomous campaign completed.'
        Get-Content -LiteralPath $finalMarker | ForEach-Object { Write-Host $_ }
        Publish-Summary 'PROVEN'
        break
    }
    if (Test-Path -LiteralPath $attentionMarker) {
        Write-Host 'NEEDS ATTENTION: autonomous campaign stopped.'
        Get-Content -LiteralPath $attentionMarker | ForEach-Object { Write-Host $_ }
        Publish-Summary 'NEEDS ATTENTION'
        exit 3
    }
    $live = Get-LiveStatus
    $gates = Write-GateStatus
    $line = "[$(Get-Date -Format 'HH:mm:ss')] $gates"
    if ($live) { $line += " | $live" }
    if ($line -ne $last) { Write-Host $line; $last = $line }
    Start-Sleep -Seconds 10
}

if (-not (Test-Path -LiteralPath $finalMarker)) {
    Publish-Summary 'TIMEOUT'
    throw "Runtime gate monitor timed out after $TimeoutMinutes minutes."
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
if (Test-Path -LiteralPath $ledger) { Copy-Item -LiteralPath $ledger -Destination (Join-Path $outDir 'gate-ledger.properties') -Force }
if (Test-Path -LiteralPath $finalMarker) { Copy-Item -LiteralPath $finalMarker -Destination (Join-Path $outDir 'PROVEN.txt') -Force }
if (Test-Path -LiteralPath $postLog) { Copy-Item -LiteralPath $postLog -Destination (Join-Path $outDir 'OceanCanvas-POST-THIS-LOG.txt') -Force }
if (Test-Path -LiteralPath $evidence) { Copy-Item -LiteralPath $evidence -Destination (Join-Path $outDir 'evidence') -Recurse -Force }
Write-Host "Runtime evidence staged at $outDir"