[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CandidateDir,
    [string]$ProfilePath = "$env:APPDATA\ModrinthApp\profiles\Fabulously Optimized"
)

$ErrorActionPreference = 'Stop'
$manifestPath = Join-Path $CandidateDir 'candidate.properties'
$jar = Get-ChildItem -LiteralPath $CandidateDir -Filter 'oceancanvas-core-*.jar' -File | Select-Object -First 1
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Candidate manifest missing: $manifestPath" }
if ($null -eq $jar) { throw "Candidate JAR missing under $CandidateDir" }

$manifest = @{}
foreach ($line in Get-Content -LiteralPath $manifestPath) {
    if ($line -match '^([^#=]+)=(.*)$') { $manifest[$Matches[1].Trim()] = $Matches[2].Trim() }
}
foreach ($key in @('runtime','coreVersion','jarSha256','jarName','sourceCommit')) {
    if (-not $manifest.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$manifest[$key])) {
        throw "Candidate manifest missing required key: $key"
    }
}
if ($jar.Name -ne $manifest.jarName) { throw "Candidate JAR name mismatch: artifact=$($jar.Name) manifest=$($manifest.jarName)" }
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash.ToLowerInvariant()
if ($actualHash -ne $manifest.jarSha256.ToLowerInvariant()) { throw "Candidate SHA mismatch: expected=$($manifest.jarSha256) actual=$actualHash" }

$mods = Join-Path $ProfilePath 'mods'
$worldLock = Join-Path $ProfilePath 'saves\New World\session.lock'
$controller = Join-Path $env:LOCALAPPDATA 'OceanCanvas\AutonomousSupervisor\workspace\scripts\windows\oceancanvas-permanent-controller.ps1'
$permanentRoot = Join-Path $env:LOCALAPPDATA 'OceanCanvas\PermanentGateHarness'
$ledger = Join-Path $permanentRoot 'gate-ledger.properties'
$evidence = Join-Path $permanentRoot 'evidence'
$backups = Join-Path $env:LOCALAPPDATA 'OceanCanvas\RuntimeBackups'
$receiptRoot = Join-Path $env:LOCALAPPDATA 'OceanCanvas\RuntimeCandidates'

foreach ($required in @($mods,$controller)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required runtime path missing: $required" }
}

function Get-TargetMinecraft {
    $needle = $ProfilePath.ToLowerInvariant()
    try {
        return @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -in @('java.exe','javaw.exe') -and
            -not [string]::IsNullOrWhiteSpace([string]$_.CommandLine) -and
            ([string]$_.CommandLine).ToLowerInvariant().Contains($needle)
        })
    } catch { return @() }
}

