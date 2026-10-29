"""Specific one-chunk interruption/restart proof. Never a scale campaign or throughput test."""
from pathlib import Path
import hashlib, json, os, re, shutil, subprocess, threading, time, zipfile
import nbtlib
from runtime_support import prepare_server, cached_download

subprocess.run(['git','diff','--exit-code',os.environ['EXPECTED_SOURCE'],'--','production-src'],check=True)
assert 'public static final String VERSION = "v253.125.111";' in Path('production-src/src/main/java/net/oceancanvas/mod/OceanCanvas.java').read_text()
ROOT = Path('cooperative-carve-restart-fixture').resolve()
assert not ROOT.exists()
ROOT.mkdir()
SERVER = ROOT / 'server'
sha = lambda p: hashlib.file_digest(Path(p).open('rb'), 'sha256').hexdigest()
prepare_server(SERVER, Path('.cooperative-fixture-runtime-cache'))
(SERVER / 'mods').mkdir(exist_ok=True)
product = Path('production-src/build/libs/oceancanvas-26.2-v253.125.111.jar')
fixture = Path('production-src/build/fixture-libs/cooperative-deadline-fixture.jar')
for p in (product, fixture): shutil.copy2(p, SERVER / 'mods' / p.name)
cached_download('https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.156.0+26.2/fabric-api-0.156.0+26.2.jar', SERVER / 'mods/fabric-api.jar', Path('.cooperative-fixture-runtime-cache/downloads'))
(SERVER / 'eula.txt').write_text('eula=true\n')
(SERVER / 'server.properties').write_text('level-name=world\nlevel-seed=4182026\nonline-mode=false\nserver-ip=127.0.0.1\nview-distance=2\nsimulation-distance=2\npause-when-empty-seconds=-1\nmax-tick-time=120000\n')
(SERVER / 'config').mkdir(exist_ok=True)
(SERVER / 'config/oceancanvas.properties').write_text('canvasSize=20000\ncenterX=0\ncenterZ=0\npregenEnabled=true\noceanFloorY=-25\noceanFloorVariation=5\nbackupEnabled=true\n')
identity = {'source': os.environ['EXPECTED_SOURCE'], 'harness': subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(), 'jarSha256': sha(product), 'fixtureSha256': sha(fixture), 'seed': 4182026, 'chunk': [4,4], 'declaredSelection': [64,79,64,79], 'heapGiB': 3, 'scope': 'Forced conservative elapsed-clock ONLY; ONE newly declared16block fixture, not4096 rerun/not20k campaign. Same saved job on addonfree cold restart. Independent whole selected block oracle remains separate.'}
(ROOT / 'identity.json').write_text(json.dumps(identity, indent=2))

def inventory():
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in SERVER.rglob('*') if p.is_file()}

def mask():
    p = SERVER / 'world/dimensions/minecraft/overworld/data/oceancanvas/protected_regions.dat'
    if not p.exists(): return 0
    data = nbtlib.load(p)['data']
    for row in data.get('sealRuns', []):
        if int(row['z']) == 4 and int(row['minX']) <= 4 <= int(row['maxX']): return int(row['mask'])
    return 0

