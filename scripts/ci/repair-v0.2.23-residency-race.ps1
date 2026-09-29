[CmdletBinding()]
param(
    [string]$Workspace = "$env:LOCALAPPDATA\OceanCanvas\AutonomousSupervisor\workspace",
    [string]$ProfilePath = "$env:APPDATA\ModrinthApp\profiles\Fabulously Optimized"
)

$ErrorActionPreference = 'Stop'
$oldCore = 'core-v0.2.23'
$newCore = 'core-v0.2.24'
$oldMod = '26.2-core-v0.2.23'
$newMod = '26.2-core-v0.2.24'
$source = Join-Path $Workspace 'src\main\java\net\oceancanvas\mod\server\SingleChunkWorldPorts.java'
$entry = Join-Path $Workspace 'src\main\java\net\oceancanvas\mod\OceanCanvas.java'
$gradleProps = Join-Path $Workspace 'gradle.properties'
$controller = Join-Path $Workspace 'scripts\windows\oceancanvas-permanent-controller.ps1'
$archTest = Join-Path $Workspace 'scripts\test-architecture.sh'
$mods = Join-Path $ProfilePath 'mods'
$worldLock = Join-Path $ProfilePath 'saves\New World\session.lock'
$receiptDir = Join-Path $env:LOCALAPPDATA 'OceanCanvas\RuntimeRepairs'
$receipt = Join-Path $receiptDir 'v0.2.24-residency-reacquire.txt'

function Assert-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Required file missing: $Path" }
}

function Test-WorldLockFree {
    if (-not (Test-Path -LiteralPath $worldLock)) { return $true }
    try {
        $fs = [System.IO.File]::Open($worldLock,[System.IO.FileMode]::Open,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None)
        $fs.Close()
        return $true
    } catch { return $false }
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

foreach ($p in @($source,$entry,$gradleProps,$controller,$archTest)) { Assert-File $p }
New-Item -ItemType Directory -Force -Path $receiptDir | Out-Null

# Idempotent fast path: once this exact candidate is installed and attested, do
# not rebuild it on every validation run.
if (Test-Path -LiteralPath $receipt) {
    $receiptText = Get-Content -LiteralPath $receipt -Raw -ErrorAction SilentlyContinue
    $m = [regex]::Match($receiptText, '(?m)^candidateSha256=([0-9a-f]{64})$')
    $installed = Get-ChildItem -LiteralPath $mods -Filter ("oceancanvas-core-{0}.jar" -f $newMod) -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($m.Success -and $null -ne $installed) {
        $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $installed.FullName).Hash.ToLowerInvariant()
        if ($installedHash -eq $m.Groups[1].Value) {
            Write-Host ("RUNTIME_REPAIR_ALREADY_APPLIED candidate={0} sha256={1}" -f $newMod,$installedHash)
            exit 0
        }
    }
}
$running = @(Get-TargetMinecraft)
if ($running.Count -gt 0) {
    throw "Refusing runtime repair while target Minecraft is running: PIDs=$((@($running | ForEach-Object ProcessId)) -join ',')"
}
if (-not (Test-WorldLockFree)) { throw "Refusing runtime repair while New World session.lock is held." }

