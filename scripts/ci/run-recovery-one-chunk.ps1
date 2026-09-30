[CmdletBinding()]
param(
    [string]$ProfilePath = "$env:APPDATA\ModrinthApp\profiles\Fabulously Optimized",
    [string]$WorldName = 'New World',
    [int]$ChunkX = 32,
    [int]$ChunkZ = 32,
    [int]$PerStageTimeoutSeconds = 420,
    [int]$MaxSessions = 14
)

$ErrorActionPreference = 'Stop'
$ExpectedRuntime = '26.2-core-v0.2.26-recovery.3'
$controller = Join-Path $env:LOCALAPPDATA 'OceanCanvas\AutonomousSupervisor\workspace\scripts\windows\oceancanvas-permanent-controller.ps1'
$configPath = Join-Path $ProfilePath 'config\oceancanvas-core.properties'
$worldPath = Join-Path $ProfilePath ("saves\{0}" -f $WorldName)
$single = Join-Path $worldPath 'oceancanvas-core\single-chunk'
$statePath = Join-Path $single 'acceptance-state.properties'
$journalPath = Join-Path $single 'transitions.journal'
$receiptPath = Join-Path $single 'runtime-receipts.log'
$preimagePath = Join-Path $single 'preimage-blockstates.bin'
$outRoot = Join-Path $env:GITHUB_WORKSPACE 'recovery-runtime-proof'
$transcript = Join-Path $outRoot 'recovery-one-chunk.log'
$tempController = Join-Path $outRoot 'recovery-launch-controller.ps1'

New-Item -ItemType Directory -Force -Path $outRoot | Out-Null

function Read-Properties([string]$Path) {
    $p=@{}
    if(-not (Test-Path -LiteralPath $Path)){ return $p }
    foreach($line in Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue){
        if($line -match '^([^#=]+)=(.*)$'){ $p[$Matches[1].Trim()]=$Matches[2].Trim() }
    }
    return $p
}

function Write-ExistingStateDiagnostic([string]$Reason) {
    $diag=Join-Path $outRoot 'EXISTING_STATE_DIAGNOSTIC.txt'
    $state=Read-Properties $statePath
    $stage=Get-JournalStageLocal
    $receipts=if(Test-Path -LiteralPath $receiptPath){Get-Content -LiteralPath $receiptPath -Raw -ErrorAction SilentlyContinue}else{''}
    @(
        "reason=$Reason",
        "stage=$stage",
        "chunkX=$(if($state.ContainsKey('chunkX')){$state.chunkX}else{'unknown'})",
        "chunkZ=$(if($state.ContainsKey('chunkZ')){$state.chunkZ}else{'unknown'})",
        "awaitingRestartStage=$(if($state.ContainsKey('awaitingRestartStage')){$state.awaitingRestartStage}else{''})",
        "verifiedRestarts=$(if($state.ContainsKey('verifiedRestarts')){$state.verifiedRestarts}else{'unknown'})",
        "sessionsOpened=$(if($state.ContainsKey('sessionsOpened')){$state.sessionsOpened}else{'unknown'})",
        "preimageExists=$(Test-Path -LiteralPath $preimagePath)",
        "journalExists=$(Test-Path -LiteralPath $journalPath)",
        "receiptExists=$(Test-Path -LiteralPath $receiptPath)",
        "hasPhysicalAuthoringReceipt=$($receipts.Contains('PHYSICAL_AUTHORING_COMPLETE'))",
        "hasPersistReceipt=$($receipts.Contains('SAVE_FLUSH_COMPLETE'))",
        "hasRestoreReceipt=$($receipts.Contains('RESTORE_COMPLETE'))",
        "captured=$(Get-Date -Format o)"
    ) | Set-Content -LiteralPath $diag -Encoding ASCII
    if(Test-Path -LiteralPath $single){
        Copy-Item -LiteralPath $single -Destination (Join-Path $outRoot 'existing-single-chunk-state') -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Get-JournalStageLocal {
    if(-not (Test-Path -LiteralPath $journalPath)){ return 'DISCOVERED' }
    $last=Get-Content -LiteralPath $journalPath -Tail 1 -ErrorAction SilentlyContinue
    if($last -match '\t([A-Z_]+)\t([A-Z_]+)\t'){ return $Matches[2] }
    return 'UNKNOWN'
}

function Test-WorldLockReleased {
    $lock=Join-Path $worldPath 'session.lock'
    if(-not (Test-Path -LiteralPath $lock)){ return $true }
    try {
        $fs=[IO.File]::Open($lock,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
        $fs.Close()
        return $true
    } catch { return $false }
}

function Get-TargetMinecraftProcesses {
    $needle=$ProfilePath.ToLowerInvariant()
    try {
        return @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -in @('java.exe','javaw.exe') -and
            -not [string]::IsNullOrWhiteSpace([string]$_.CommandLine) -and
            ([string]$_.CommandLine).ToLowerInvariant().Contains($needle)
        })
    } catch { return @() }
}

function Reset-ModrinthLauncherSafely {
    if(-not (Test-WorldLockReleased)){
        throw 'LAUNCHER RESET REFUSED: world lock is held.'
    }
    $minecraft=@(Get-TargetMinecraftProcesses)
    if($minecraft.Count -gt 0){
        throw "LAUNCHER RESET REFUSED: target Minecraft process still exists: $((@($minecraft | ForEach-Object { $_.ProcessId })) -join ',')"
    }

    $launchers=@()
    try {
        $launchers=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -in @('Modrinth App.exe','modrinth-app.exe','ModrinthApp.exe') -or
            ([string]$_.Name -like 'Modrinth*')
        })
    } catch {}

    foreach($p in $launchers){
        try {
            Write-Host "RECOVERY LAUNCHER RESET: stopping stale Modrinth PID $($p.ProcessId)."
            Stop-Process -Id ([int]$p.ProcessId) -Force -ErrorAction Stop
        } catch {
            Write-Warning "Could not stop stale Modrinth PID $($p.ProcessId): $($_.Exception.Message)"
        }
    }
    Start-Sleep -Seconds 5
}

