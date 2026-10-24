@echo off
set "OC_LOCAL_BOOTSTRAP=%~f0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$text=[IO.File]::ReadAllText($env:OC_LOCAL_BOOTSTRAP); $tag='# POWERSHELL_'+'PAYLOAD'; & ([scriptblock]::Create($text.Substring($text.LastIndexOf($tag)+$tag.Length)))"
pause
exit /b
# POWERSHELL_PAYLOAD
$ErrorActionPreference = 'Stop'
$profile = Join-Path $env:APPDATA 'ModrinthApp\profiles\Fabulously Optimized'
$world = Join-Path $profile 'saves\New World'
$mods = Join-Path $profile 'mods'
$runRoot = Join-Path $env:LOCALAPPDATA ('OceanCanvas\LocalTest\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$report = Join-Path $runRoot 'POST_THIS_LOG.txt'
$transcript = Join-Path $runRoot 'launcher.log'
Start-Transcript -Path $transcript | Out-Null
try {
    if (!(Test-Path $mods) -or !(Test-Path (Join-Path $world 'level.dat'))) {
        throw 'Expected Fabulously Optimized profile and New World save are missing. No installation was changed.'
    }
    if (Get-Process javaw -ErrorAction SilentlyContinue) { throw 'Close Minecraft before running this launcher.' }
    $lock = Join-Path $world 'session.lock'
    if (Test-Path $lock) {
        $lease = [IO.File]::Open($lock, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $lease.Dispose()
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $scriptFolder = Split-Path $env:OC_LOCAL_BOOTSTRAP
    $downloads = Join-Path $env:USERPROFILE 'Downloads'
    $archives = @(Get-ChildItem -LiteralPath $scriptFolder,$downloads -Filter '*.zip' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(?i)oceancanvas.*candidate|production-candidate' } |
        Sort-Object LastWriteTime -Descending)
    if ($archives.Count -eq 0) { throw 'Download the oceancanvas-production-candidate ZIP from the GitHub run into Downloads, then run this launcher again. No manual extraction is needed.' }
    $zip = [IO.Compression.ZipFile]::OpenRead($archives[0].FullName)
    try {
        function Read-ZipText([string]$name) {
            $entry = $zip.GetEntry($name)
            if ($null -eq $entry) { throw "Candidate is missing $name" }
            $reader = New-Object IO.StreamReader($entry.Open())
            try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
        $props = @{}
        foreach ($line in ((Read-ZipText 'candidate.properties') -split '\r?\n')) {
            if (!$line -or $line.StartsWith('#')) { continue }
            $pair = $line -split '=',2
            if ($pair.Count -ne 2 -or $props.ContainsKey($pair[0])) { throw 'Invalid or duplicate candidate identity.' }
            $props[$pair[0]] = $pair[1]
        }
        $source = $props['sourceCommit']
        $build = $props['runtimeBuild']
        if ($source -notmatch '^[0-9a-f]{40}$' -or $build -notmatch '^v[0-9]+(\.[0-9]+)+$') { throw 'Candidate identity is incomplete.' }
        $jarName = "oceancanvas-26.2-$build.jar"
        $entry = $zip.GetEntry($jarName)
        if ($null -eq $entry) { throw 'Exact candidate JAR is absent.' }
        $jar = Join-Path $runRoot $jarName
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$jar,$false)
        $expected = ((Read-ZipText 'JAR-SHA256.txt') -split '\s+')[0]
        $actual = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw 'Candidate checksum mismatch.' }
    } finally { $zip.Dispose() }
    $jarZip = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $reader = New-Object IO.StreamReader($jarZip.GetEntry('fabric.mod.json').Open())
        try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if ($metadata.id -ne 'oceancanvas' -or $metadata.version -ne "26.2-$build") { throw 'JAR metadata does not match candidate identity.' }
    } finally { $jarZip.Dispose() }
    $backup = Join-Path $runRoot 'world-backup'
    & robocopy.exe $world $backup /E /XF session.lock /R:1 /W:1 /NFL /NDL /NJH /NJS | Out-Null
    if ($LASTEXITCODE -ge 8) { throw 'World backup failed; mod installation was not changed.' }
    $oldMods = Join-Path $runRoot 'previous-mods'
    New-Item -ItemType Directory -Path $oldMods | Out-Null
    $previous = @(Get-ChildItem -LiteralPath $mods -Filter 'oceancanvas*.jar')
    try {
        foreach ($old in $previous) { Move-Item -LiteralPath $old.FullName -Destination $oldMods }
        Copy-Item -LiteralPath $jar -Destination (Join-Path $mods $jarName)
    } catch {
        Remove-Item -LiteralPath (Join-Path $mods $jarName) -ErrorAction SilentlyContinue
        foreach ($old in (Get-ChildItem -LiteralPath $oldMods -Filter '*.jar')) { Copy-Item -LiteralPath $old.FullName -Destination $mods -Force }
        throw
    }
    @("status=INSTALLED_NOT_TESTED", "build=$build", "sourceCommit=$source", "jarSha256=$actual", "save=New World", "backup=$backup") | Set-Content -LiteralPath $report
    Write-Host "Installed $build. New World was backed up to $backup"
    Write-Host 'Open Fabulously Optimized in Modrinth, then open New World. Forever World is not used.'
    Write-Host 'In game run /oceancanvas pregen status first. If no job is running, use /oceancanvas pregen start 250 0 0 confirm.'
    Write-Host 'Run /oceancanvas diagnostics when the test finishes or stalls, then Save & Quit.'
    $app = Get-StartApps | Where-Object { $_.Name -match 'Modrinth' } | Select-Object -First 1
    if ($app) { Start-Process explorer.exe -ArgumentList ('shell:AppsFolder\' + $app.AppID) }
    $log = Join-Path $profile 'logs\latest.log'
    $startedAt = Get-Date
    $deadline = $startedAt.AddMinutes(40)
    $seenBuild = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        if (!(Test-Path $log) -or (Get-Item -LiteralPath $log).LastWriteTime -lt $startedAt) { continue }
        $text = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
        if (!$text -or !$text.Contains($build)) { continue }
        $seenBuild = $true
        Copy-Item -LiteralPath $log -Destination (Join-Path $runRoot 'minecraft-latest.log') -Force -ErrorAction SilentlyContinue
        $status = 'RUNTIME_OBSERVED_NOT_CERTIFIED'
        if ($text -match "PREGEN-ACCEPTANCE-DONE .*build=$([regex]::Escape($build))") { $status = 'COMPLETION_REPORTED_NOT_CERTIFIED' }
        @("status=$status", "build=$build", "sourceCommit=$source", "jarSha256=$actual", "backup=$backup", '', $text) | Set-Content -LiteralPath $report
        if (!(Get-Process javaw -ErrorAction SilentlyContinue)) { break }
    }
    if (!$seenBuild) { Add-Content -LiteralPath $report 'No matching Minecraft runtime was observed. This is not a test pass.' }
    Write-Host "Local evidence: $report"
} catch {
    @('status=LOCAL_SETUP_OR_TEST_FAILED', $_.Exception.Message, "details=$transcript") | Set-Content -LiteralPath $report
    Write-Host "Failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Evidence: $report"
} finally {
    Stop-Transcript | Out-Null
}
