[CmdletBinding()]
param(
    [string]$Controller = "$env:LOCALAPPDATA\OceanCanvas\AutonomousSupervisor\workspace\scripts\windows\oceancanvas-permanent-controller.ps1"
)

$ErrorActionPreference='Stop'
if(-not (Test-Path -LiteralPath $Controller)){ throw "Controller missing: $Controller" }

$text=Get-Content -LiteralPath $Controller -Raw
if($text.Contains('OC_CHUNK_CHECKPOINT_RESUME_V1')){
    Write-Host 'CONTROLLER_HARDENING_ALREADY_APPLIED'
    exit 0
}

$start=$text.IndexOf('function Run-PermanentGate($Gate, [string]$WorldPath) {')
$end=$text.IndexOf('function Invoke-PermanentAuto {',$start)
if($start -lt 0 -or $end -lt 0){ throw 'Permanent gate function boundaries not found.' }

$replacement=@'
# OC_CHUNK_CHECKPOINT_RESUME_V1
function Test-PersistedGateChunkPass([string]$GateRoot,[int]$X,[int]$Z) {
    $verdictPath=Join-Path $GateRoot ("chunk-$X-$Z\VERDICT.txt")
    if(-not (Test-Path -LiteralPath $verdictPath)){ return $false }
    $p=Read-SimpleProperties $verdictPath
    if(-not $p.ContainsKey('verdict') -or $p['verdict'] -ne 'PASS'){ return $false }
    if(-not $p.ContainsKey('build') -or $p['build'] -ne $ExpectedModVersion){ return $false }
    if(-not $p.ContainsKey('chunk') -or $p['chunk'] -ne "$X,$Z"){ return $false }
    return $true
}

function Test-CurrentChunkResumeEligible([string]$WorldPath,[int]$X,[int]$Z) {
    $single=Join-Path $WorldPath 'oceancanvas-core\single-chunk'
    if(-not (Test-Path -LiteralPath $single)){ return $false }

    $build=Get-CampaignBuild $WorldPath
    if($build -ne $ExpectedModVersion){ return $false }

    $stage=Get-JournalStage $WorldPath
    if([string]::IsNullOrWhiteSpace($stage) -or $stage -eq 'FAILED'){ return $false }

    $state=Read-SimpleProperties (Join-Path $single 'acceptance-state.properties')
    if(-not $state.ContainsKey('chunkX') -or -not $state.ContainsKey('chunkZ')){ return $false }
    if(([int]$state['chunkX']) -ne $X -or ([int]$state['chunkZ']) -ne $Z){ return $false }

    return $true
}

function Save-GateProgressCheckpoint($Gate,[string]$GateRoot,[int]$CompletedCount,[int]$CurrentX,[int]$CurrentZ,[string]$State) {
    @(
        "runtime=$ExpectedModVersion",
        "runtimeSha256=$ProvenJarSha256",
        "gate=$($Gate.Id)",
        "completedChunks=$CompletedCount",
        "totalChunks=$($Gate.Chunks.Count)",
        "currentChunk=$CurrentX,$CurrentZ",
        "state=$State",
        "updated=$(Get-Date -Format o)"
    ) | Set-Content -LiteralPath (Join-Path $GateRoot 'PROGRESS.properties') -Encoding ASCII
}