def stage(name, interrupt):
    lines = []
    failure = None
    reason = None
    with (ROOT / (name + '.log')).open('x', encoding='utf8') as log:
        p = subprocess.Popen(['java','-Xms512M','-Xmx3G','-XX:+UseZGC','-jar','fabric-server-launch.jar','nogui'], cwd=SERVER, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf8', errors='replace')
        def read():
            for line in p.stdout: lines.append(line); log.write(line); log.flush()
        thread = threading.Thread(target=read, daemon=True); thread.start()
        def command(text): p.stdin.write(text + '\n'); p.stdin.flush()
        started = time.monotonic(); startup = started + 240; end = started + 840; commands_sent = False
        try:
            while p.poll() is None:
                failure = next((l.strip() for l in lines if ('P2 performance budget assertion:' in l and re.search(r'=WARN\(|=FAIL\(', l)) or ('/ERROR]' in l and 'Perflib' not in l) or 'TRUNCATED' in l), None)
                if failure: reason = 'FIRST_FAILURE'; break
                if any('Done (' in l for l in lines) and not commands_sent:
                    if interrupt:
                        command('oceancanvas forever status')
                        command('oceancanvas forever role EXPERIMENT')
                        command('oceancanvas pregen 8 72 72')
                    commands_sent = True
                if interrupt and any('COOPERATIVE-CARVE-YIELD chunk=4,4 ' in l for l in lines): reason = 'ACTUAL_PARTIAL_YIELD'; break
                if interrupt and any('PREGEN-ACCEPTANCE-DONE' in l for l in lines): reason = 'NO_PARTIAL_YIELD_EXERCISED'; break
                if not interrupt and any('PREGEN-ACCEPTANCE-DONE' in l for l in lines): reason = 'STRICT_SAVED_JOB_COMPLETE'; break
                now = time.monotonic()
                if not commands_sent and now >= startup: reason = 'STARTUP_CEILING'; break
                if now >= end: reason = 'FIXTURE_CEILING'; break
                time.sleep(.05)
        finally:
            if p.poll() is None: command('stop'); p.wait(timeout=180)
            thread.join(10)
    failure = failure or next((l.strip() for l in lines if ('P2 performance budget assertion:' in l and re.search(r'=WARN\(|=FAIL\(', l)) or ('/ERROR]' in l and 'Perflib' not in l) or 'TRUNCATED' in l), None)
    m = inventory()
    (ROOT / (name + '-stopped-manifest.json')).write_text(json.dumps(m, indent=2))
    result = {'reason': reason, 'firstFailure': failure, 'exit': p.returncode, 'stoppedMask': mask(), 'verifiedFiles': len(m), 'fullAcceptancePassed': False}
    (ROOT / (name + '-evidence.json')).write_text(json.dumps(result, indent=2))
    assert failure is None and p.returncode == 0, result
    return result

partial = stage('interrupted', True)
assert partial['reason'] == 'ACTUAL_PARTIAL_YIELD' and partial['stoppedMask'] != 15, partial
assert 'PREGEN-ACCEPTANCE-DONE' not in (ROOT / 'interrupted.log').read_text()
before = inventory()
with zipfile.ZipFile(ROOT / 'immutable-partial-checkpoint.zip', 'x', zipfile.ZIP_DEFLATED, compresslevel=1) as z:
    for n, h in before.items(): assert sha(ROOT/n) == h; z.write(ROOT/n, n)
with (ROOT / 'immutable-partial-checkpoint.zip').open('r+b') as f: os.fsync(f.fileno())
with zipfile.ZipFile(ROOT / 'immutable-partial-checkpoint.zip') as z:
    for n,h in before.items():
        with z.open(n) as f: assert hashlib.file_digest(f,'sha256').hexdigest() == h
(ROOT / 'partial-lineage.json').write_text(json.dumps({'archiveSha256': sha(ROOT/'immutable-partial-checkpoint.zip'), 'files': before}, indent=2))
archived = ROOT / 'removed-fixture-addons'; archived.mkdir()
shutil.move(str(SERVER/'mods'/fixture.name), str(archived/fixture.name))
assert sha(archived/fixture.name) == identity['fixtureSha256']
assert sorted(p.name for p in (SERVER/'mods').glob('*.jar')) == sorted([product.name, 'fabric-api.jar'])
cold = stage('addonfree-cold', False)
assert cold['reason'] == 'STRICT_SAVED_JOB_COMPLETE' and cold['stoppedMask'] == 15, cold
(ROOT / 'targeted-native-restart-result.json').write_text(json.dumps({'partialYieldObserved': True, 'partialUnsealed': True, 'immutablePartialVerified': True, 'addonRemoved': True, 'sameSavedJobRestartNativeStrictDone': True, 'independentBlockOraclePassed': False, 'scaleDispatchAdmitted': False, 'fullAcceptancePassed': False}, indent=2))