$src = Get-Content -LiteralPath $source -Raw
if ($src -notmatch 'MAX_RESIDENCY_REACQUIRE_ATTEMPTS') {
    $src = $src.Replace(
        '    private static final int MAX_PHYSICAL_RECONCILIATION_PASSES = 3;',
        "    private static final int MAX_PHYSICAL_RECONCILIATION_PASSES = 3;`r`n    private static final int MAX_RESIDENCY_REACQUIRE_ATTEMPTS = 8;`r`n    private static final long MAX_RESIDENCY_RETRY_DELAY_TICKS = 20L;"
    )
    $src = $src.Replace(
        "    private LevelChunk chunk;`r`n",
        "    private LevelChunk chunk;`r`n    private int residencyReacquireAttempts;`r`n    private long residencyRetryNotBeforeTick = Long.MIN_VALUE;`r`n"
    )

    $old = @'
            if (loadFuture == null) {
                loadFuture = world.getChunkSource().getChunkFuture(key.x(), key.z(), ChunkStatus.FULL, false);
                return StageActionResult.waiting("FULL chunk future requested");
            }
            if (!loadFuture.isDone()) return StageActionResult.waiting("waiting for FULL chunk future");
            ChunkResult<ChunkAccess> result = loadFuture.join();
            ChunkAccess access = result.orElse(null);
            if (!(access instanceof LevelChunk live)) {
                return StageActionResult.failure("FULL chunk future completed without LevelChunk: " + result.getError());
            }
            chunk = live;
            receipts.append(ReceiptKind.CHUNK_RESIDENT, key, "resident-via-FULL-future");
            return StageActionResult.success("FULL chunk resident");
'@
    $new = @'
            if (loadFuture == null) {
                if (world.getGameTime() < residencyRetryNotBeforeTick) {
                    return StageActionResult.waiting("bounded FULL chunk residency reacquire backoff attempt="
                            + residencyReacquireAttempts + "/" + MAX_RESIDENCY_REACQUIRE_ATTEMPTS);
                }
                loadFuture = world.getChunkSource().getChunkFuture(key.x(), key.z(), ChunkStatus.FULL, false);
                return StageActionResult.waiting("FULL chunk future requested attempt="
                        + (residencyReacquireAttempts + 1) + "/" + (MAX_RESIDENCY_REACQUIRE_ATTEMPTS + 1));
            }
            if (!loadFuture.isDone()) return StageActionResult.waiting("waiting for FULL chunk future");
            ChunkResult<ChunkAccess> result = loadFuture.join();
            ChunkAccess access = result.orElse(null);
            if (!(access instanceof LevelChunk live)) {
                LevelChunk recovered = world.getChunkSource().getChunkNow(key.x(), key.z());
                if (recovered != null) {
                    chunk = recovered;
                    loadFuture = null;
                    residencyReacquireAttempts = 0;
                    residencyRetryNotBeforeTick = Long.MIN_VALUE;
                    receipts.append(ReceiptKind.CHUNK_RESIDENT, key, "resident-after-stale-FULL-future");
                    return StageActionResult.success("FULL chunk resident after stale future");
                }

                String error = String.valueOf(result.getError());
                if (residencyReacquireAttempts >= MAX_RESIDENCY_REACQUIRE_ATTEMPTS) {
                    return StageActionResult.failure("FULL chunk residency could not be reacquired after "
                            + MAX_RESIDENCY_REACQUIRE_ATTEMPTS + " stale/unloaded future completions: " + error);
                }

                residencyReacquireAttempts++;
                long delay = Math.min(MAX_RESIDENCY_RETRY_DELAY_TICKS,
                        1L << Math.min(4, residencyReacquireAttempts - 1));
                residencyRetryNotBeforeTick = world.getGameTime() + delay;
                loadFuture = null;
                world.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 0);
                return StageActionResult.waiting("FULL chunk future completed transiently unavailable; reacquire attempt="
                        + residencyReacquireAttempts + "/" + MAX_RESIDENCY_REACQUIRE_ATTEMPTS
                        + " retryInTicks=" + delay + " error=" + error);
            }
            chunk = live;
            loadFuture = null;
            residencyReacquireAttempts = 0;
            residencyRetryNotBeforeTick = Long.MIN_VALUE;
            receipts.append(ReceiptKind.CHUNK_RESIDENT, key, "resident-via-FULL-future");
            return StageActionResult.success("FULL chunk resident");
'@
    if (-not $src.Contains($old)) { throw 'Expected v0.2.23 ensureResident block not found; refusing fuzzy patch.' }
    $src = $src.Replace($old,$new)
    Set-Content -LiteralPath $source -Value $src -Encoding UTF8
}

$entryText = Get-Content -LiteralPath $entry -Raw
$entryText = $entryText.Replace('public static final String VERSION = "core-v0.2.23";','public static final String VERSION = "core-v0.2.24";')
Set-Content -LiteralPath $entry -Value $entryText -Encoding UTF8

$gp = Get-Content -LiteralPath $gradleProps -Raw
$gp = $gp.Replace("mod_version=$oldMod","mod_version=$newMod")
Set-Content -LiteralPath $gradleProps -Value $gp -Encoding ASCII

$arch = Get-Content -LiteralPath $archTest -Raw
if ($arch -notmatch 'missing bounded residency reacquire policy') {
    $arch += @'

# Runtime residency race regression.
grep -q 'MAX_RESIDENCY_REACQUIRE_ATTEMPTS' "$PORTS" || fail "missing bounded residency reacquire policy"
grep -q 'resident-after-stale-FULL-future' "$PORTS" || fail "missing stale FULL-future recovery path"
grep -q 'FULL chunk future completed transiently unavailable; reacquire attempt=' "$PORTS" || fail "missing residency retry telemetry"
if grep -q 'return StageActionResult.failure("FULL chunk future completed without LevelChunk:' "$PORTS"; then
  fail "transient unloaded FULL future still fails terminally on first completion"
fi
'@
    Set-Content -LiteralPath $archTest -Value $arch -Encoding UTF8
}

