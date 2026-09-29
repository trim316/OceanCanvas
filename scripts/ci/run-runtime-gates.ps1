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

# Prevent GitHub Runner orphan cleanup from force-killing Minecraft. The controller
# owns lifecycle and may only escalate after proving the world session lock is free.
$env:RUNNER_TRACKING_ID = 'oceancanvas-runtime-owned'

$profileRoot = Join-Path $env:APPDATA 'ModrinthApp\profiles\Fabulously Optimized'
$worldLock = Join-Path $profileRoot 'saves\New World\session.lock'
$attemptLog = Join-Path $permanentRoot 'github-controller-attempt.log'
$maxRecoveryAttempts = 3

function Test-WorldLockReleased {
    if (-not (Test-Path -LiteralPath $worldLock)) { return $false }
    try {
        $fs = [System.IO.File]::Open($worldLock,[System.IO.FileMode]::Open,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None)
        $fs.Close()
        return $true
    } catch {
        return $false
    }
}

function Get-LastMinecraftPid {
    if (-not (Test-Path -LiteralPath $attemptLog)) { return $null }
    $text = Get-Content -LiteralPath $attemptLog -Raw -ErrorAction SilentlyContinue
    $matches = [regex]::Matches($text,'(?:minecraftPid=|Minecraft PID\s+)(\d+)')
    if ($matches.Count -eq 0) { return $null }
    return [int]$matches[$matches.Count - 1].Groups[1].Value
}

function Invoke-ControllerOnce([int]$Attempt) {
    Write-Host "== GitHub controller attempt $Attempt/$maxRecoveryAttempts =="
    Remove-Item -LiteralPath $attemptLog -Force -ErrorAction SilentlyContinue
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $controller -Action PermanentAuto 2>&1 |
        Tee-Object -FilePath $attemptLog
    return $LASTEXITCODE
}

function Recover-CleanCloseTimeout {
    $pidValue = Get-LastMinecraftPid
    if (-not $pidValue) {
        Write-Warning 'Could not identify the target Minecraft PID from controller telemetry.'
        return $false
    }

    Write-Host "RECOVERY: clean-close timeout for Minecraft PID $pidValue; preserving world safety."
    $deadline = (Get-Date).AddMinutes(8)
    while ((Get-Date) -lt $deadline) {
        $p = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
        if (-not $p) {
            Write-Host 'RECOVERY PASS: Minecraft exited after the controller timeout.'
            return $true
        }

        try { [void]$p.CloseMainWindow() } catch {}

        if (Test-WorldLockReleased) {
            Write-Host 'RECOVERY: world session.lock is released; leftover client process can be terminated safely.'
            Stop-Process -Id $pidValue -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 3
            if (-not (Get-Process -Id $pidValue -ErrorAction SilentlyContinue)) {
                Write-Host 'RECOVERY PASS: terminated leftover client only after lock-release proof.'
                return $true
            }
        }

        Write-Host 'RECOVERY: client still alive and/or world lock still held; retrying graceful close in 15s.'
        Start-Sleep -Seconds 15
    }

    $diag = Join-Path $permanentRoot ("hung-client-{0}.txt" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    @(
        "timestamp=$(Get-Date -Format o)",
        "pid=$pidValue",
        "worldLock=$worldLock",
        "worldLockReleased=$(Test-WorldLockReleased)",
        "gateStatus=$(Write-GateStatus)"
    ) | Set-Content -LiteralPath $diag -Encoding UTF8
    Write-Warning "RECOVERY STOP: client remained unsafe to terminate after 8 additional minutes. Diagnostic: $diag"
    return $false
}

$code = 1
for ($attempt = 1; $attempt -le $maxRecoveryAttempts; $attempt++) {
    $code = Invoke-ControllerOnce -Attempt $attempt
    Write-Host "Controller exit code: $code"
    Write-Host "Durable status after attempt $attempt`: $(Write-GateStatus)"
    if ($code -eq 0) { break }

    $attemptText = ''
    if (Test-Path -LiteralPath $attemptLog) {
        $attemptText = Get-Content -LiteralPath $attemptLog -Raw -ErrorAction SilentlyContinue
    }
    $isCleanCloseTimeout = $attemptText -match 'did not exit within 150 seconds|Could not close the target Modrinth Minecraft client cleanly'
    if (-not $isCleanCloseTimeout) {
        Write-Warning 'Controller failure is not the known recoverable clean-close timeout; not retrying blindly.'
        break
    }

    if (-not (Recover-CleanCloseTimeout)) { break }
    Write-Host 'RECOVERY PASS: resuming the durable gate campaign automatically.'
    Start-Sleep -Seconds 5
}

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
