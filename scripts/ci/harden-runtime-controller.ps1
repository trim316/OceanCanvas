[CmdletBinding()]
param(
    [string]$Controller = "$env:LOCALAPPDATA\OceanCanvas\AutonomousSupervisor\workspace\scripts\windows\oceancanvas-permanent-controller.ps1"
)

$ErrorActionPreference='Stop'
if(-not (Test-Path -LiteralPath $Controller)){ throw "Controller missing: $Controller" }

$text=Get-Content -LiteralPath $Controller -Raw

# v0.2.26 release migration: older permanent controllers required exactly
# seven verified restarts. A resumed COMPLETE campaign can legitimately have
# more than seven verified restarts; proof is monotonic and must accept >= 7.
$releaseMarker='OC_RELEASE_CONTROLLER_V026_FIX_V1'
if(-not $text.Contains($releaseMarker)){
    $patterns=@(
        @{ Pattern='\$verified\s+-ne\s+7'; Replacement='$verified -lt 7' },
        @{ Pattern='\$verifiedRestarts\s+-ne\s+7'; Replacement='$verifiedRestarts -lt 7' },
        @{ Pattern='\[int\]\s*\$state\[''verifiedRestarts''\]\s+-ne\s+7'; Replacement='[int]$state[''verifiedRestarts''] -lt 7' }
    )
    $restartComparisonPatched=$false
    foreach($p in $patterns){
        $next=[regex]::Replace($text,$p.Pattern,$p.Replacement)
        if($next -ne $text){ $restartComparisonPatched=$true; $text=$next }
    }

    if(-not $restartComparisonPatched){
        $anchor=$text.IndexOf('did not satisfy COMPLETE + final restart + 7/7 invariant')
        if($anchor -lt 0){
            throw 'Release controller migration could not find the restart invariant anchor.'
        }
        $windowStart=[Math]::Max(0,$anchor-1600)
        $window=$text.Substring($windowStart,$anchor-$windowStart)
        throw "Release controller migration found the invariant but no supported exact-seven comparison. Context: $window"
    }

    $text=$text.Replace('7/7 invariant','at-least-7 invariant')
    $text=$text.Replace('final-restart-7-of-7','final-restart-at-least-7')
    $text=("# $releaseMarker"+[Environment]::NewLine+$text)

    $tokens=$null; $errors=$null
    [void][System.Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if($errors.Count -gt 0){
        $errors | ForEach-Object { Write-Error $_.Message }
        throw 'v0.2.26 release controller migration failed parser validation.'
    }

    $backup="$Controller.pre-v0.2.26-release-fix"
    if(-not (Test-Path -LiteralPath $backup)){ Copy-Item -LiteralPath $Controller -Destination $backup -Force }
    Set-Content -LiteralPath $Controller -Value $text -Encoding UTF8
    Write-Host 'CONTROLLER_RELEASE_MIGRATION_PASS verifiedRestartsPolicy=at-least-7'
}
$requiredInstalledTokens=@(
    'OC_CHUNK_CHECKPOINT_RESUME_V1',
    'Test-PersistedGateChunkPass',
    'Test-CurrentChunkResumeEligible',
    'Save-GateProgressCheckpoint',
    'CHECKPOINT-REUSE',
    'CHECKPOINT-RESUME',
    'CHECKPOINT-COMMIT',
    'checkpointSchema=2'
)

if($text.Contains('OC_CHUNK_CHECKPOINT_RESUME_V1')){
    $tokens=$null; $errors=$null
    [void][System.Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if($errors.Count -gt 0){
        $errors | ForEach-Object { Write-Error $_.Message }
        throw 'Existing checkpoint-hardened controller no longer parses.'
    }

    $missing=@($requiredInstalledTokens | Where-Object { -not $text.Contains($_) })

    # Known migration: the original checkpoint-resume controller (schema 1)
    # already contains every behavioral token except the schema marker. Upgrade
    # it in place rather than trapping an otherwise valid runner forever.
    if($missing.Count -eq 1 -and $missing[0] -eq 'checkpointSchema=2'){
        $functionNeedle='function Save-GateProgressCheckpoint($Gate,[string]$GateRoot,[int]$CompletedCount,[int]$CurrentX,[int]$CurrentZ,[string]$State) {'
        $functionStart=$text.IndexOf($functionNeedle)
        if($functionStart -lt 0){
            throw 'Schema-1 checkpoint controller detected, but the progress-checkpoint function is missing.'
        }
        $arrayStart=$text.IndexOf('@(',$functionStart)
        if($arrayStart -lt 0){
            throw 'Schema-1 checkpoint controller detected, but the progress-checkpoint array is missing.'
        }
        $lineEnd=$text.IndexOf("`n",$arrayStart)
        if($lineEnd -lt 0){
            throw 'Schema-1 checkpoint controller detected, but its progress-checkpoint array has no line boundary.'
        }
        $newline=if($text.Contains("`r`n")){"`r`n"}else{"`n"}
        $text=$text.Insert($lineEnd+1,"        'checkpointSchema=2',"+$newline)

        $tokens=$null; $errors=$null
        [void][System.Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
        if($errors.Count -gt 0){
            $errors | ForEach-Object { Write-Error $_.Message }
            throw 'Schema-1 to schema-2 controller migration failed parser validation.'
        }

        $backup="$Controller.pre-checkpoint-schema2"
        if(-not (Test-Path -LiteralPath $backup)){ Copy-Item -LiteralPath $Controller -Destination $backup -Force }
        Set-Content -LiteralPath $Controller -Value $text -Encoding UTF8

        $verify=Get-Content -LiteralPath $Controller -Raw
        $stillMissing=@($requiredInstalledTokens | Where-Object { -not $verify.Contains($_) })
        if($stillMissing.Count -gt 0){
            throw "Schema migration verification failed. Missing: $($stillMissing -join ', ')"
        }

        Write-Host 'CONTROLLER_HARDENING_MIGRATED fromSchema=1 toSchema=2 verified=true'
        exit 0
    }

    if($missing.Count -gt 0){
        throw "Controller contains checkpoint marker but is stale/partial in an unrecognized way. Missing: $($missing -join ', ')"
    }

    Write-Host 'CONTROLLER_HARDENING_ALREADY_APPLIED verified=true schema=2'
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
        'checkpointSchema=2',
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

$installed=Get-Content -LiteralPath $Controller -Raw
$missing=@($requiredInstalledTokens | Where-Object { -not $installed.Contains($_) })
if($missing.Count -gt 0){
    throw "Post-write controller verification failed. Missing: $($missing -join ', ')"
}
$tokens=$null; $errors=$null
[void][System.Management.Automation.Language.Parser]::ParseInput($installed,[ref]$tokens,[ref]$errors)
if($errors.Count -gt 0){
    $errors | ForEach-Object { Write-Error $_.Message }
    throw 'Installed checkpoint-hardened controller failed parser verification.'
}

Write-Host 'CONTROLLER_HARDENING_PASS checkpointResume=true schema=2 verified=true'
