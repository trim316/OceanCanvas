#!/usr/bin/env python3
"""Fresh disposable server proof of an already-built production JAR."""
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import threading
import time
import urllib.request

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
    'freshWorld': True, 'targetBlocks': 500, 'targetChunks': 1024,
    'releaseVerdict': 'HOLD', 'scope': 'production-500-only',
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
process = subprocess.Popen(['java', '-Xms1G', '-Xmx4G', '-jar',
                            'fabric-server-launch.jar', 'nogui'], cwd=RUN,
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, bufsize=1)
def collect():
    with LOG.open('w') as target:
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
        time.sleep(1)
    raise TimeoutError(f'Timed out waiting for {pattern}')
try:
    wait_for(r'Done \(', 300)
    command('oceancanvas pregen start 250 0 0 confirm')
    wait_for(r'PREGEN-ACCEPTANCE-START .*chunks=1024 widthBlocks=500 centerX=0 centerZ=0', 120)
    wait_for(r'PREGEN-ACCEPTANCE-DONE .*chunks=1024', 1800)
    command('oceancanvas diagnostics')
    wait_for(r'Diagnostic bundle .* written to ', 120)
    command('save-all flush')
    command('stop')
    process.wait(timeout=180)
    thread.join(timeout=10)
    if process.returncode:
        raise RuntimeError(f'Server shutdown exit={process.returncode}')
    subprocess.run(['python3', str(ROOT / 'scripts/ci/production-acceptance.py'), str(LOG),
                    '--expected-build', 'v253.125.54', '--expected-chunks', '1024',
                    '--expected-size-blocks', '500', '--expected-center-x', '0',
                    '--expected-center-z', '0', '--require-start', '--require-completion',
                    '--scope-latest-run', '--require-structured-evidence',
                    '--json-output', str(OUT / 'acceptance.json')], check=True)
    print('PRODUCTION_500_RUNTIME_PASS', flush=True)
finally:
    if process.poll() is None:
        try:
            command('stop')
            process.wait(timeout=120)
        except Exception:
            process.kill()
            process.wait(timeout=30)
    thread.join(timeout=10)
    # Logs and native diagnostics are retained; the large disposable world is
    # excluded from uploads. No user world or workstation is accessed.
    for p in RUN.rglob('*.zip'):
        shutil.copy2(p, OUT / p.name)
    shutil.rmtree(RUN)
