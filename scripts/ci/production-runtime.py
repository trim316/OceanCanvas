#!/usr/bin/env python3
"""Fresh disposable server proof of an already-built production JAR."""
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
from runtime_support import prepare_server, cached_download, bind_checkpoint
import zipfile

def verify_candidate(candidate, build, source):
    """Reject stale/mislabeled artifacts before starting Minecraft."""
    if not build or not re.fullmatch(r'v[0-9]+(?:\.[0-9]+)+', build):
        raise ValueError('EXPECTED_BUILD must explicitly identify the candidate')
    if not source or not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('EXPECTED_SOURCE_COMMIT must explicitly identify the checkout')
    props = {}
    for line in (candidate / 'candidate.properties').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in props:
            raise ValueError(f'Duplicate candidate property: {key}')
        props[key] = value
    if props.get('sourceCommit') != source:
        raise ValueError('Unexpected production candidate source identity')
    # Older immutable baseline artifacts did not include runtimeBuild.
    if 'runtimeBuild' in props and props['runtimeBuild'] != build:
        raise ValueError('Candidate manifest build differs from requested build')
    jar = candidate / f'oceancanvas-26.2-{build}.jar'
    if sorted(candidate.glob('*.jar')) != [jar]:
        raise ValueError('Candidate directory must contain only the exact requested JAR')
    expected = (candidate / 'JAR-SHA256.txt').read_text().split()[0]
    actual = hashlib.sha256(jar.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError('Candidate JAR checksum mismatch')
    with zipfile.ZipFile(jar) as archive:
        metadata = json.loads(archive.read('fabric.mod.json'))
        if metadata.get('id') != 'oceancanvas' or metadata.get('version') != f'26.2-{build}':
            raise ValueError('Packaged Fabric identity differs from requested build')
        if 'net/oceancanvas/mod/OceanCanvas.class' not in archive.namelist():
            raise ValueError('Candidate lacks the production mod entrypoint')
    return jar, actual


ROOT = pathlib.Path.cwd()
OUT = ROOT / 'production-runtime-evidence'
RUN = OUT / 'server'
CANDIDATE = ROOT / 'production-candidate'
BUILD = os.environ.get('EXPECTED_BUILD')
SOURCE = os.environ.get('EXPECTED_SOURCE_COMMIT')
SEED = int(os.environ.get('OC_TEST_SEED', '4182026'))
WIDTH = int(os.environ.get('OC_TEST_WIDTH', '500'))
CENTER_X = int(os.environ.get('OC_CENTER_X', '0'))
CENTER_Z = int(os.environ.get('OC_CENTER_Z', '0'))
if WIDTH not in (128, 500, 1000):
    raise ValueError('Unsupported focused test width')
RADIUS = WIDTH // 2
CHUNKS = ((CENTER_X + RADIUS - 1) // 16 - (CENTER_X - RADIUS) // 16 + 1) * ((CENTER_Z + RADIUS - 1) // 16 - (CENTER_Z - RADIUS) // 16 + 1)
JAR, actual = verify_candidate(CANDIDATE, BUILD, SOURCE)
OUT.mkdir(exist_ok=True)
if RUN.exists():
    raise SystemExit('Refusing to reuse a world: fresh server directory already exists')
RUN.mkdir()
(OUT / 'identity.json').write_text(json.dumps({
    'jarSha256': actual, 'sourceCommit': SOURCE, 'build': BUILD,
    'freshWorld': True, 'targetBlocks': WIDTH, 'targetChunks': CHUNKS,
    'worldSeed': SEED, 'centerX': CENTER_X, 'centerZ': CENTER_Z,
    'releaseVerdict': 'HOLD', 'scope': f'production-{WIDTH}-only',
}, indent=2))

def download(url, path):
    with urllib.request.urlopen(url, timeout=120) as response:
        with path.open('wb') as target:
            shutil.copyfileobj(response, target)

prepare_server(RUN, ROOT / '.runtime-cache')
(RUN / 'mods').mkdir(exist_ok=True)
shutil.copy2(JAR, RUN / 'mods' / JAR.name)
cached_download('https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.156.0+26.2/fabric-api-0.156.0+26.2.jar', RUN / 'mods' / 'fabric-api.jar', ROOT / '.runtime-cache' / 'downloads')
(RUN / 'eula.txt').write_text('eula=true\n')
(RUN / 'server.properties').write_text(
    f'level-name=world\nlevel-seed={SEED}\nonline-mode=false\n'
    'server-ip=127.0.0.1\nview-distance=2\nsimulation-distance=2\n'
    'pause-when-empty-seconds=-1\nmax-tick-time=120000\n')
(RUN / 'config').mkdir()
(RUN / 'config' / 'oceancanvas.properties').write_text(
    'canvasSize=20000\ncenterX=0\ncenterZ=0\npregenEnabled=true\n'
    'oceanFloorY=-25\nbackupEnabled=true\n')
LOG = OUT / 'console.log'
lines = []
profile_args = ['-XX:StartFlightRecording=filename=profile.jfr,settings=profile,dumponexit=true', '-Xlog:gc*:file=gc.log'] if os.environ.get('OC_PROFILE') == 'true' else []
process = subprocess.Popen(['java', '-Xms1G', '-Xmx4G', *profile_args, '-jar',
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
    command(f'oceancanvas pregen start {RADIUS} {CENTER_X} {CENTER_Z} confirm')
    wait_for(rf'PREGEN-ACCEPTANCE-START .*chunks={CHUNKS} widthBlocks={WIDTH} centerX={CENTER_X} centerZ={CENTER_Z}', 120)
    wait_for(rf'PREGEN-ACCEPTANCE-DONE .*chunks={CHUNKS}', 1800)
    command('oceancanvas diagnostics')
    wait_for(r'Diagnostic bundle .* written to ', 120)
    command('save-all flush')
    command('stop')
    process.wait(timeout=180)
    thread.join(timeout=10)
    if process.returncode:
        raise RuntimeError(f'Server shutdown exit={process.returncode}')
    subprocess.run(['python3', str(ROOT / 'scripts/ci/production-acceptance.py'), str(LOG),
                    '--expected-build', BUILD, '--expected-chunks', str(CHUNKS),
                    '--expected-size-blocks', str(WIDTH), '--expected-center-x', str(CENTER_X),
                    '--expected-center-z', str(CENTER_Z), '--require-start', '--require-completion',
                    '--scope-latest-run', '--require-structured-evidence',
                    '--json-output', str(OUT / 'acceptance.json')], check=True)
    # Restart the saved disposable world using exactly the same installed JAR.
    # This is a restart smoke test; the full 1,024-chunk lighting proof above
    # remains the scope of the runtime certificate.
    LOG = OUT / 'restart.log'
    lines = []
    process = subprocess.Popen(['java', '-Xms1G', '-Xmx4G', '-jar',
                                'fabric-server-launch.jar', 'nogui'], cwd=RUN,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    thread = threading.Thread(target=collect, daemon=True)
    thread.start()
    wait_for(r'Done \(', 300)
    command('oceancanvas diagnostics')
    wait_for(r'Diagnostic bundle .* written to ', 120)
    command('save-all flush')
    command('stop')
    process.wait(timeout=180)
    thread.join(timeout=10)
    if process.returncode or any('[Server thread/ERROR]' in line for line in lines):
        raise RuntimeError('Saved-world restart smoke test failed')
    (OUT / 'restart.json').write_text(json.dumps({
        'passed': True, 'build': BUILD, 'sourceCommit': SOURCE,
        'jarSha256': actual, 'scope': 'saved-world-restart-smoke',
        'logSha256': hashlib.sha256(LOG.read_bytes()).hexdigest(),
    }, indent=2))
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
    for name in ('profile.jfr', 'gc.log'):
        if (RUN / name).is_file(): shutil.copy2(RUN / name, OUT / name)
    if (OUT / 'profile.jfr').is_file():
        with (OUT / 'profile-summary.txt').open('w') as summary:
            subprocess.run(['jfr', 'summary', str(OUT / 'profile.jfr')], stdout=summary, check=True)
    shutil.rmtree(RUN)