function Test-WorldLockFree {
    if (-not (Test-Path -LiteralPath $worldLock)) { return $true }
    try {
        $fs=[IO.File]::Open($worldLock,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
        $fs.Close(); return $true
    } catch { return $false }
}

$deadline=(Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    $targets=@(Get-TargetMinecraft)
    if ($targets.Count -eq 0 -and (Test-WorldLockFree)) { break }
    foreach ($p in $targets) {
        try {
            $proc=Get-Process -Id ([int]$p.ProcessId) -ErrorAction SilentlyContinue
            if ($proc) { [void]$proc.CloseMainWindow() }
        } catch {}
    }
    Start-Sleep -Seconds 5
}
if (@(Get-TargetMinecraft).Count -gt 0) { throw 'WORLD_SAFETY_RISK: target Minecraft still running; refusing candidate install.' }
if (-not (Test-WorldLockFree)) { throw 'WORLD_SAFETY_RISK: New World session.lock still held; refusing candidate install.' }

New-Item -ItemType Directory -Force -Path $backups,$receiptRoot,$permanentRoot | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$backup=Join-Path $backups $stamp
New-Item -ItemType Directory -Force -Path $backup | Out-Null
$active=@(Get-ChildItem -LiteralPath $mods -File | Where-Object { $_.Name -match '(?i)oceancanvas.*\.jar$' })
foreach ($old in $active) {
    $dst=Join-Path $backup $old.Name
    Copy-Item -LiteralPath $old.FullName -Destination $dst -Force
    if ((Get-FileHash $old.FullName -Algorithm SHA256).Hash -ne (Get-FileHash $dst -Algorithm SHA256).Hash) {
        throw "Backup checksum mismatch: $($old.Name)"
    }
}

$staged=Join-Path $mods (".$($jar.Name).installing")
Copy-Item -LiteralPath $jar.FullName -Destination $staged -Force
if ((Get-FileHash $staged -Algorithm SHA256).Hash.ToLowerInvariant() -ne $actualHash) { throw 'Staged candidate checksum mismatch.' }

foreach ($old in $active) { Remove-Item -LiteralPath $old.FullName -Force }
$target=Join-Path $mods $jar.Name
Move-Item -LiteralPath $staged -Destination $target -Force
if ((Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $actualHash) { throw 'Installed candidate checksum mismatch.' }

$ctl=Get-Content -LiteralPath $controller -Raw
$ctl=[regex]::Replace($ctl,"\$ExpectedVersion = '[^']+'","\$ExpectedVersion = '$($manifest.coreVersion)'",1)
$ctl=[regex]::Replace($ctl,"\$ExpectedModVersion = '[^']+'","\$ExpectedModVersion = '$($manifest.runtime)'",1)
$ctl=[regex]::Replace($ctl,"\$ProvenJarSha256 = '[0-9a-fA-F]{64}'","\$ProvenJarSha256 = '$actualHash'",1)

# Candidate-scoped evidence: G2 is no longer inherited from v0.2.23.
$ctl=$ctl.Replace("        @{ Id='G4'; Label='2x2 / 4 chunks'; Chunks=@(","        @{ Id='G2'; Label='two-chunk baseline'; Chunks=@(`r`n            @{X=0;Z=0}, @{X=1;Z=0}`r`n        ) },`r`n        @{ Id='G4'; Label='2x2 / 4 chunks'; Chunks=@(")
$ctl=$ctl.Replace("if (-not (Test-Path -LiteralPath $GateLedger)) { return @('G2') }","if (-not (Test-Path -LiteralPath $GateLedger)) { return @() }")
$ctl=$ctl.Replace("$ids = @('G2')","$ids = @()")
$ctl=$ctl.Replace('Frozen runtime: v0.2.23. No Gradle build or runtime replacement is permitted.',"Runtime candidate: $($manifest.runtime). No rebuild is permitted during this gate campaign.")
$ctl=$ctl.Replace('PERMANENT GATE LADDER PASS: G2,G4,G9,G16; frozenRuntime=v0.2.23; maxActiveChunks=1',"PERMANENT GATE LADDER PASS: G2,G4,G9,G16; runtime=$($manifest.runtime); maxActiveChunks=1")
Set-Content -LiteralPath $controller -Value $ctl -Encoding UTF8

if (Test-Path -LiteralPath $ledger) {
    Copy-Item -LiteralPath $ledger -Destination (Join-Path $backup 'gate-ledger.previous.properties') -Force
    Remove-Item -LiteralPath $ledger -Force
}
if (Test-Path -LiteralPath $evidence) {
    Move-Item -LiteralPath $evidence -Destination (Join-Path $backup 'gate-evidence.previous') -Force
}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

$receipt=Join-Path $receiptRoot ("{0}.properties" -f $manifest.runtime)
@(
    "installed=$(Get-Date -Format o)",
    "runtime=$($manifest.runtime)",
    "coreVersion=$($manifest.coreVersion)",
    "jarName=$($manifest.jarName)",
    "jarSha256=$actualHash",
    "sourceCommit=$($manifest.sourceCommit)",
    "backup=$backup",
    'gateEvidenceReset=true',
    'priorGateCreditInherited=false'
) | Set-Content -LiteralPath $receipt -Encoding ASCII

Write-Host "INSTALL_PASS runtime=$($manifest.runtime) sha256=$actualHash backup=$backup"
