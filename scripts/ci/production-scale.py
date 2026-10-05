#!/usr/bin/env python3
"""Fresh-world scale proof for an exact Ocean Canvas production candidate."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import threading
import time
import urllib.request

WIDTH = int(os.environ.get('OC_TEST_WIDTH', '5000'))
if WIDTH not in (500, 5000, 20000):
    raise SystemExit('Unsupported scale width')
RADIUS = WIDTH // 2
CHUNKS = ((RADIUS - 1) // 16 - (-RADIUS // 16) + 1) ** 2
STAGE_SECONDS = int(os.environ.get('OC_STAGE_SECONDS', '9600'))
BUILD = os.environ.get('EXPECTED_BUILD')
SOURCE = os.environ.get('EXPECTED_SOURCE_COMMIT')
if not BUILD or not SOURCE:
    raise SystemExit('EXPECTED_BUILD and EXPECTED_SOURCE_COMMIT are required')

ROOT = pathlib.Path.cwd()
OUT = ROOT / 'production-runtime-evidence'
RUN = OUT / 'server'
CANDIDATE = ROOT / 'production-candidate'
OUT.mkdir(exist_ok=True)
if RUN.exists():
    raise SystemExit('Refusing to reuse a world: fresh server directory already exists')
RUN.mkdir()

JAR = CANDIDATE / f'oceancanvas-26.2-{BUILD}.jar'
if not JAR.is_file():
    raise SystemExit(f'Exact candidate JAR is missing: {JAR}')
expected = (CANDIDATE / 'JAR-SHA256.txt').read_text(encoding='utf-8').split()[0]
actual = hashlib.sha256(JAR.read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit('Candidate JAR checksum mismatch')
manifest_lines = (CANDIDATE / 'candidate.properties').read_text(encoding='utf-8').splitlines()
if f'sourceCommit={SOURCE}' not in manifest_lines:
    raise SystemExit('Unexpected production candidate source identity')

(OUT / 'identity.json').write_text(json.dumps({
    'jarSha256': actual,
    'sourceCommit': SOURCE,
    'build': BUILD,
    'freshWorld': True,
    'targetBlocks': WIDTH,
    'targetChunks': CHUNKS,
    'releaseVerdict': 'HOLD',
    'scope': f'production-{WIDTH}-scale',
}, indent=2) + '\n', encoding='utf-8')


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
(RUN / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
(RUN / 'server.properties').write_text(
    'level-name=world\nlevel-seed=4182026\nonline-mode=false\n'
    'server-ip=127.0.0.1\nview-distance=2\nsimulation-distance=2\n'
    'pause-when-empty-seconds=-1\nmax-tick-time=120000\n', encoding='utf-8')
(RUN / 'config').mkdir()
(RUN / 'config' / 'oceancanvas.properties').write_text(
    'canvasSize=20000\ncenterX=0\ncenterZ=0\npregenEnabled=true\n'
    'oceanFloorY=-25\nbackupEnabled=true\n', encoding='utf-8')

LOG = OUT / 'console.log'
lines = []
process = subprocess.Popen(['java', '-Xms1G', '-Xmx4G', '-jar',
                            'fabric-server-launch.jar', 'nogui'], cwd=RUN,
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, bufsize=1)


def collect():
    with LOG.open('w', encoding='utf-8') as target:
        for line in process.stdout:
            target.write(line)
            target.flush()
            lines.append(line)
            print(line, end='', flush=True)


thread = threading.Thread(target=collect, daemon=True)
thread.start()


def command(value):
    process.stdin.write(value + '\n')
    process.stdin.flush()


def wait_for(pattern, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if any(re.search(pattern, line) for line in lines):
            return
        if process.poll() is not None:
            raise RuntimeError(f'Server exited {process.returncode} before {pattern}')
        if shutil.disk_usage(RUN).free < 1024 ** 3:
            raise RuntimeError('Disposable runner disk capacity exhausted; runtime proof is incomplete')
        time.sleep(1)
    raise TimeoutError(f'Timed out waiting for {pattern}')


try:
    wait_for(r'Done \(', 300)
    command(f'oceancanvas pregen start {RADIUS} 0 0 confirm')
    wait_for(rf'PREGEN-ACCEPTANCE-START .*chunks={CHUNKS} widthBlocks={WIDTH} centerX=0 centerZ=0', 120)
    wait_for(rf'PREGEN-ACCEPTANCE-DONE .*chunks={CHUNKS}', STAGE_SECONDS)
    command('oceancanvas diagnostics')
    wait_for(r'Diagnostic bundle .* written to ', 120)
    command('save-all flush')
    command('stop')
    process.wait(timeout=180)
    thread.join(timeout=10)
    if process.returncode:
        raise RuntimeError(f'Server shutdown exit={process.returncode}')

    audit = [
        'python3', str(ROOT / 'scripts/ci/production-acceptance.py'), str(LOG),
        '--expected-build', BUILD,
        '--expected-chunks', str(CHUNKS),
        '--expected-size-blocks', str(WIDTH),
        '--expected-center-x', '0', '--expected-center-z', '0',
        '--require-start', '--require-completion', '--scope-latest-run',
        '--require-structured-evidence', '--max-hours', '8',
        '--json-output', str(OUT / 'acceptance.json'),
    ]
    subprocess.run(audit, check=True)
    print(f'PRODUCTION_{WIDTH}_RUNTIME_PASS chunks={CHUNKS}', flush=True)
finally:
    if process.poll() is None:
        try:
            command('stop')
            process.wait(timeout=120)
        except Exception:
            process.kill()
            process.wait(timeout=30)
    thread.join(timeout=10)
    for p in RUN.rglob('*.zip'):
        shutil.copy2(p, OUT / p.name)
    if RUN.exists():
        shutil.rmtree(RUN)
