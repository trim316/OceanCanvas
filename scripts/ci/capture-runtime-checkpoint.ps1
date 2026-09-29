[CmdletBinding()]
param(
    [string]$ProfilePath = "$env:APPDATA\ModrinthApp\profiles\Fabulously Optimized",
    [string]$WorldName = 'New World',
    [string]$OutputRoot = "$env:GITHUB_WORKSPACE\runtime-checkpoint"
)

$ErrorActionPreference='Stop'
$permanentRoot=Join-Path $env:LOCALAPPDATA 'OceanCanvas\PermanentGateHarness'
$world=Join-Path $ProfilePath ("saves\{0}" -f $WorldName)
$single=Join-Path $world 'oceancanvas-core\single-chunk'
$latestLog=Join-Path $ProfilePath 'logs\latest.log'
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$dest=Join-Path $OutputRoot $stamp
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$ledger=Join-Path $permanentRoot 'gate-ledger.properties'
if(Test-Path -LiteralPath $ledger){ Copy-Item -LiteralPath $ledger -Destination $dest -Force }

$evidence=Join-Path $permanentRoot 'evidence'
if(Test-Path -LiteralPath $evidence){ Copy-Item -LiteralPath $evidence -Destination (Join-Path $dest 'evidence') -Recurse -Force }

if(Test-Path -LiteralPath $single){ Copy-Item -LiteralPath $single -Destination (Join-Path $dest 'single-chunk') -Recurse -Force }
if(Test-Path -LiteralPath $latestLog){ Copy-Item -LiteralPath $latestLog -Destination (Join-Path $dest 'latest.log') -Force }

$controllerLog=Join-Path $permanentRoot 'github-controller-attempt.log'
if(Test-Path -LiteralPath $controllerLog){ Copy-Item -LiteralPath $controllerLog -Destination $dest -Force }

$completed=@()
if(Test-Path -LiteralPath $ledger){
    foreach($line in Get-Content -LiteralPath $ledger -ErrorAction SilentlyContinue){
        if($line -match '^completed=(.*)$'){
            $completed=@($Matches[1].Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        }
    }
}

$passReceipts=@()
if(Test-Path -LiteralPath $evidence){
    $passReceipts=@(Get-ChildItem -LiteralPath $evidence -Filter 'VERDICT.txt' -File -Recurse -ErrorAction SilentlyContinue | Where-Object {
        (Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue) -match '(?m)^verdict=PASS\s*$'
    })
}

$stage='UNKNOWN'; $verified=''; $awaiting=''; $chunk=''
$statePath=Join-Path $single 'acceptance-state.properties'
if(Test-Path -LiteralPath $statePath){
    $state=@{}
    foreach($line in Get-Content -LiteralPath $statePath){
        if($line -match '^([^#=]+)=(.*)$'){ $state[$Matches[1].Trim()]=$Matches[2].Trim() }
    }
    if($state.ContainsKey('verifiedRestarts')){ $verified=$state['verifiedRestarts'] }
    if($state.ContainsKey('awaitingRestartStage')){ $awaiting=$state['awaitingRestartStage'] }
    if($state.ContainsKey('chunkX') -and $state.ContainsKey('chunkZ')){ $chunk="$($state['chunkX']),$($state['chunkZ'])" }
}
$journal=Join-Path $single 'transitions.journal'
if(Test-Path -LiteralPath $journal){
    $last=Get-Content -LiteralPath $journal -Tail 1 -ErrorAction SilentlyContinue
    if($last -match 'to=([A-Z_]+)'){ $stage=$Matches[1] }
}

@(
    "captured=$(Get-Date -Format o)",
    "completedGates=$($completed -join ',')",
    "passChunkReceipts=$($passReceipts.Count)",
    "currentChunk=$chunk",
    "currentStage=$stage",
    "verifiedRestarts=$verified",
    "awaitingRestartStage=$awaiting",
    "world=$world"
) | Set-Content -LiteralPath (Join-Path $dest 'CHECKPOINT-SUMMARY.properties') -Encoding ASCII

Write-Host "CHECKPOINT_CAPTURED path=$dest passChunkReceipts=$($passReceipts.Count) currentChunk=$chunk stage=$stage verifiedRestarts=$verified"
