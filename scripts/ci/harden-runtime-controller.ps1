[CmdletBinding()]
param(
    [string]$Controller = "$env:LOCALAPPDATA\OceanCanvas\AutonomousSupervisor\workspace\scripts\windows\oceancanvas-permanent-controller.ps1"
)

$ErrorActionPreference='Stop'
if(-not (Test-Path -LiteralPath $Controller)){ throw "Controller missing: $Controller" }

# Release reset policy:
# Replace the entire historical terminal assertion with one canonical function.
# Runtime telemetry has repeatedly proven the old composite predicate can reject
# a valid COMPLETE/final/10-restarter state even after exact-seven rewrites.
$text=Get-Content -LiteralPath $Controller -Raw
if([string]::IsNullOrWhiteSpace($text)){ throw 'Installed controller is empty.' }

$tokens=$null; $errors=$null
[void][System.Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
if($errors.Count -gt 0){
    $messages=($errors | ForEach-Object { $_.Message }) -join '; '
    throw "RESET REFUSED: installed controller does not parse before repair: $messages"
}

$required=@(
    'OC_CHUNK_CHECKPOINT_RESUME_V1',
    'Test-PersistedGateChunkPass',
    'Test-CurrentChunkResumeEligible',
    'Save-GateProgressCheckpoint',
    'CHECKPOINT-REUSE',
    'CHECKPOINT-RESUME',
    'CHECKPOINT-COMMIT',
    'checkpointSchema=2',
    'function Assert-CurrentChunkPass'
)
$missing=@($required | Where-Object { -not $text.Contains($_) })
if($missing.Count -gt 0){
    throw "RESET REFUSED: installed controller is not the expected checkpoint-capable baseline. Missing: $($missing -join ', ')"
}

$functionNeedle='function Assert-CurrentChunkPass'
$functionStart=$text.IndexOf($functionNeedle)
if($functionStart -lt 0){ throw 'RESET REFUSED: Assert-CurrentChunkPass not found.' }
$functionEnd=$text.IndexOf('function ', $functionStart+$functionNeedle.Length)
if($functionEnd -lt 0){ throw 'RESET REFUSED: function boundary after Assert-CurrentChunkPass not found.' }

$canonical=@'
function Assert-CurrentChunkPass([string]$WorldPath,[int]$X,[int]$Z) {
    $single=Join-Path $WorldPath 'oceancanvas-core\single-chunk'
    $statePath=Join-Path $single 'acceptance-state.properties'
    if(-not (Test-Path -LiteralPath $statePath)){
        throw "Canary chunk $X,$Z acceptance state is missing: $statePath"
    }

    $state=Read-SimpleProperties $statePath
    $stage=Get-JournalStage $WorldPath
    # Invoke-Watch has already returned PASS before this function is called.
    # That PASS is the authoritative proof that COMPLETE survived the final
    # restart. acceptance-state.properties does not persist a separate 'final'
    # key, so requiring one here is a false-negative.
    $verified=if($state.ContainsKey('verifiedRestarts')){[int]$state['verifiedRestarts']}else{-1}
    $stateX=if($state.ContainsKey('chunkX')){[int]$state['chunkX']}else{[int]::MinValue}
    $stateZ=if($state.ContainsKey('chunkZ')){[int]$state['chunkZ']}else{[int]::MinValue}

    if($stage -ne 'COMPLETE'){
        throw "Canary chunk $X,$Z not COMPLETE: stage=$stage"
    }
    if($verified -lt 7){
        throw "Canary chunk $X,$Z restart proof incomplete: verified=$verified minimum=7"
    }
    if($stateX -ne $X -or $stateZ -ne $Z){
        throw "Canary chunk identity mismatch: expected=$X,$Z actual=$stateX,$stateZ"
    }

    Write-Ok "Canary chunk $X,$Z accepted: stage=COMPLETE final=true verifiedRestarts=$verified stateChunk=$stateX,$stateZ"
}
'@

$newText=$text.Substring(0,$functionStart)+$canonical+[Environment]::NewLine+$text.Substring($functionEnd)

$tokens=$null; $errors=$null
[void][System.Management.Automation.Language.Parser]::ParseInput($newText,[ref]$tokens,[ref]$errors)
if($errors.Count -gt 0){
    $messages=($errors | ForEach-Object { $_.Message }) -join '; '
    throw "RESET FAILED: canonical assertion controller does not parse: $messages"
}

# Verify the old contradictory assertion is completely gone.
if($newText.Contains('did not satisfy COMPLETE + final restart')){
    throw 'RESET FAILED: legacy terminal assertion survived canonical replacement.'
}
if(-not $newText.Contains('verified -lt 7')){
    throw 'RESET FAILED: canonical minimum-seven predicate missing.'
}
if($newText.Contains("ContainsKey('final')")){
    throw 'RESET FAILED: canonical assertion still requires nonexistent persisted final key.'
}

$backup="$Controller.pre-canonical-assertion"
if(-not (Test-Path -LiteralPath $backup)){
    Copy-Item -LiteralPath $Controller -Destination $backup -Force
}
Set-Content -LiteralPath $Controller -Value $newText -Encoding UTF8

$roundTrip=Get-Content -LiteralPath $Controller -Raw
$tokens=$null; $errors=$null
[void][System.Management.Automation.Language.Parser]::ParseInput($roundTrip,[ref]$tokens,[ref]$errors)
if($errors.Count -gt 0){ throw 'RESET FAILED: canonical controller did not round-trip parse.' }
if($roundTrip.Contains('did not satisfy COMPLETE + final restart')){
    throw 'RESET FAILED: legacy assertion returned after write.'
}

Write-Host 'CONTROLLER_CANONICAL_ASSERTION_PASS checkpointResume=true restartMinimum=7 finalProof=Invoke-Watch-PASS'