function Run-PermanentGate($Gate, [string]$WorldPath) {
    $gateRoot = Join-Path $PermanentRoot (Join-Path 'evidence' $Gate.Id)
    New-Item -ItemType Directory -Force -Path $gateRoot | Out-Null
    Add-AutomationTrace "PERMANENT-GATE-BEGIN id=$($Gate.Id) label=$($Gate.Label) maxActiveChunks=1 checkpointResume=true"

    $index=0
    foreach($pair in $Gate.Chunks){
        $x=[int]$pair.X; $z=[int]$pair.Z

        if(Test-PersistedGateChunkPass $gateRoot $x $z){
            $index++
            Save-GateProgressCheckpoint $Gate $gateRoot $index $x $z 'PASS-REUSED'
            Write-Ok "Checkpoint reuse: gate $($Gate.Id) chunk $x,$z already has valid PASS evidence; skipping."
            Add-AutomationTrace "CHECKPOINT-REUSE gate=$($Gate.Id) chunk=$x,$z completed=$index/$($Gate.Chunks.Count)"
            continue
        }

        if($index -gt 0){
            Ensure-ProfileMinecraftClosed "permanent gate $($Gate.Id) handoff"
            $deadline=(Get-Date).AddSeconds(90)
            while((Get-Date) -lt $deadline -and -not (Test-WorldClosed $WorldPath)){ Start-Sleep -Milliseconds 500 }
            if(-not (Test-WorldClosed $WorldPath)){ throw "World remained open before arming gate $($Gate.Id) chunk $x,$z" }
        }

        $single=Join-Path $WorldPath 'oceancanvas-core\single-chunk'
        $resume=Test-CurrentChunkResumeEligible $WorldPath $x $z

        if($resume){
            $stage=Get-JournalStage $WorldPath
            $state=Read-SimpleProperties (Join-Path $single 'acceptance-state.properties')
            $restarts=if($state.ContainsKey('verifiedRestarts')){$state['verifiedRestarts']}else{'?'}
            Save-GateProgressCheckpoint $Gate $gateRoot $index $x $z ("RESUME-$stage-$restarts")
            Write-Ok "Checkpoint resume: gate $($Gate.Id) chunk $x,$z stage=$stage verifiedRestarts=$restarts."
            Add-AutomationTrace "CHECKPOINT-RESUME gate=$($Gate.Id) chunk=$x,$z stage=$stage verifiedRestarts=$restarts"
        } else {
            if(Test-Path -LiteralPath $single){
                $archive=Join-Path $gateRoot ("interrupted-{0}-{1}-{2}" -f $x,$z,(Get-Date -Format 'yyyyMMdd-HHmmss'))
                Copy-Item -LiteralPath $single -Destination $archive -Recurse -Force
                Remove-Item -LiteralPath $single -Recurse -Force
                Add-AutomationTrace "CHECKPOINT-ARCHIVE gate=$($Gate.Id) chunk=$x,$z path=$archive"
            }
            $script:ChunkX=$x; $script:ChunkZ=$z
            Invoke-Arm
            Save-GateProgressCheckpoint $Gate $gateRoot $index $x $z 'ARMED'
            Write-PostableLog "LIVE: permanent gate $($Gate.Id) chunk $x,$z armed ($($index+1)/$($Gate.Chunks.Count))" -Quiet
        }

        Start-DisposableWorld $WorldPath
        $verdict=Invoke-Watch
        if($verdict -ne 'PASS'){
            Save-CanaryChunkEvidence $WorldPath $gateRoot $x $z $verdict
            Save-GateProgressCheckpoint $Gate $gateRoot $index $x $z ("STOP-$verdict")
            throw "Permanent gate $($Gate.Id) stopped fail-closed at chunk $x,$z verdict=$verdict"
        }

        Assert-CurrentChunkPass $WorldPath $x $z
        Save-CanaryChunkEvidence $WorldPath $gateRoot $x $z 'PASS'
        $index++
        Save-GateProgressCheckpoint $Gate $gateRoot $index $x $z 'PASS'
        Add-AutomationTrace "CHECKPOINT-COMMIT gate=$($Gate.Id) chunk=$x,$z completed=$index/$($Gate.Chunks.Count)"
    }

    @(
        "runtime=$ExpectedModVersion",
        "runtimeSha256=$ProvenJarSha256",
        "gate=$($Gate.Id)",
        "label=$($Gate.Label)",
        "chunks=$($Gate.Chunks.Count)",
        'maxActiveChunks=1',
        'sequencing=next-not-armed-until-previous-COMPLETE-final-restart-7-of-7',
        'checkpointResume=true',
        'verdict=PASS',
        "completed=$(Get-Date -Format o)"
    ) | Set-Content -LiteralPath (Join-Path $gateRoot 'GATE-SUMMARY.txt') -Encoding ASCII

    Save-GateProgressCheckpoint $Gate $gateRoot $Gate.Chunks.Count 0 0 'GATE-PASS'
    Add-AutomationTrace "PERMANENT-GATE-PASS id=$($Gate.Id) chunks=$($Gate.Chunks.Count)"
}

'@

$newText=$text.Substring(0,$start)+$replacement+$text.Substring($end)

$tokens=$null; $errors=$null
[void][System.Management.Automation.Language.Parser]::ParseInput($newText,[ref]$tokens,[ref]$errors)
if($errors.Count -gt 0){
    $errors | ForEach-Object { Write-Error $_.Message }
    throw 'Patched controller failed PowerShell parser validation.'
}

$backup="$Controller.pre-checkpoint-resume"
if(-not (Test-Path -LiteralPath $backup)){ Copy-Item -LiteralPath $Controller -Destination $backup -Force }
Set-Content -LiteralPath $Controller -Value $newText -Encoding UTF8
Write-Host 'CONTROLLER_HARDENING_PASS checkpointResume=true'
