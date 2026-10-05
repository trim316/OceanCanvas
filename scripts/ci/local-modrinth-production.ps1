[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$CandidateDir)
$ErrorActionPreference='Stop'
$profile=Join-Path $env:APPDATA 'ModrinthApp\profiles\Fabulously Optimized'
$world=Join-Path $profile 'saves\New World'
$controller=Join-Path $env:LOCALAPPDATA 'OceanCanvas\AutonomousSupervisor\workspace\scripts\windows\oceancanvas-permanent-controller.ps1'
$out=Join-Path $env:GITHUB_WORKSPACE 'local-production-evidence'
New-Item -ItemType Directory -Force -Path $out | Out-Null
Start-Transcript -Path (Join-Path $out 'local-test.log') -Force
try {
    if(-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))){throw 'Designated New World save missing'}
    $lock=Join-Path $world 'session.lock'
    if(Test-Path -LiteralPath $lock){$f=[IO.File]::Open($lock,'Open','ReadWrite','None');$f.Close()}
    $running=@(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -and $_.CommandLine.ToLowerInvariant().Contains($profile.ToLowerInvariant()) })
    if($running.Count){throw 'Target Minecraft profile is running; close it before candidate installation'}
    $jar=Join-Path $CandidateDir 'oceancanvas-26.2-v253.125.54.jar'
    $expected=((Get-Content -LiteralPath (Join-Path $CandidateDir 'JAR-SHA256.txt') -Raw).Trim() -split '\s+')[0]
    $hash=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    if($hash -ne $expected){throw 'Production JAR checksum mismatch'}
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup=Join-Path $env:LOCALAPPDATA ('OceanCanvas\LocalProductionTest\'+$stamp)
    New-Item -ItemType Directory -Force -Path $backup | Out-Null
    Copy-Item -LiteralPath $world -Destination (Join-Path $backup 'New World') -Recurse
    foreach($file in Get-ChildItem -LiteralPath $world -File -Recurse){
        $relative=$file.FullName.Substring($world.Length).TrimStart('\')
        $copy=Join-Path (Join-Path $backup 'New World') $relative
        if((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash){throw ('World backup mismatch: '+$relative)}
    }
    Write-Host ('WORLD_BACKUP_VERIFIED='+$backup)
    $mods=Join-Path $profile 'mods'
    $oldMods=Join-Path $backup 'prior-mods'
    New-Item -ItemType Directory -Force -Path $oldMods | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach($old in Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File){
        $z=[IO.Compression.ZipFile]::OpenRead($old.FullName)
        try {
            $e=$z.GetEntry('fabric.mod.json')
            if($null -eq $e){continue}
            $r=New-Object IO.StreamReader($e.Open())
            try{$meta=$r.ReadToEnd() | ConvertFrom-Json}finally{$r.Dispose()}
        }finally{$z.Dispose()}
        if($meta.id -eq 'oceancanvas'){Move-Item -LiteralPath $old.FullName -Destination $oldMods}
    }
    Copy-Item -LiteralPath $jar -Destination (Join-Path $mods (Split-Path $jar -Leaf))
    $installed=Join-Path $mods (Split-Path $jar -Leaf)
    if((Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash){throw 'Installed production JAR checksum mismatch'}
    $config=Join-Path $profile 'config\oceancanvas.properties'
    if(Test-Path -LiteralPath $config){Copy-Item -LiteralPath $config -Destination $backup}
    @('canvasSize=20000','centerX=0','centerZ=0','oceanFloorY=-25','pregenEnabled=true','backupEnabled=true') | Set-Content -LiteralPath $config -Encoding ASCII
    $pack=Join-Path $world 'datapacks\oceancanvas-local-production-test'
    if(Test-Path -LiteralPath $pack){throw 'A prior local test datapack exists; refusing to overwrite its evidence'}
    $functions=Join-Path $pack 'data\oc_local_proof\function'
    $tags=Join-Path $pack 'data\minecraft\tags\function'
    New-Item -ItemType Directory -Force -Path $functions,$tags | Out-Null
    '{"pack":{"description":"Temporary Ocean Canvas local production test","min_format":0,"max_format":9999}}' | Set-Content -LiteralPath (Join-Path $pack 'pack.mcmeta') -Encoding ASCII
    '{"values":["oc_local_proof:load"]}' | Set-Content -LiteralPath (Join-Path $tags 'load.json') -Encoding ASCII
    'execute unless data storage oc_local_proof:once started run function oc_local_proof:start' | Set-Content -LiteralPath (Join-Path $functions 'load.mcfunction') -Encoding ASCII
    @('data modify storage oc_local_proof:once started set value 1b','say OC_LOCAL_PRODUCTION_TEST_STARTED','oceancanvas selftest','oceancanvas pregen cancel','oceancanvas pregen start 250 0 0 confirm','schedule function oc_local_proof:status 60s replace') | Set-Content -LiteralPath (Join-Path $functions 'start.mcfunction') -Encoding ASCII
    @('oceancanvas pregen status','schedule function oc_local_proof:status 60s replace') | Set-Content -LiteralPath (Join-Path $functions 'status.mcfunction') -Encoding ASCII
    $text=Get-Content -LiteralPath $controller -Raw
    $tokens=$null;$errors=$null
    $ast=[System.Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'Installed launcher does not parse'}
    $fn=@($ast.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-PermanentAuto'},$true))
    if($fn.Count -ne 1){throw 'Expected one existing launch entry point'}
    $body='function Invoke-PermanentAuto { $worldPath=Join-Path $ProfilePath ''saves\New World''; Start-DisposableWorld $worldPath }'
    $extent=$fn[0].Extent
    $patched=$text.Remove($extent.StartOffset,$extent.EndOffset-$extent.StartOffset).Insert($extent.StartOffset,$body)
    $launch=Join-Path $out 'launch-test.ps1'
    Set-Content -LiteralPath $launch -Value $patched -Encoding UTF8
    # Modrinth/Minecraft must retain its normal save ownership if this observer
    # times out. GitHub's orphan cleanup must not terminate the player's client.
    $tracking=$env:RUNNER_TRACKING_ID
    Remove-Item Env:RUNNER_TRACKING_ID -ErrorAction SilentlyContinue
    try {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launch -Action PermanentAuto -ProfilePath $profile -WorldName 'New World'
    } finally {
        if($tracking){$env:RUNNER_TRACKING_ID=$tracking}
    }
    if($LASTEXITCODE){throw ('Existing Modrinth launcher failed: '+$LASTEXITCODE)}
    $log=Join-Path $profile 'logs\latest.log'
    $deadline=(Get-Date).AddMinutes(35)
    $started=$false
    while((Get-Date) -lt $deadline){
        $content=Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
        Copy-Item -LiteralPath $log -Destination (Join-Path $out 'latest.log') -Force
        if($content -match 'PREGEN-ACCEPTANCE-START build=v253.125.54 .*chunks=1024 widthBlocks=500 centerX=0 centerZ=0'){$started=$true}
        if($content -match 'PREGEN-ACCEPTANCE-DONE build=v253.125.54 .*chunks=1024'){
            if(-not $started){throw 'Completion without this test start is not accepted'}
            @('runtime=26.2-v253.125.54',('jarSha256='+$hash),'testWorld=New World','localPregenCompletion=PASS','targetBlocks=500','targetChunks=1024','releaseVerdict=HOLD',('backup='+$backup)) | Set-Content -LiteralPath (Join-Path $out 'local-result.properties') -Encoding ASCII
            Write-Host 'LOCAL_MODRINTH_500_COMPLETION_OBSERVED; final lighting, shutdown and Restore remain separate gates'
            return
        }
        if(-not $started -and $content -match 'Failed to load function oc_local_proof|Failed to read pack metadata|Unknown function oc_local_proof'){throw 'Local test datapack did not load; no Pregen proof exists'}
        Write-Host ('Local production test: started='+$started+'; last status:')
        Get-Content -LiteralPath $log -Tail 12
        if(-not $started -and (Get-Date) -gt $deadline.AddMinutes(-32)){throw 'Local test command did not start within three minutes'}
        Start-Sleep -Seconds 20
    }
    throw 'Local 500-block test incomplete after 35 minutes; no PASS is inferred'
} finally {
    Stop-Transcript
}
