#!/usr/bin/env python3
"""Fresh disposable server proof of an already-built production JAR."""
import os
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import threading
import time
import urllib.request

MODE = os.environ.get('OC_TEST_MODE', 'commands')
WIDTH = int(os.environ.get('OC_TEST_WIDTH', '500'))
if WIDTH not in (128, 500, 5000, 20000): raise SystemExit('Unsupported test width')
RADIUS = WIDTH // 2
CHUNKS = ((RADIUS - 1) // 16 - (-RADIUS // 16) + 1) ** 2
ROOT = pathlib.Path.cwd()
OUT = ROOT / 'production-runtime-evidence'
RUN = OUT / 'server'
OUT.mkdir(exist_ok=True)
if RUN.exists():
    raise SystemExit('Refusing to reuse a world: fresh server directory already exists')
RUN.mkdir()
CANDIDATE = ROOT / 'production-candidate'
JAR = CANDIDATE / 'oceancanvas-26.2-v253.125.54.jar'
expected = (CANDIDATE / 'JAR-SHA256.txt').read_text().split()[0]
actual = hashlib.sha256(JAR.read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit('Candidate JAR checksum mismatch')
manifest = (CANDIDATE / 'candidate.properties').read_text()
if 'sourceCommit=24072e6f21034cc8e77e201ab33e5866c14592ce' not in manifest:
    raise SystemExit('Unexpected production candidate source identity')
(OUT / 'identity.json').write_text(json.dumps({
    'jarSha256': actual, 'sourceCommit': '24072e6f21034cc8e77e201ab33e5866c14592ce',
    'freshWorld': True, 'targetBlocks': WIDTH, 'targetChunks': CHUNKS,
    'releaseVerdict': 'HOLD', 'scope': MODE, 'exactPreimageRestore': 'NOT_TESTED', 'clientVisuals': 'NOT_TESTED',
}, indent=2))

def download(url, path):
    with urllib.request.urlopen(url, timeout=120) as response:
        with path.open('wb') as target:
            shutil.copyfileobj(response, target)

download('https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar', RUN / 'installer.jar')
subprocess.run(['java', '-jar', 'installer.jar', 'server', '-mcversion', '26.2',
                '-loader', '0.19.3', '-downloadMinecraft'], cwd=RUN, check=True, timeout=300)
(RUN / 'mods').mkdir(exist_ok=True)
shutil.copy2(JAR, RUN / 'mods' / JAR.name)
download('https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.156.0+26.2/fabric-api-0.156.0+26.2.jar', RUN / 'mods' / 'fabric-api.jar')
(RUN / 'eula.txt').write_text('eula=true\n')
(RUN / 'server.properties').write_text(
    'level-name=world\nlevel-seed=4182026\nonline-mode=false\n'
    'server-ip=127.0.0.1\nview-distance=2\nsimulation-distance=2\n'
    'pause-when-empty-seconds=-1\nmax-tick-time=120000\n')
(RUN / 'config').mkdir()
(RUN / 'config' / 'oceancanvas.properties').write_text(
    'canvasSize=20000\ncenterX=0\ncenterZ=0\npregenEnabled=true\n'
    'oceanFloorY=-25\nbackupEnabled=true\n')
LOG = OUT / 'console.log'
lines = []
process = None
thread = None
def collect():
    with LOG.open('a') as target:
        for line in process.stdout:
            target.write(line)
            target.flush()
            lines.append(line)
            print(line, end='', flush=True)
def launch():
    global process, thread
    offset = len(lines)
    process = subprocess.Popen(['java', '-Xms1G', '-Xmx4G', '-jar',
                                'fabric-server-launch.jar', 'nogui'], cwd=RUN,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    thread = threading.Thread(target=collect, daemon=True)
    thread.start()
    wait_for(r'Done \(', 300, offset)
    return offset
def command(value):
    process.stdin.write(value + '\n')
    process.stdin.flush()
def wait_for(pattern, seconds, after=0):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if any(re.search(pattern, line) for line in lines[after:]):
            return
        if process.poll() is not None:
            raise RuntimeError(f'Server exited {process.returncode} before {pattern}')
        if shutil.disk_usage(RUN).free < 1024 ** 3:
            raise RuntimeError('Disposable runner disk capacity exhausted; runtime proof is incomplete')
        time.sleep(1)
    raise TimeoutError(f'Timed out waiting for {pattern}')
receipts = []
def expect(value, pattern, seconds=120):
    offset = len(lines)
    command(value)
    wait_for(pattern, seconds, offset)
    receipts.append({'command': value, 'verdict': 'PASS', 'observedPattern': pattern})
def stop():
    command('stop')
    process.wait(timeout=180)
    thread.join(timeout=10)
    if process.returncode:
        raise RuntimeError(f'Server shutdown exit={process.returncode}')
def baseline():
    expect('oceancanvas selftest', r'Ocean Canvas self-test PASS')
    expect('oceancanvas restore confirm NoSuchTestRegion', r"No Ocean Canvas region named 'NoSuchTestRegion'")
try:
    launch()
    baseline()
    if MODE == 'commands':
        expect('oceancanvas harness all', r'Full reliability report .*boundary=true, torture=true, crash=true')
        expect('oceancanvas harness crash-filesystem', r'verdict=PASS')
        expect('oceancanvas worldborder on', r'World border sync enabled')
        expect('oceancanvas protect here TestProtected 1', r"Zone 'TestProtected' created and protected")
        expect('oceancanvas harness torture-runtime arm', r'Runtime Save/Quit torture armed')
        stop()
        launch()
        expect('oceancanvas worldborder status', r'World border sync is ON')
        expect('oceancanvas protect list', r'TestProtected')
        expect('oceancanvas harness torture-runtime verify', r'PASS:')
        baseline()
    elif MODE in ('cancel', 'restart', 'crash', 'scale', 'restore'):
        expect(f'oceancanvas pregen start {RADIUS} 0 0 confirm',
               rf'PREGEN-ACCEPTANCE-START .*chunks={CHUNKS} widthBlocks={WIDTH} centerX=0 centerZ=0')
        if MODE == 'cancel':
            expect('oceancanvas pregen cancel', r'Cancelled pregen/rewipe/restore/expand job')
            stop()
            offset = launch()
            expect('oceancanvas pregen cancel', r'No pregen/rewipe/restore/expand job is running')
            if any('Resumed pregen job after restart' in line for line in lines[offset:]):
                raise RuntimeError('Cancelled Pregen resumed after restart')
            receipts.append({'case': 'cancelled-job-does-not-resume', 'verdict': 'PASS'})
        else:
            if MODE in ('restart', 'crash'):
                if any('PREGEN-ACCEPTANCE-DONE' in line for line in lines):
                    raise RuntimeError('Job finished too early to prove an interrupted restart')
                if MODE == 'restart':
                    expect('oceancanvas harness torture-runtime arm', r'Runtime Save/Quit torture armed')
                    stop()
                else:
                    expect('save-all flush', r'Saved the game')
                    if any('PREGEN-ACCEPTANCE-DONE' in line for line in lines):
                        raise RuntimeError('Job completed before crash injection')
                    process.kill()
                    process.wait(timeout=30)
                    thread.join(timeout=10)
                offset = launch()
                wait_for(r'Resumed pregen job after restart', 120, offset)
                receipts.append({'case': MODE + '-resumed-persisted-job', 'verdict': 'PASS'})
            wait_for(rf'PREGEN-ACCEPTANCE-DONE .*chunks={CHUNKS}', int(os.environ.get('OC_STAGE_SECONDS', '1800')))
            if MODE == 'restart':
                expect('oceancanvas harness torture-runtime verify', r'PASS:')
            if MODE == 'restore':
                expect('execute positioned 0 64 0 run oceancanvas protect here RestoreTest 1', r"Zone 'RestoreTest' created and protected")
                expect('setblock 48 80 0 minecraft:diamond_block', r'Changed the block')
                expect('oceancanvas restore confirm RestoreTest', r'Started Restore to Vanilla: 9 chunk')
                wait_for(r"Restore to Vanilla complete: 9 chunk\(s\) restored in 'RestoreTest'", 900)
                expect('execute if block 48 80 0 minecraft:diamond_block run say RESTORE_OUTSIDE_SENTINEL_PASS', r'\[Server\] RESTORE_OUTSIDE_SENTINEL_PASS')
                receipts.append({'case': 'seed-regeneration-restore-nine-chunks', 'verdict': 'PASS', 'exactPreimageProof': False})
    else:
        raise RuntimeError('Unknown test mode: ' + MODE)
    expect('oceancanvas diagnostics', r'Diagnostic bundle .* written to ')
    stop()
    if MODE in ('scale', 'restart', 'crash'):
        subprocess.run(['python3', str(ROOT / 'scripts/ci/production-acceptance.py'), str(LOG),
                        '--expected-build', 'v253.125.54', '--expected-chunks', str(CHUNKS),
                        '--expected-size-blocks', str(WIDTH), '--expected-center-x', '0',
                        '--expected-center-z', '0', '--require-start', '--require-completion',
                        '--scope-latest-run', '--require-structured-evidence',
                        '--json-output', str(OUT / 'acceptance.json')], check=True)
    (OUT / 'receipts.json').write_text(json.dumps({'mode': MODE, 'verdict': 'PASS', 'jarSha256': actual, 'checks': receipts}, indent=2))
    print('PRODUCTION_RUNTIME_PASS ' + MODE, flush=True)

finally:
    if process is not None and process.poll() is None:
        try:
            command('stop')
            process.wait(timeout=120)
        except Exception:
            process.kill()
            process.wait(timeout=30)
    if thread is not None: thread.join(timeout=10)
    (OUT / 'observed-checks.json').write_text(json.dumps(receipts, indent=2))
    data = RUN / 'world' / 'data'
    if data.exists(): shutil.copytree(data, OUT / 'saved-world-data', dirs_exist_ok=True)
    # Logs and native diagnostics are retained; the large disposable world is
    # excluded from uploads. No user world or workstation is accessed.
    for p in RUN.rglob('*.zip'):
        shutil.copy2(p, OUT / p.name)
    shutil.rmtree(RUN)
