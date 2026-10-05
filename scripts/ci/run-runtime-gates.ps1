[CmdletBinding()]
param(
    [int]$TimeoutMinutes = 720,
    [switch]$ReleaseProofOnly
)

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

function Read-SimpleProperties([string]$Path) {
    $result = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $result }
    foreach ($line in Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue) {
        if ($line -match '^([^#=]+)=(.*)$') {
            $result[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    return $result
}

function Get-ControllerRuntimeIdentity {
    if (-not (Test-Path -LiteralPath $controller)) { return $null }
    $text = Get-Content -LiteralPath $controller -Raw -ErrorAction SilentlyContinue
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }

    $runtimeMatch = [regex]::Match($text, "(?m)^\s*\$ExpectedModVersion\s*=\s*'([^']+)'\s*$")
    $shaMatch = [regex]::Match($text, "(?m)^\s*\$ProvenJarSha256\s*=\s*'([0-9a-fA-F]{64})'\s*$")
    if (-not $runtimeMatch.Success -or -not $shaMatch.Success) { return $null }

    return @{
        Runtime = $runtimeMatch.Groups[1].Value
        Sha256 = $shaMatch.Groups[1].Value.ToLowerInvariant()
    }
}

function Test-GateEvidenceBound([string]$GateId) {
    $identity = Get-ControllerRuntimeIdentity
    if ($null -eq $identity) { return $false }

    $summary = Join-Path $evidence (Join-Path $GateId 'GATE-SUMMARY.txt')
    $p = Read-SimpleProperties $summary
    if ($p.Count -eq 0) { return $false }
    if (-not $p.ContainsKey('runtime') -or $p['runtime'] -ne $identity.Runtime) { return $false }
    if (-not $p.ContainsKey('runtimeSha256') -or $p['runtimeSha256'].ToLowerInvariant() -ne $identity.Sha256) { return $false }
    if (-not $p.ContainsKey('gate') -or $p['gate'] -ne $GateId) { return $false }
    if (-not $p.ContainsKey('verdict') -or $p['verdict'] -ne 'PASS') { return $false }
    return $true
}

function Get-CompletedGates {
    $completed = @()
    if (Test-Path -LiteralPath $ledger) {
        foreach ($line in @(Get-Content -LiteralPath $ledger -ErrorAction SilentlyContinue)) {
            if ($line -match '^completed=(.+)$') {
                foreach ($id in $Matches[1].Split(',')) {
                    $id = $id.Trim()
                    if ($id -in @('G2','G4','G9','G16')) {
                        if (Test-GateEvidenceBound $id) {
                            $completed += $id
                        } else {
                            Write-Warning "Ignoring unbound completed-gate ledger credit: $id"
                        }
                    }
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
        '- Runtime identity: **read from candidate-scoped permanent gate ledger/controller**',
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

if($ReleaseProofOnly){
    $candidatePath=Join-Path $env:GITHUB_WORKSPACE 'candidate\candidate.properties'
    $attestationPath=Join-Path $env:GITHUB_WORKSPACE 'release-evidence\v0.2.26.properties'
    $candidate=Read-SimpleProperties $candidatePath
    $attestation=Read-SimpleProperties $attestationPath

    if($candidate.Count -eq 0){ throw "RELEASE PROOF MISSING: candidate manifest not found at $candidatePath" }
    if($attestation.Count -eq 0){ throw "RELEASE PROOF MISSING: committed attestation not found at $attestationPath" }

    foreach($key in @('runtime','jarSha256')){
        if(-not $candidate.ContainsKey($key)){ throw "RELEASE PROOF INVALID: candidate missing $key" }
        if(-not $attestation.ContainsKey($key)){ throw "RELEASE PROOF INVALID: attestation missing $key" }
        if($candidate[$key].ToLowerInvariant() -ne $attestation[$key].ToLowerInvariant()){
            throw "RELEASE PROOF MISMATCH: $key candidate=$($candidate[$key]) attestation=$($attestation[$key])"
        }
    }

    foreach($key in @(
        'verdict',
        'finalRestartVerified',
        'preimageCaptured',
        'physicalAuthoringComplete',
        'physicalSettlementVerified',
        'saveFlushComplete',
        'lightRequestComplete',
        'serverVerificationComplete',
        'restoreComplete',
        'restoreVerified',
        'exactBlockStateIds',
        'acceptanceFinalRestartVerified'
    )){
        if(-not $attestation.ContainsKey($key)){ throw "RELEASE PROOF INVALID: attestation missing $key" }
        $expected=if($key -eq 'verdict'){'PASS'}else{'true'}
        if($attestation[$key].ToLowerInvariant() -ne $expected.ToLowerInvariant()){
            throw "RELEASE PROOF INVALID: $key=$($attestation[$key]) expected=$expected"
        }
    }

    if(-not $attestation.ContainsKey('verifiedRestarts') -or [int]$attestation['verifiedRestarts'] -lt 7){
        throw 'RELEASE PROOF INVALID: verifiedRestarts must be at least 7.'
    }

    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Copy-Item -LiteralPath $attestationPath -Destination (Join-Path $outDir 'RELEASE-PROOF.txt') -Force
    Write-Host "RELEASE_PROOF_REUSED exactArtifact=true runtime=$($candidate['runtime']) sha256=$($candidate['jarSha256']) verifiedRestarts=$($attestation['verifiedRestarts']) minecraftRelaunchRequired=false"
    exit 0
}

if (-not (Test-Path -LiteralPath $controller)) {
    throw "Permanent controller is not installed at $controller"
}

Write-Host 'Ocean Canvas GitHub runtime owner'
Write-Host "Controller: $controller"
Write-Host "Durable status before run: $(Write-GateStatus)"

function Test-CapturedReleaseProof {
    $identity=Get-ControllerRuntimeIdentity
    if($null -eq $identity){ return $false }

    # Evidence has existed under more than one durable folder layout across
    # controller generations. Discover PASS receipts by content instead of
    # assuming a single path, then bind them to the exact runtime identity.
    $verdictFiles=@(Get-ChildItem -LiteralPath $permanentRoot -Filter 'VERDICT.txt' -File -Recurse -ErrorAction SilentlyContinue)
    foreach($vf in $verdictFiles){
        $verdict=Read-SimpleProperties $vf.FullName
        if($verdict.Count -eq 0){ continue }
        if(-not $verdict.ContainsKey('build') -or $verdict['build'] -ne $identity.Runtime){ continue }
        if(-not $verdict.ContainsKey('verdict') -or $verdict['verdict'] -ne 'PASS'){ continue }

        $chunkRoot=Split-Path -Parent $vf.FullName
        $statePath=Join-Path $chunkRoot 'single-chunk\acceptance-state.properties'
        $receiptsPath=Join-Path $chunkRoot 'single-chunk\runtime-receipts.log'
        $latestLogPath=Join-Path $chunkRoot 'latest.log'
        if(-not (Test-Path -LiteralPath $statePath)){ continue }
        if(-not (Test-Path -LiteralPath $receiptsPath)){ continue }
        if(-not (Test-Path -LiteralPath $latestLogPath)){ continue }

        $state=Read-SimpleProperties $statePath
        if(-not $state.ContainsKey('finalRestartVerified') -or $state['finalRestartVerified'].ToLowerInvariant() -ne 'true'){ continue }
        if(-not $state.ContainsKey('verifiedRestarts') -or [int]$state['verifiedRestarts'] -lt 7){ continue }

        $receipts=Get-Content -LiteralPath $receiptsPath -Raw
        $requiredReceipts=@(
            'PREIMAGE_CAPTURED',
            'PHYSICAL_AUTHORING_COMPLETE',
            'PHYSICAL_SETTLEMENT_VERIFIED',
            'SAVE_FLUSH_COMPLETE',
            'LIGHT_REQUEST_COMPLETE',
            'SERVER_VERIFICATION_COMPLETE',
            'RESTORE_COMPLETE',
            'RESTORE_VERIFIED',
            'ACCEPTANCE_FINAL_RESTART_VERIFIED'
        )
        $missingReceipts=@($requiredReceipts | Where-Object { -not $receipts.Contains($_) })
        if($missingReceipts.Count -gt 0){ continue }

        $latest=Get-Content -LiteralPath $latestLogPath -Raw
        if(-not $latest.Contains("- oceancanvas $($identity.Runtime)")){ continue }

        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
        @(
            "runtime=$($identity.Runtime)",
            "runtimeSha256=$($identity.Sha256)",
            'releaseProof=PASS',
            'proofSource=durable-single-chunk-end-to-end',
            "evidencePath=$chunkRoot",
            "verifiedRestarts=$($state['verifiedRestarts'])",
            "finalRestartVerified=$($state['finalRestartVerified'])",
            'restoreVerified=true',
            'minecraftRelaunchRequired=false',
            "validated=$(Get-Date -Format o)"
        ) | Set-Content -LiteralPath (Join-Path $outDir 'RELEASE-PROOF.txt') -Encoding ASCII

        Write-Host "RELEASE_PROOF_FOUND path=$chunkRoot verifiedRestarts=$($state['verifiedRestarts'])"
        return $true
    }

    Write-Host "RELEASE_PROOF_SCAN candidates=$($verdictFiles.Count) root=$permanentRoot"
    foreach($vf in $verdictFiles | Select-Object -First 20){
        Write-Host "RELEASE_PROOF_CANDIDATE $($vf.FullName)"
    }
    return $false
}

if(Test-CapturedReleaseProof){
    Write-Host 'RELEASE_PROOF_REUSED exactArtifact=true minecraftRelaunchRequired=false'
    Stage-Evidence
    Publish-Summary 'PROVEN (REUSED END-TO-END EVIDENCE)'
    exit 0
}

if($ReleaseProofOnly){
    throw 'RELEASE PROOF MISSING: exact-artifact end-to-end evidence was not found; refusing to launch Minecraft in proof-only mode.'
}

Stop-LegacySupervisorOwnership
Remove-Item -LiteralPath $attentionMarker -Force -ErrorAction SilentlyContinue

# Prevent GitHub Runner orphan cleanup from force-killing Minecraft. The controller
# owns lifecycle and may only escalate after proving the world session lock is free.
$env:RUNNER_TRACKING_ID = 'oceancanvas-runtime-owned'

$profileRoot = Join-Path $env:APPDATA 'ModrinthApp\profiles\Fabulously Optimized'
$worldLock = Join-Path $profileRoot 'saves\New World\session.lock'
$attemptLog = Join-Path $permanentRoot 'github-controller-attempt.log'
$maxRecoveryAttempts = 6

function Test-WorldLockReleased {
    if (-not (Test-Path -LiteralPath $worldLock)) { return $true }
    try {
        $fs = [System.IO.File]::Open($worldLock,[System.IO.FileMode]::Open,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None)
        $fs.Close()
        return $true
    } catch {
        return $false
    }
}


function Get-TargetMinecraftProcesses {
    $escapedProfile = [regex]::Escape($profileRoot)
    try {
        return @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -in @('javaw.exe','java.exe') -and
            -not [string]::IsNullOrWhiteSpace([string]$_.CommandLine) -and
            (
                ([string]$_.CommandLine -match $escapedProfile) -or
                (
                    ([string]$_.CommandLine -match '--gameDir') -and
                    ([string]$_.CommandLine -match 'ModrinthApp.+Fabulously Optimized')
                )
            )
        })
    } catch {
        Write-Warning "Could not enumerate target Minecraft processes: $($_.Exception.Message)"
        return @()
    }
}

function Resolve-StaleRuntimeOwnership {
    if (Test-WorldLockReleased) {
        Write-Host 'PREFLIGHT PASS: New World session.lock is free.'
        return $true
    }

    Write-Warning 'PREFLIGHT: New World is still owned by a prior Minecraft process; resolving before gate execution.'
    $deadline = (Get-Date).AddMinutes(10)
    $lastPids = ''

    while ((Get-Date) -lt $deadline) {
        $targets = @(Get-TargetMinecraftProcesses)
        $pids = @($targets | ForEach-Object { [int]$_.ProcessId })
        $pidText = if ($pids.Count -gt 0) { $pids -join ',' } else { 'none-detected' }

        if ($pidText -ne $lastPids) {
            Write-Host "PREFLIGHT: target Minecraft PIDs=$pidText"
            $lastPids = $pidText
        }

        foreach ($t in $targets) {
            try {
                $proc = Get-Process -Id ([int]$t.ProcessId) -ErrorAction SilentlyContinue
                if ($proc) {
                    Write-Host "PREFLIGHT: requesting graceful close for Minecraft PID $($t.ProcessId)."
                    [void]$proc.CloseMainWindow()
                }
            } catch {
                Write-Warning "Graceful close request failed for PID $($t.ProcessId): $($_.Exception.Message)"
            }
        }

        Start-Sleep -Seconds 5

        if (Test-WorldLockReleased) {
            Write-Host 'PREFLIGHT PASS: world session.lock released.'
            $leftovers = @(Get-TargetMinecraftProcesses)
            foreach ($t in $leftovers) {
                try {
                    Write-Host "PREFLIGHT: lock is free; terminating leftover target client PID $($t.ProcessId)."
                    Stop-Process -Id ([int]$t.ProcessId) -Force -ErrorAction Stop
                } catch {
                    Write-Warning "Could not terminate leftover PID $($t.ProcessId): $($_.Exception.Message)"
                }
            }
            Start-Sleep -Seconds 3
            return $true
        }

        Start-Sleep -Seconds 10
    }

    $targets = @(Get-TargetMinecraftProcesses)
    $diag = Join-Path $permanentRoot ("stale-world-preflight-{0}.txt" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    @(
        "timestamp=$(Get-Date -Format o)",
        "worldLock=$worldLock",
        "worldLockReleased=$(Test-WorldLockReleased)",
        "targetPids=$((@($targets | ForEach-Object { $_.ProcessId })) -join ',')",
        "gateStatus=$(Write-GateStatus)"
    ) | Set-Content -LiteralPath $diag -Encoding UTF8
    Write-Warning "PREFLIGHT STOP: world ownership did not clear within 10 minutes. Diagnostic: $diag"
    return $false
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

    # IMPORTANT: keep the controller transcript out of this function's success
    # output stream. Otherwise assigning the function result to $code produces an
    # array containing log lines + the integer exit code and can false-green CI.
    $savedErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $controller -Action PermanentAuto 2>&1 |
            Tee-Object -FilePath $attemptLog |
            Out-Host
        $controllerExitCode = [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }

    Write-Host "CONTROLLER_EXIT_CODE=$controllerExitCode"
    return [int]$controllerExitCode
}

function Reset-ModrinthLauncher {
    if (-not (Test-WorldLockReleased)) {
        Write-Warning 'LAUNCHER RECOVERY REFUSED: world lock is still held.'
        return $false
    }

    $minecraft = @(Get-TargetMinecraftProcesses)
    if ($minecraft.Count -gt 0) {
        Write-Warning "LAUNCHER RECOVERY REFUSED: target Minecraft process still exists: $((@($minecraft | ForEach-Object { $_.ProcessId })) -join ',')"
        return $false
    }

    $launchers = @()
    try {
        $launchers = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -in @('Modrinth App.exe','modrinth-app.exe','ModrinthApp.exe') -or
            ([string]$_.Name -like 'Modrinth*')
        })
    } catch {}

    foreach ($p in $launchers) {
        try {
            Write-Host "LAUNCHER RECOVERY: restarting Modrinth PID $($p.ProcessId)."
            Stop-Process -Id ([int]$p.ProcessId) -Force -ErrorAction Stop
        } catch {
            Write-Warning "Could not stop Modrinth PID $($p.ProcessId): $($_.Exception.Message)"
        }
    }

    Start-Sleep -Seconds 5
    return $true
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
    if (-not (Resolve-StaleRuntimeOwnership)) {
        $code = 5
        break
    }

    $code = Invoke-ControllerOnce -Attempt $attempt
    Write-Host "Controller exit code: $code"
    Write-Host "Durable status after attempt $attempt`: $(Write-GateStatus)"
    if ($code -eq 0) { break }

    $attemptText = ''
    if (Test-Path -LiteralPath $attemptLog) {
        $attemptText = Get-Content -LiteralPath $attemptLog -Raw -ErrorAction SilentlyContinue
    }
    $isCleanCloseTimeout = $attemptText -match 'did not exit within 150 seconds|Could not close the target Modrinth Minecraft client cleanly'
    $isLaunchFailure = $attemptText -match 'Automatic Modrinth launch produced no attributable Minecraft startup activity'
    $isTransientResidencyRace = $attemptText -match 'FULL chunk future completed without LevelChunk:\s*Unloaded chunk'
    $isGateHandoffWorldOpen = $attemptText -match 'The test world appears to be open\. Save & Quit before arming/resetting it'

    if ($isGateHandoffWorldOpen) {
        $before = Write-GateStatus
        Write-Warning "RECOVERY: completed-gate handoff found New World still open; preserving ledger and closing only the stale client. status=$before"
        if (-not (Resolve-StaleRuntimeOwnership)) { break }
        $after = Write-GateStatus
        Write-Host "RECOVERY PASS: gate-handoff ownership cleared without resetting durable progress. status=$after"
        Start-Sleep -Seconds 2
        continue
    }

    if ($isTransientResidencyRace) {
        $diag = Join-Path $permanentRoot ("classified-transient-residency-{0}.txt" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
        @(
            "timestamp=$(Get-Date -Format o)",
            'classification=KNOWN_TRANSIENT_RUNTIME_RESIDENCY_RACE',
            'signature=FULL chunk future completed without LevelChunk: Unloaded chunk',
            "gateStatus=$(Write-GateStatus)",
            'policy=archive failed attempt, restart at clean process boundary, retry exact durable campaign; never count failed attempt as gate PASS'
        ) | Set-Content -LiteralPath $diag -Encoding UTF8
        Write-Warning "RECOVERY: known transient FULL-chunk residency race classified; evidence=$diag"
        if (-not (Resolve-StaleRuntimeOwnership)) { break }
        Start-Sleep -Seconds 5
        continue
    }

    if ($isCleanCloseTimeout) {
        if (-not (Recover-CleanCloseTimeout)) { break }
        Write-Host 'RECOVERY PASS: resuming the durable gate campaign automatically.'
        Start-Sleep -Seconds 5
        continue
    }

    if ($isLaunchFailure) {
        Write-Warning 'RECOVERY: Modrinth failed to produce attributable Minecraft startup; resetting launcher and retrying.'
        if (-not (Reset-ModrinthLauncher)) { break }
        Start-Sleep -Seconds 5
        continue
    }

    Write-Warning 'Controller failure is not a classified recoverable infrastructure condition; failing closed.'
    break
}

# A zero controller exit is necessary but never sufficient. The durable gate
# ledger is authoritative and is re-read independently before CI can succeed.
$doneAfterController = @(Get-CompletedGates)
$missingAfterController = @('G2','G4','G9','G16' | Where-Object { $doneAfterController -notcontains $_ })
if (($code -eq 0) -and ($missingAfterController.Count -gt 0)) {
    Write-Error "FAIL-CLOSED: controller returned 0 but durable ledger is incomplete: $($missingAfterController -join ',')"
    $code = 6
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
    'Runtime identity: candidate-scoped controller + ledger',
    'Gates: G2=PASS G4=PASS G9=PASS G16=PASS',
    'maxActiveChunks=1',
    'No Gradle build or runtime replacement occurred during gate progression.',
    "Evidence root: $evidence"
) | Set-Content -LiteralPath $finalMarker -Encoding UTF8

Stage-Evidence
Publish-Summary 'PROVEN'
Write-Host 'PROVEN: G2, G4, G9 and G16 all durably PASS.'
