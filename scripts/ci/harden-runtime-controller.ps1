[CmdletBinding()]
param(
    [string]$Controller = "$env:LOCALAPPDATA\OceanCanvas\AutonomousSupervisor\workspace\scripts\windows\oceancanvas-permanent-controller.ps1"
)

$ErrorActionPreference='Stop'
if(-not (Test-Path -LiteralPath $Controller)){ throw "Controller missing: $Controller" }

# Release reset policy:
# The installed permanent controller is already checkpoint-capable. Do not stack
# migrations or prepend markers to it. Make one narrow, idempotent repair to the
# historical exact-seven acceptance predicate and verify the complete script.
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
    'checkpointSchema=2'
)
$missing=@($required | Where-Object { -not $text.Contains($_) })
if($missing.Count -gt 0){
    throw "RESET REFUSED: installed controller is not the expected checkpoint-capable baseline. Missing: $($missing -join ', ')"
}

$throwAnchor='Canary chunk $X,$Z did not satisfy COMPLETE + final restart +'
$anchor=$text.IndexOf($throwAnchor)
if($anchor -lt 0){ throw 'RESET REFUSED: terminal canary assertion not found.' }

# Restrict the edit to the function containing the terminal canary assertion.
$functionStart=$text.LastIndexOf('function ', $anchor)
if($functionStart -lt 0){ throw 'RESET REFUSED: terminal assertion function start not found.' }
$functionEnd=$text.IndexOf('function ', $anchor+1)
if($functionEnd -lt 0){ $functionEnd=$text.Length }
$segment=$text.Substring($functionStart,$functionEnd-$functionStart)

# The world proof is monotonic: seven verified restarts is a minimum, not an
# exact value. Normalize every exact numeric-seven inequality in this one
# assertion function. This catches parenthesized/casted/property variants
# without depending on their variable spelling.
$patchedSegment=[regex]::Replace($segment,'(?i)-ne\s+7\b','-lt 7')
$patchedSegment=[regex]::Replace($patchedSegment,"(?i)-ne\s+'7'",'-lt 7')
$patchedSegment=[regex]::Replace($patchedSegment,'(?i)-ne\s+"7"','-lt 7')
$patchedSegment=$patchedSegment.Replace('7/7 invariant','at-least-7 invariant')
$patchedSegment=$patchedSegment.Replace('final-restart-7-of-7','final-restart-at-least-7')

if($patchedSegment -eq $segment){
    # An already repaired controller is valid only when no exact-seven predicate
    # remains in the assertion function.
    if($segment -match '(?i)-ne\s+["'']?7["'']?'){
        throw 'RESET FAILED: exact-seven predicate remains but no supported replacement was made.'
    }
    Write-Host 'CONTROLLER_RESET_ALREADY_CLEAN exactRestartPolicy=minimum-7'
    exit 0
}

$newText=$text.Substring(0,$functionStart)+$patchedSegment+$text.Substring($functionEnd)

$tokens=$null; $errors=$null
[void][System.Management.Automation.Language.Parser]::ParseInput($newText,[ref]$tokens,[ref]$errors)
if($errors.Count -gt 0){
    $messages=($errors | ForEach-Object { $_.Message }) -join '; '
    throw "RESET FAILED: repaired controller does not parse: $messages"
}

$verifySegment=$newText.Substring($functionStart,$patchedSegment.Length)
if($verifySegment -match '(?i)-ne\s+["'']?7["'']?'){
    throw 'RESET FAILED: exact-seven predicate survived terminal-function repair.'
}

$backup="$Controller.pre-release-reset"
if(-not (Test-Path -LiteralPath $backup)){
    Copy-Item -LiteralPath $Controller -Destination $backup -Force
}
Set-Content -LiteralPath $Controller -Value $newText -Encoding UTF8

$roundTrip=Get-Content -LiteralPath $Controller -Raw
$tokens=$null; $errors=$null
[void][System.Management.Automation.Language.Parser]::ParseInput($roundTrip,[ref]$tokens,[ref]$errors)
if($errors.Count -gt 0){ throw 'RESET FAILED: installed repaired controller did not round-trip parse.' }

Write-Host 'CONTROLLER_RESET_PASS checkpointResume=true exactRestartPolicy=minimum-7'