$bash = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path -LiteralPath $bash)) { throw "Git Bash missing: $bash" }
Push-Location $Workspace
try {
    & $bash scripts/validate-all.sh
    if ($LASTEXITCODE -ne 0) { throw "validate-all.sh failed exit=$LASTEXITCODE" }

    & .\gradlew.bat clean build --warning-mode=fail
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed exit=$LASTEXITCODE" }
} finally { Pop-Location }

$jar = Get-ChildItem -LiteralPath (Join-Path $Workspace 'build\libs') -Filter "oceancanvas-core-$newMod.jar" -File |
    Select-Object -First 1
if ($null -eq $jar) { throw "Expected candidate JAR not found for $newMod" }
$newHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash.ToLowerInvariant()

if (@(Get-TargetMinecraft).Count -gt 0) { throw 'Minecraft started during repair; refusing installation.' }
if (-not (Test-WorldLockFree)) { throw 'World lock became held during repair; refusing installation.' }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $mods "oceancanvas-runtime-repair-backup\$stamp"
New-Item -ItemType Directory -Force -Path $backup | Out-Null
$active = @(Get-ChildItem -LiteralPath $mods -Filter '*oceancanvas*.jar' -File -ErrorAction SilentlyContinue)
foreach ($oldJar in $active) {
    Copy-Item -LiteralPath $oldJar.FullName -Destination (Join-Path $backup $oldJar.Name) -Force
    $a=(Get-FileHash -Algorithm SHA256 -LiteralPath $oldJar.FullName).Hash
    $b=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $backup $oldJar.Name)).Hash
    if ($a -ne $b) { throw "Backup checksum mismatch for $($oldJar.Name)" }
}

$staged = Join-Path $mods ".$($jar.Name).installing"
Copy-Item -LiteralPath $jar.FullName -Destination $staged -Force
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $staged).Hash.ToLowerInvariant() -ne $newHash) {
    throw 'Staged candidate checksum mismatch'
}
foreach ($oldJar in $active) { Remove-Item -LiteralPath $oldJar.FullName -Force }
$target = Join-Path $mods $jar.Name
Move-Item -LiteralPath $staged -Destination $target -Force
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant() -ne $newHash) {
    throw 'Installed candidate checksum mismatch'
}

$ctl = Get-Content -LiteralPath $controller -Raw
$ctl = $ctl.Replace("$ExpectedVersion = '$oldCore'","$ExpectedVersion = '$newCore'")
$ctl = $ctl.Replace("$ExpectedModVersion = '$oldMod'","$ExpectedModVersion = '$newMod'")
$ctl = [regex]::Replace($ctl,"\$ProvenJarSha256 = '[0-9a-fA-F]{64}'","\$ProvenJarSha256 = '$newHash'")
$ctl = $ctl.Replace('oceancanvas-core-26.2-core-v0.2.23.jar','oceancanvas-core-26.2-core-v0.2.24.jar')
$ctl = $ctl.Replace('Frozen runtime: v0.2.23. No Gradle build or runtime replacement is permitted.',
                    'Frozen runtime candidate: v0.2.24. No further rebuild is permitted during gate progression.')
Set-Content -LiteralPath $controller -Value $ctl -Encoding UTF8

@(
    'Ocean Canvas runtime repair v0.2.24',
    "completed=$(Get-Date -Format o)",
    "sourceWorkspace=$Workspace",
    "previousRuntime=$oldMod",
    "candidateRuntime=$newMod",
    "candidateSha256=$newHash",
    "backup=$backup",
    'cause=FULL chunk future transiently completed as Unloaded chunk during G16 chunk 3,0',
    'fix=bounded asynchronous FULL residency reacquisition under the existing owned FORCED ticket',
    'validation=validate-all.sh PASS; Gradle clean build --warning-mode=fail PASS'
) | Set-Content -LiteralPath $receipt -Encoding UTF8

Write-Host "RUNTIME_REPAIR_PASS candidate=$newMod sha256=$newHash"