function Wait-WorldClosed([int]$TimeoutSeconds = 180) {
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    while((Get-Date) -lt $deadline){
        if(Test-WorldLockReleased){ return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Write-RecoveryConfig {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $configPath) | Out-Null
    @(
        'mode=CORE_AUTHORING',
        'canvasSize=20000',
        'centerX=0',
        'centerZ=0',
        'waterSurfaceY=62',
        'oceanFloorY=25',
        'oceanFloorVariation=5',
        'expansionEnabled=false',
        'singleChunkEnabled=true',
        "singleChunkX=$ChunkX",
        "singleChunkZ=$ChunkZ",
        ('singleChunkConfirm=ERASE_CHUNK_{0}_{1}' -f $ChunkX,$ChunkZ),
        'maxBlockWritesPerTick=256',
        'maxChecksPerTick=1024',
        'stageWallBudgetMicros=3000',
        'physicalSettleTicks=40',
        'lightSettleTicks=40',
        'acceptanceHarnessEnabled=true'
    ) | Set-Content -LiteralPath $configPath -Encoding ASCII
}

function Assert-RecoveryStateSafe {
    if(-not (Test-Path -LiteralPath $single)){ return }
    $stage=Get-JournalStageLocal
    $state=Read-Properties $statePath
    $existingX=if($state.ContainsKey('chunkX')){[int]$state.chunkX}else{$null}
    $existingZ=if($state.ContainsKey('chunkZ')){[int]$state.chunkZ}else{$null}
    if($stage -eq 'COMPLETE'){
        $archive=Join-Path $outRoot ("previous-complete-{0}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
        Copy-Item -LiteralPath $single -Destination $archive -Recurse -Force
        Remove-Item -LiteralPath $single -Recurse -Force
        return
    }
    if($null -ne $existingX -and ($existingX -ne $ChunkX -or $existingZ -ne $ChunkZ)){
        if($stage -in @('DISCOVERED','LOADED')){
            Write-ExistingStateDiagnostic -Reason 'foreign-pre-mutation-state'
            $archive=Join-Path $outRoot ("previous-safe-premutation-{0}-{1}-{2}" -f $existingX,$existingZ,(Get-Date -Format 'yyyyMMdd-HHmmss'))
            Copy-Item -LiteralPath $single -Destination $archive -Recurse -Force
            Remove-Item -LiteralPath $single -Recurse -Force
            return
        }
        if(Test-Path -LiteralPath $preimagePath){
            Write-ExistingStateDiagnostic -Reason 'foreign-interrupted-recoverable-preimage'
            throw "RECOVERY SAFETY STOP: interrupted recoverable operation exists at $existingX,$existingZ stage=$stage. Resume/restore it before starting $ChunkX,$ChunkZ."
        }
        Write-ExistingStateDiagnostic -Reason 'foreign-interrupted-no-preimage'
        throw "RECOVERY SAFETY STOP: legacy/interrupted operation exists at $existingX,$existingZ stage=$stage with no recovery preimage. Refusing further mutation until classified."
    }
    if($stage -notin @('DISCOVERED','LOADED','UNKNOWN') -and -not (Test-Path -LiteralPath $preimagePath)){
        Write-ExistingStateDiagnostic -Reason 'requested-interrupted-no-preimage'
        throw "RECOVERY SAFETY STOP: interrupted stage $stage has no restore preimage. Refusing further mutation."
    }
}

function New-RecoveryControllerCopy([string]$Body,[string]$Destination) {
    $controllerText=Get-Content -LiteralPath $controller -Raw
    $tokens=$null; $errors=$null
    $ast=[System.Management.Automation.Language.Parser]::ParseInput($controllerText,[ref]$tokens,[ref]$errors)
    if($errors.Count -gt 0){ throw 'Installed launcher controller does not parse.' }
    $fn=@($ast.FindAll({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-PermanentAuto'},$true))
    if($fn.Count -ne 1){ throw "Expected exactly one Invoke-PermanentAuto function; found $($fn.Count)." }
    $replacement='function Invoke-PermanentAuto {' + [Environment]::NewLine + $Body + [Environment]::NewLine + '}'
    $extent=$fn[0].Extent
    $patched=$controllerText.Remove($extent.StartOffset,$extent.EndOffset-$extent.StartOffset).Insert($extent.StartOffset,$replacement)
    Set-Content -LiteralPath $Destination -Value $patched -Encoding UTF8
    $tokens=$null; $errors=$null
    [void][System.Management.Automation.Language.Parser]::ParseFile($Destination,[ref]$tokens,[ref]$errors)
    if($errors.Count -gt 0){ throw "Temporary recovery controller does not parse: $Destination" }
}

if(-not (Test-Path -LiteralPath $controller)){ throw "Launcher controller missing: $controller" }
if(-not (Test-Path -LiteralPath $worldPath)){ throw "Test world missing: $worldPath" }

Assert-RecoveryStateSafe
Write-RecoveryConfig

$launchBody = "    `$worldPath = Join-Path `$ProfilePath ('saves\{0}' -f `$WorldName)" + [Environment]::NewLine + "    Start-DisposableWorld `$worldPath"
$closeBody = "    Ensure-ProfileMinecraftClosed 'one-chunk recovery restart hold'"
New-RecoveryControllerCopy -Body $launchBody -Destination $tempController
$closeTemp=Join-Path $outRoot 'recovery-close-controller.ps1'
New-RecoveryControllerCopy -Body $closeBody -Destination $closeTemp

Start-Transcript -LiteralPath $transcript -Force | Out-Null
try {
    $previousStage=Get-JournalStageLocal
    $observedStages=New-Object System.Collections.Generic.List[string]
    for($session=1; $session -le $MaxSessions; $session++){
        Write-Host "RECOVERY SESSION $session/$MaxSessions stageBefore=$previousStage"
        if(-not (Test-WorldLockReleased)){ throw 'RECOVERY SAFETY STOP: world lock held before launch.' }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $tempController -Action PermanentAuto -ProfilePath $ProfilePath -WorldName $WorldName
        if($LASTEXITCODE -ne 0){
            if(@(Get-TargetMinecraftProcesses).Count -eq 0 -and (Test-WorldLockReleased)){
                Write-Warning 'RECOVERY LAUNCHER: no Minecraft activity after bounded Modrinth launch attempts; resetting stale launcher once.'
                Reset-ModrinthLauncherSafely
                & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $tempController -Action PermanentAuto -ProfilePath $ProfilePath -WorldName $WorldName
            }
            if($LASTEXITCODE -ne 0){ throw "Recovery launcher failed with exit $LASTEXITCODE after one safe launcher reset" }
        }
        $deadline=(Get-Date).AddSeconds($PerStageTimeoutSeconds)
        $holdSeen=$false
        while((Get-Date) -lt $deadline){
            $stage=Get-JournalStageLocal
            $state=Read-Properties $statePath
            if($stage -eq 'FAILED'){ throw 'RECOVERY RUNTIME FAILED: durable journal reached FAILED.' }
            if($state.ContainsKey('awaitingRestartStage') -and -not [string]::IsNullOrWhiteSpace([string]$state.awaitingRestartStage)){
                if([string]$state.awaitingRestartStage -ne $stage){
                    throw "RECOVERY INVARIANT FAILED: awaitingRestartStage=$($state.awaitingRestartStage) journalStage=$stage"
                }
                $holdSeen=$true
                break
            }
            if($stage -eq 'COMPLETE' -and $state.ContainsKey('finalRestartVerified') -and [string]$state.finalRestartVerified -eq 'true'){ break }
            Start-Sleep -Seconds 2
        }
        $stage=Get-JournalStageLocal
        $state=Read-Properties $statePath
        if(-not $observedStages.Contains($stage)){ [void]$observedStages.Add($stage) }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $closeTemp -Action PermanentAuto -ProfilePath $ProfilePath -WorldName $WorldName
        if($LASTEXITCODE -ne 0){ throw "Recovery clean-close primitive failed with exit $LASTEXITCODE" }
        if(-not (Wait-WorldClosed)){ throw 'RECOVERY SAFETY STOP: world did not close after restart hold.' }
        if($stage -eq 'COMPLETE'){
            $state=Read-Properties $statePath
            if($state.ContainsKey('finalRestartVerified') -and [string]$state.finalRestartVerified -eq 'true'){
                $required=@('LOADED','PREIMAGE_CAPTURED','PHYSICAL_AUTHORED','PHYSICAL_SETTLED','PERSISTED','LIGHTING_SETTLED','VERIFIED','RESTORED','RESTORE_VERIFIED','COMPLETE')
                foreach($requiredStage in $required){
                    if(-not $observedStages.Contains($requiredStage)){ throw "RECOVERY PROOF INCOMPLETE: did not observe durable stage $requiredStage." }
                }
                if(Test-Path -LiteralPath $preimagePath){ throw 'RECOVERY PROOF FAILED: consumed preimage still exists after COMPLETE.' }
                $receipts=if(Test-Path -LiteralPath $receiptPath){Get-Content -LiteralPath $receiptPath -Raw}else{''}
                foreach($needle in @('PREIMAGE_CAPTURED','RESTORE_COMPLETE','RESTORE_VERIFIED','TICKET_RELEASED')){
                    if(-not $receipts.Contains($needle)){ throw "RECOVERY PROOF FAILED: missing receipt $needle" }
                }
                Copy-Item -LiteralPath $single -Destination (Join-Path $outRoot 'single-chunk-final') -Recurse -Force
                Copy-Item -LiteralPath $configPath -Destination (Join-Path $outRoot 'oceancanvas-core.properties') -Force
                @(
                    'verdict=PASS',
                    "runtime=$ExpectedRuntime",
                    "chunk=$ChunkX,$ChunkZ",
                    'lifecycle=LOADED,PREIMAGE_CAPTURED,PHYSICAL_AUTHORED,PHYSICAL_SETTLED,PERSISTED,LIGHTING_SETTLED,VERIFIED,RESTORED,RESTORE_VERIFIED,COMPLETE',
                    "verifiedRestarts=$($state.verifiedRestarts)",
                    "sessionsOpened=$($state.sessionsOpened)",
                    'finalRestartVerified=true',
                    'preimageConsumed=true',
                    "completed=$(Get-Date -Format o)"
                ) | Set-Content -LiteralPath (Join-Path $outRoot 'VERDICT.txt') -Encoding ASCII
                Write-Host 'RECOVERY_ONE_CHUNK_PASS'
                exit 0
            }
        }
        if(-not $holdSeen -and $stage -ne 'COMPLETE'){ throw "RECOVERY TIMEOUT: no restart hold reached within $PerStageTimeoutSeconds seconds; stage=$stage" }
        $previousStage=$stage
    }
    throw "RECOVERY INCOMPLETE: exceeded $MaxSessions sessions."
}
catch {
    @(
        'verdict=FAIL',
        "runtime=$ExpectedRuntime",
        "chunk=$ChunkX,$ChunkZ",
        "stage=$(Get-JournalStageLocal)",
        "error=$($_.Exception.Message)",
        "failed=$(Get-Date -Format o)"
    ) | Set-Content -LiteralPath (Join-Path $outRoot 'VERDICT.txt') -Encoding ASCII
    if(Test-Path -LiteralPath $single){
        Copy-Item -LiteralPath $single -Destination (Join-Path $outRoot 'single-chunk-failure') -Recurse -Force -ErrorAction SilentlyContinue
    }
    $automationLog=Join-Path $env:LOCALAPPDATA 'OceanCanvas\PermanentGateHarness\automation.log'
    if(Test-Path -LiteralPath $automationLog){
        Copy-Item -LiteralPath $automationLog -Destination (Join-Path $outRoot 'controller-automation.log') -Force -ErrorAction SilentlyContinue
    }
    $latestLog=Join-Path $ProfilePath 'logs\latest.log'
    if(Test-Path -LiteralPath $latestLog){
        Copy-Item -LiteralPath $latestLog -Destination (Join-Path $outRoot 'minecraft-latest.log') -Force -ErrorAction SilentlyContinue
    }
    throw
}
finally {
    try { Stop-Transcript | Out-Null } catch {}
}
