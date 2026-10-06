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
from runtime_stall import PhysicalMutationStall
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
NATURAL_BORDER = os.environ.get('OC_NATURAL_BORDER') == 'true'
AUTHORED_RADIUS = RADIUS + (16 if NATURAL_BORDER else 0)
AUTHORED_WIDTH = AUTHORED_RADIUS * 2
CHUNKS = ((CENTER_X + AUTHORED_RADIUS - 1) // 16 - (CENTER_X - AUTHORED_RADIUS) // 16 + 1) * ((CENTER_Z + AUTHORED_RADIUS - 1) // 16 - (CENTER_Z - AUTHORED_RADIUS) // 16 + 1)
PREGEN_COMMAND = f'oceancanvas pregen start {RADIUS} {CENTER_X} {CENTER_Z} confirm' + (' border' if NATURAL_BORDER else '')
JAR, actual = verify_candidate(CANDIDATE, BUILD, SOURCE)
OUT.mkdir(exist_ok=True)
if RUN.exists():
    raise SystemExit('Refusing to reuse a world: fresh server directory already exists')
RUN.mkdir()
(OUT / 'identity.json').write_text(json.dumps({
    'jarSha256': actual, 'sourceCommit': SOURCE, 'build': BUILD,
    'freshWorld': True, 'requestedBlocks': WIDTH, 'naturalBorderBlocks': 16 if NATURAL_BORDER else 0,
    'targetBlocks': AUTHORED_WIDTH, 'targetChunks': CHUNKS,
    'worldSeed': SEED, 'centerX': CENTER_X, 'centerZ': CENTER_Z,
    'releaseVerdict': 'HOLD', 'scope': f'production-{AUTHORED_WIDTH}-only',
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
line_events = []
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
            line_events.append((time.monotonic(), line))
            lines.append(line)
            print(line, end='', flush=True)
thread = threading.Thread(target=collect, daemon=True)
thread.start()
def command(value):
    process.stdin.write(value + '\n')
    process.stdin.flush()
def wait_for(pattern, seconds, *, completion_guard=False):
    guard = PhysicalMutationStall() if completion_guard else None
    cursor = 0
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if guard is None:
            if any(re.search(pattern, line) for line in lines):
                return
        else:
            while cursor < len(line_events):
                arrived, line = line_events[cursor]
                cursor += 1
                if re.search(pattern, line):
                    return
                verdict = guard.observe(line, arrived)
                if verdict is not None:
                    (OUT / 'stall.json').write_text(json.dumps({
                        'passed': False, 'build': BUILD, 'sourceCommit': SOURCE,
                        'jarSha256': actual, 'chunk': verdict.chunk,
                        'repeats': verdict.repeats, 'stalledSeconds': verdict.stalled_seconds,
                        'reason': verdict.reason, 'scope': 'completion-wait-failure-only',
                    }, indent=2))
                    raise RuntimeError(f'Physical mutation stalled at {verdict.chunk}: {verdict.repeats} repeats over {verdict.stalled_seconds:.1f}s')
        if process.poll() is not None:
            raise RuntimeError(f'Server exited {process.returncode} before {pattern}')
        time.sleep(1)
    raise TimeoutError(f'Timed out waiting for {pattern}')
validated = False
try:
    wait_for(r'Done \(', 300)
    if os.environ.get('OC_WATERFALL_FIXTURE') == 'true':
        if (WIDTH, CENTER_X, CENTER_Z) != (128, -464, 436):
            raise ValueError('Waterfall fixture requires its exact disposable boundary scope')
        # An external source is a test fixture, not permission to mutate outside
        # the operation during repair. Its inflow must be diagnosed explicitly.
        command('forceload add -480 496 -465 511')
        for attempt in range(60):
            command('execute if loaded -475 79 500 run say OC-WATERFALL-AREA-LOADED')
            time.sleep(1)
            if any('OC-WATERFALL-AREA-LOADED' in line for line in lines):
                break
        else:
            raise TimeoutError('Waterfall fixture chunk did not become loaded')
        command('setblock -475 79 500 minecraft:water')
        command('execute if block -475 79 500 minecraft:water[level=0] run say OC-WATERFALL-FIXTURE-READY')
        wait_for(r'OC-WATERFALL-FIXTURE-READY', 30)
    command(PREGEN_COMMAND)
    wait_for(rf'PREGEN-ACCEPTANCE-START .*chunks={CHUNKS} widthBlocks={AUTHORED_WIDTH} centerX={CENTER_X} centerZ={CENTER_Z}', 120)
    wait_for(rf'PREGEN-ACCEPTANCE-DONE .*chunks={CHUNKS}', int(os.environ.get('OC_STAGE_SECONDS') or '1800'), completion_guard=True)
    if os.environ.get('OC_WATERFALL_FIXTURE') == 'true':
        command('forceload remove -480 496 -465 511')
    command('oceancanvas diagnostics')
    wait_for(r'Diagnostic bundle .* written to ', 120)
    gameplay = os.environ.get('OC_GAMEPLAY_FIXTURE') == 'true'
    if gameplay:
        if (CENTER_X, CENTER_Z) != (0, 0):
            raise ValueError('Gameplay fixture requires the origin disposable world')
        command('forceload add 0 0 31 31')
        for attempt in range(60):
            command('execute if loaded 8 80 8 if loaded 16 80 8 run say OC-GAMEPLAY-AREA-LOADED')
            time.sleep(1)
            if any('OC-GAMEPLAY-AREA-LOADED' in line for line in lines):
                break
        else:
            raise TimeoutError('Gameplay fixture chunk did not become loaded')
        command('setblock 8 80 8 minecraft:glass')
        command('setblock 8 80 8 minecraft:air')
        command('execute if block 8 80 8 minecraft:air run say OC-GAMEPLAY-BREAK-VERIFIED')
        wait_for(r'OC-GAMEPLAY-BREAK-VERIFIED', 30)
        command('setblock 8 80 8 minecraft:glass')
        command('setblock 15 80 8 minecraft:glass')
        command('setblock 16 80 8 minecraft:glass')
        command('setblock 12 80 8 minecraft:water')
        time.sleep(5)
        command('execute if block 8 80 8 minecraft:glass run say OC-GAMEPLAY-BUILD-BEFORE-SAVE')
        command('execute if block 12 80 8 minecraft:water[level=0] run say OC-GAMEPLAY-WATER-BEFORE-SAVE')
        wait_for(r'OC-GAMEPLAY-BUILD-BEFORE-SAVE', 30)
        wait_for(r'OC-GAMEPLAY-WATER-BEFORE-SAVE', 30)
        command('execute if block 15 80 8 minecraft:glass if block 16 80 8 minecraft:glass run say OC-GAMEPLAY-SEAM-BEFORE-SAVE')
        wait_for(r'OC-GAMEPLAY-SEAM-BEFORE-SAVE', 30)
    command('save-all flush')
    command('stop')
    process.wait(timeout=180)
    thread.join(timeout=10)
    if process.returncode:
        raise RuntimeError(f'Server shutdown exit={process.returncode}')
    subprocess.run(['python3', str(ROOT / 'scripts/ci/production-acceptance.py'), str(LOG),
                    '--expected-build', BUILD, '--expected-chunks', str(CHUNKS),
                    '--expected-size-blocks', str(AUTHORED_WIDTH), '--expected-center-x', str(CENTER_X),
                    '--expected-center-z', str(CENTER_Z), '--require-start', '--require-completion',
                    '--scope-latest-run', '--require-structured-evidence',
                    '--json-output', str(OUT / 'acceptance.json')], check=True)
    # Restart the saved disposable world using exactly the same installed JAR.
    # This is a restart smoke test; the full 1,024-chunk lighting proof above
    # remains the scope of the runtime certificate.
    LOG = OUT / 'restart.log'
    lines = []
    line_events = []
    process = subprocess.Popen(['java', '-Xms1G', '-Xmx4G', '-jar',
                                'fabric-server-launch.jar', 'nogui'], cwd=RUN,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    thread = threading.Thread(target=collect, daemon=True)
    thread.start()
    wait_for(r'Done \(', 300)
    if gameplay:
        command('forceload add 0 0 31 31')
        for attempt in range(60):
            command('execute if loaded 8 80 8 if loaded 16 80 8 run say OC-GAMEPLAY-RESTART-AREA-LOADED')
            time.sleep(1)
            if any('OC-GAMEPLAY-RESTART-AREA-LOADED' in line for line in lines):
                break
        else:
            raise TimeoutError('Gameplay restart fixture chunks did not become loaded')
        command('execute if block 8 80 8 minecraft:glass run say OC-GAMEPLAY-BUILD-AFTER-RESTART')
        command('execute if block 12 80 8 minecraft:water[level=0] run say OC-GAMEPLAY-WATER-AFTER-RESTART')
        wait_for(r'OC-GAMEPLAY-BUILD-AFTER-RESTART', 30)
        wait_for(r'OC-GAMEPLAY-WATER-AFTER-RESTART', 30)
        command('execute if block 15 80 8 minecraft:glass if block 16 80 8 minecraft:glass run say OC-GAMEPLAY-SEAM-AFTER-RESTART')
        wait_for(r'OC-GAMEPLAY-SEAM-AFTER-RESTART', 30)
        command('forceload remove all')
        (OUT / 'gameplay.json').write_text(json.dumps({
            'passed': True, 'build': BUILD, 'sourceCommit': SOURCE, 'jarSha256': actual,
            'scope': 'command-driven-break-replace-chunk-seam-and-water-save-restart',
            'checks': ['break-replace', 'adjacent-chunk-block-persistence', 'water-source-persistence'],
            'visualOrPlayerMovementVerified': False,
        }, indent=2))
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
    validated = True
    print(f'PRODUCTION_{AUTHORED_WIDTH}_RUNTIME_PASS', flush=True)
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
    if not validated and (RUN / 'world').is_dir():
        # Keep only the failed world/config, not downloaded server libraries.
        # This diagnostic snapshot is not automatically admitted as certification.
        with zipfile.ZipFile(OUT / 'failed-world.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
            for folder in ('world', 'config'):
                for file in (RUN / folder).rglob('*'):
                    if file.is_file(): archive.write(file, file.relative_to(RUN).as_posix())
    if (OUT / 'profile.jfr').is_file():
        with (OUT / 'profile-summary.txt').open('w') as summary:
            subprocess.run(['jfr', 'summary', str(OUT / 'profile.jfr')], stdout=summary, check=True)
    shutil.rmtree(RUN)
