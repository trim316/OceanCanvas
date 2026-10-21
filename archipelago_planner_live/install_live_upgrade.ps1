$ErrorActionPreference = 'Stop'

Write-Host 'Archipelago Planner live-region upgrade' -ForegroundColor Cyan

# Find the currently installed planner root automatically. Prefer the current directory,
# then common user folders, and choose the newest islands_auto.py that looks like v35.35+.
$candidates = New-Object System.Collections.Generic.List[string]

function Add-Candidate([string]$p) {
    if ([string]::IsNullOrWhiteSpace($p)) { return }
    try {
        $full = [System.IO.Path]::GetFullPath($p)
        if ((Test-Path (Join-Path $full 'islands_auto.py')) -and -not $candidates.Contains($full)) {
            $candidates.Add($full)
        }
    } catch {}
}

$here = (Get-Location).Path
$p = $here
for ($i=0; $i -lt 6 -and $p; $i++) {
    Add-Candidate $p
    $parent = Split-Path $p -Parent
    if ($parent -eq $p) { break }
    $p = $parent
}

$roots = @(
    [Environment]::GetFolderPath('Desktop'),
    [Environment]::GetFolderPath('MyDocuments'),
    (Join-Path $env:USERPROFILE 'Downloads')
) | Where-Object { $_ -and (Test-Path $_) }

foreach ($root in $roots) {
    Get-ChildItem -Path $root -Filter 'islands_auto.py' -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 20 |
        ForEach-Object { Add-Candidate $_.DirectoryName }
}

if ($candidates.Count -eq 0) {
    throw 'Could not find the Archipelago Planner folder containing islands_auto.py. Open PowerShell in that folder and run this installer again.'
}

# Score candidates by newest file and presence of the normal launcher/registry.
$chosen = $candidates |
    ForEach-Object {
        $root = $_
        $score = (Get-Item (Join-Path $root 'islands_auto.py')).LastWriteTimeUtc.Ticks
        if (Test-Path (Join-Path $root 'Run_Island_Auto.cmd')) { $score += 100000000000000000 }
        if (Test-Path (Join-Path $root 'archipelago_planner.json')) { $score += 10000000000000000 }
        [pscustomobject]@{ Root=$root; Score=$score }
    } |
    Sort-Object Score -Descending |
    Select-Object -First 1

$plannerRoot = $chosen.Root
Write-Host "Planner root: $plannerRoot" -ForegroundColor Green
Set-Location $plannerRoot

$scriptUrl = 'https://raw.githubusercontent.com/trim316/OceanCanvas/archipelago-planner-live/archipelago_planner_live/upgrade_major_regions_live.py'
$tmp = Join-Path $env:TEMP 'archipelago_upgrade_major_regions_live.py'
Invoke-WebRequest -UseBasicParsing -Uri $scriptUrl -OutFile $tmp

$python = $null
if (Get-Command py -ErrorAction SilentlyContinue) { $python = 'py' }
elseif (Get-Command python -ErrorAction SilentlyContinue) { $python = 'python' }
else { throw 'Python was not found on PATH.' }

& $python $tmp
if ($LASTEXITCODE -ne 0) { throw "Upgrade script failed with exit code $LASTEXITCODE" }

# Verify the patch really landed before claiming success.
$source = Get-Content -Raw -Path (Join-Path $plannerRoot 'islands_auto.py')
if ($source -notmatch 'VERSION_LABEL\s*=\s*"35\.36"') { throw 'Verification failed: planner did not update to v35.36.' }
if ($source -notmatch 'def _major_region_name\(') { throw 'Verification failed: major-region renderer was not installed.' }
if ($source -notmatch 'major_region_override') { throw 'Verification failed: live major-region field was not installed.' }

Write-Host ''
Write-Host 'SUCCESS: planner upgraded and verified as v35.36.' -ForegroundColor Green
Write-Host 'Close any old planner/server window, then run Run_Island_Auto.cmd once.' -ForegroundColor Yellow
