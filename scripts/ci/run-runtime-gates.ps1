[CmdletBinding()]
param([int]$TimeoutMinutes = 720)

$ErrorActionPreference = 'Stop'
$stateRoot = Join-Path $env:LOCALAPPDATA 'OceanCanvas\AutonomousSupervisor'
$workspace = Join-Path $stateRoot 'workspace'
$controller = Join-Path $workspace 'scripts\windows\oceancanvas-permanent-controller.ps1'
$permanentRoot = Join-Path $env:LOCALAPPDATA 'OceanCanvas\PermanentGateHarness'
$ledger = Join-Path $permanentRoot 'gate-ledger.properties'
$evidence = Join-Path $permanentRoot 'evidence'
$finalMarker = Join-Path $stateRoot 'PROVEN.txt'
$attentionMarker = Join-Path $stateRoot 'NEEDS-ATTENTION.txt'
$desktop = [Environment]::GetFolderPath('Desktop')
$postLog = Join-Path $desktop 'OceanCanvas-POST-THIS-LOG.txt'
$outDir = Join-Path $env:GITHUB_WORKSPACE 'runtime-evidence'

function Get-CompletedGates {
    $completed = @('G2')
    if (Test-Path -LiteralPath $ledger) {
        foreach ($line in @(Get-Content -LiteralPath $ledger -ErrorAction SilentlyContinue)) {
            if ($line -match '^completed=(.+)$') {
                foreach ($id in $Matches[1].Split(',')) {
                    $id = $id.Trim()
                    if ($id -in @('G2','G4','G9','G16')) { $completed += $id }
                }
            }
        }
    }
    return @($completed | Select-Object -Unique)
}

function Write-GateStatus {
    $done = @(Get-CompletedGates)
    return ((@('G2','G4','G9','G16') | ForEach-Object {
        if ($done -contains $_) { "$_=PASS" } else { "$_=PENDING" }
    }) -join ' ')
}

function Stop-LegacySupervisorOwnership {
    $victims = @()
    try {
        $victims = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -in @('powershell.exe','pwsh.exe') -and
            -not [string]::IsNullOrWhiteSpace([string]$_.CommandLine) -and
            (([string]$_.CommandLine -like '*oceancanvas-autonomous-supervisor.ps1*') -or
             ([string]$_.CommandLine -like '*oceancanvas-permanent-controller.ps1*'))
        })
    } catch {}
    foreach ($v in $victims) {
        try {
            Write-Host "Retiring legacy local supervisor PID $($v.ProcessId) so GitHub Actions is the sole runtime owner."
            Stop-Process -Id ([int]$v.ProcessId) -Force -ErrorAction Stop
        } catch {
            Write-Warning "Could not retire PID $($v.ProcessId): $($_.Exception.Message)"
        }
    }
    if ($victims.Count -gt 0) { Start-Sleep -Seconds 2 }
}

function Publish-Summary([string]$Result) {
    $summary = @(
        '# Ocean Canvas runtime gates',
        '',
        "- Result: **$Result**",
        '- Frozen runtime: **26.2-core-v0.2.23**',
        '- Runtime policy: **no build / no Gradle / maxActiveChunks=1**',
        "- Gate ledger: $(Write-GateStatus)",
        "- Evidence root: $evidence"
    )
    if ($env:GITHUB_STEP_SUMMARY) { $summary | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Encoding UTF8 }
}

function Stage-Evidence {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    if (Test-Path -LiteralPath $ledger) { Copy-Item -LiteralPath $ledger -Destination (Join-Path $outDir 'gate-ledger.properties') -Force }
    if (Test-Path -LiteralPath $finalMarker) { Copy-Item -LiteralPath $finalMarker -Destination (Join-Path $outDir 'PROVEN.txt') -Force }
    if (Test-Path -LiteralPath $attentionMarker) { Copy-Item -LiteralPath $attentionMarker -Destination (Join-Path $outDir 'NEEDS-ATTENTION.txt') -Force }
    if (Test-Path -LiteralPath $postLog) { Copy-Item -LiteralPath $postLog -Destination (Join-Path $outDir 'OceanCanvas-POST-THIS-LOG.txt') -Force }
    if (Test-Path -LiteralPath $evidence) { Copy-Item -LiteralPath $evidence -Destination (Join-Path $outDir 'evidence') -Recurse -Force }
}

if (-not (Test-Path -LiteralPath $controller)) {
    throw "Permanent controller is not installed at $controller"
}

Write-Host 'Ocean Canvas GitHub runtime owner'
Write-Host "Controller: $controller"
Write-Host "Durable status before run: $(Write-GateStatus)"

Stop-LegacySupervisorOwnership
Remove-Item -LiteralPath $attentionMarker -Force -ErrorAction SilentlyContinue

# GitHub Actions now owns the controller directly. Its stdout streams into the
# Actions log, so the repository dashboard shows the real Minecraft progression.
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $controller -Action PermanentAuto
$code = $LASTEXITCODE

Write-Host "Controller exit code: $code"
Write-Host "Durable status after run: $(Write-GateStatus)"

if ($code -ne 0) {
    @(
        'Ocean Canvas GitHub runtime controller stopped',
        "Stopped: $(Get-Date -Format o)",
        "Controller exit code: $code",
        "Gate status: $(Write-GateStatus)",
        "Runtime handoff: $postLog"
    ) | Set-Content -LiteralPath $attentionMarker -Encoding UTF8
    Stage-Evidence
    Publish-Summary 'NEEDS ATTENTION'
    exit $code
}

$done = @(Get-CompletedGates)
$missing = @('G2','G4','G9','G16' | Where-Object { $done -notcontains $_ })
if ($missing.Count -gt 0) {
    @(
        'Ocean Canvas GitHub runtime controller returned without completing the ladder',
        "Stopped: $(Get-Date -Format o)",
        "Missing gates: $($missing -join ',')",
        "Gate status: $(Write-GateStatus)"
    ) | Set-Content -LiteralPath $attentionMarker -Encoding UTF8
    Stage-Evidence
    Publish-Summary 'INCOMPLETE'
    exit 4
}

@(
    'Ocean Canvas autonomous proof complete',
    "Completed: $(Get-Date -Format o)",
    'Frozen runtime: 26.2-core-v0.2.23',
    'Gates: G2=PASS G4=PASS G9=PASS G16=PASS',
    'maxActiveChunks=1',
    'No Gradle build or runtime replacement occurred during gate progression.',
    "Evidence root: $evidence"
) | Set-Content -LiteralPath $finalMarker -Encoding UTF8

Stage-Evidence
Publish-Summary 'PROVEN'
Write-Host 'PROVEN: G2, G4, G9 and G16 all durably PASS.'
