#!/usr/bin/env python3
"""Fresh-world scale proof for an exact Ocean Canvas production candidate."""
import hashlib
from datetime import datetime, timezone
from scale_evidence import open_timing, finish_timing, verify_installed_jar
from candidate_verifier import verify_candidate
from scale_geometry import ScaleGeometry
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
from checkpoint_bundle import write_manifest, verify_manifest

WIDTH = int(os.environ.get('OC_TEST_WIDTH', '5000'))
if WIDTH not in (500, 2000, 5000, 10000, 20000):
    raise SystemExit('Unsupported scale width')
NATURAL_BORDER = os.environ.get('OC_NATURAL_BORDER') == 'true'
GEOMETRY = ScaleGeometry(WIDTH, NATURAL_BORDER)
AUTHORED_WIDTH = GEOMETRY.authored_width
CHUNKS = GEOMETRY.chunks
STAGE_SECONDS = int(os.environ.get('OC_STAGE_SECONDS', '9600'))
BUILD = os.environ.get('EXPECTED_BUILD')
SOURCE = os.environ.get('EXPECTED_SOURCE_COMMIT')
if not BUILD or not SOURCE:
    raise SystemExit('EXPECTED_BUILD and EXPECTED_SOURCE_COMMIT are required')

ROOT = pathlib.Path.cwd()
OUT = ROOT / os.environ.get('OC_EVIDENCE_DIR', 'production-runtime-evidence')
RUN = OUT / 'server'
CANDIDATE = ROOT / 'production-candidate'
# Validate before creating a server directory or changing checkpoint evidence.
JAR, actual = verify_candidate(CANDIDATE, BUILD, SOURCE)
OUT.mkdir(exist_ok=True)
RESUME = os.environ.get('OC_RESUME') == 'true'
if RUN.exists() != RESUME:
    raise SystemExit('Resume requires an existing checkpoint; fresh runs require an empty directory')
RUN.mkdir(exist_ok=RESUME)

checkpoint_identity = dict(jarSha256=actual, sourceCommit=SOURCE, build=BUILD,
                           targetBlocks=AUTHORED_WIDTH, targetChunks=CHUNKS,
                           seed=4182026, profile='OVERNIGHT', floorY=-25,
                           minecraft='26.2', loader='0.19.3', **GEOMETRY.checkpoint_fields())
if RESUME and (OUT / 'checkpoint-manifest.json').exists():
    verify_manifest(OUT, checkpoint_identity)
elif RESUME and os.environ.get('OC_REQUIRE_CHECKPOINT_MANIFEST') == 'true':
    raise SystemExit('Resume requires a verified checkpoint manifest')
bind_checkpoint(OUT / 'checkpoint.json', checkpoint_identity, RESUME)
TIMING = OUT / 'checkpoint-timing.json'
open_timing(TIMING, checkpoint_identity, RESUME)

(OUT / 'identity.json').write_text(json.dumps({
    'jarSha256': actual,
    'sourceCommit': SOURCE,
    'build': BUILD,
    'freshWorld': True,
    'targetBlocks': AUTHORED_WIDTH,
    'requestedBlocks': WIDTH, 'naturalBorderBlocks': 16 if NATURAL_BORDER else 0,
    'operationBlocks': GEOMETRY.operation_width,
    'targetChunks': CHUNKS,
    'releaseVerdict': 'HOLD',
    'scope': f'production-{WIDTH}-scale',
    'resumed': RESUME,
}, indent=2) + '\n', encoding='utf-8')


def download(url, path):
    with urllib.request.urlopen(url, timeout=120) as response:
        with path.open('wb') as target:
            shutil.copyfileobj(response, target)


prepare_server(RUN, ROOT / '.runtime-cache')
(RUN / 'mods').mkdir(exist_ok=True)
shutil.copy2(JAR, RUN / 'mods' / JAR.name)
cached_download('https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.156.0+26.2/fabric-api-0.156.0+26.2.jar', RUN / 'mods' / 'fabric-api.jar', ROOT / '.runtime-cache' / 'downloads')
(RUN / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
(RUN / 'server.properties').write_text(
    'level-name=world\nlevel-seed=4182026\nonline-mode=false\n'
    'server-ip=127.0.0.1\nview-distance=2\nsimulation-distance=2\n'
    'pause-when-empty-seconds=-1\nmax-tick-time=120000\n', encoding='utf-8')
(RUN / 'config').mkdir(exist_ok=True)
(RUN / 'config' / 'oceancanvas.properties').write_text(
    'canvasSize=20000\ncenterX=0\ncenterZ=0\npregenEnabled=true\n'
    'oceanFloorY=-25\nbackupEnabled=true\n', encoding='utf-8')

LOG = OUT / 'console.log'
lines = []
# Only live output from this process is eligible for the stall guard. Historical
# checkpoint console.log remains on disk for certification but is not replayed.
line_events = []
process = subprocess.Popen(['java', '-Xms1G', '-Xmx4G', '-jar',
                            'fabric-server-launch.jar', 'nogui'], cwd=RUN,
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, bufsize=1)


def collect():
    # Preserve every line in the evidence file, but avoid per-line disk flushes
    # and Actions console writes competing with the Minecraft process.
    important = re.compile(r'PREGEN-ACCEPTANCE|progress:|ERROR|Exception|Diagnostic bundle|Done \(|Pregen profile:')
    last_flush = time.monotonic()
    with LOG.open('a' if RESUME else 'w', encoding='utf-8', buffering=65536) as target:
        for line in process.stdout:
            line = '[' + datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S.%f') + '] ' + line
            target.write(line)
            now = time.monotonic()
            line_events.append((now, line))
            lines.append(line)
            if important.search(line):
                print(line, end='', flush=True)
            if now - last_flush >= 5:
                target.flush()
                last_flush = now


thread = threading.Thread(target=collect, daemon=True)
thread.start()


def command(value):
    process.stdin.write(value + '\n')
    process.stdin.flush()


def wait_for(pattern, seconds, *, completion_guard=False):
    guard = PhysicalMutationStall() if completion_guard else None
    deadline = time.monotonic() + seconds
    matcher = re.compile(pattern)
    cursor = 0
    last_save = time.monotonic()
    while time.monotonic() < deadline:
        # Each wait must still see earlier output (a marker can arrive before
        # admission), but scan each line only once rather than once per second.
        end = len(line_events) if guard is not None else len(lines)
        for index in range(cursor, end):
            if guard is None:
                line = lines[index]
            else:
                arrived, line = line_events[index]
            if matcher.search(line):
                return
            if guard is not None:
                verdict = guard.observe(line, arrived)
                if verdict is not None:
                    (OUT / 'stall.json').write_text(json.dumps({
                        'passed': False, 'build': BUILD, 'sourceCommit': SOURCE,
                        'jarSha256': actual, 'chunk': verdict.chunk,
                        'repeats': verdict.repeats, 'stalledSeconds': verdict.stalled_seconds,
                        'reason': verdict.reason, 'scope': 'scale-completion-wait-failure-only',
                        'resumed': RESUME, 'targetBlocks': AUTHORED_WIDTH, 'targetChunks': CHUNKS,
                    }, indent=2))
                    raise RuntimeError(f'Physical mutation stalled at {verdict.chunk}: {verdict.repeats} repeats over {verdict.stalled_seconds:.1f}s')
        cursor = end
        if process.poll() is not None:
            raise RuntimeError(f'Server exited {process.returncode} before {pattern}')
        if shutil.disk_usage(RUN).free < 1024 ** 3:
            raise RuntimeError('Disposable runner disk capacity exhausted; runtime proof is incomplete')
        if time.monotonic() - last_save >= 300:
            command('save-all flush')
            last_save = time.monotonic()
        time.sleep(1)
    raise TimeoutError(f'Timed out waiting for {pattern}')


completion_observed = False
segment_saved = False
try:
    wait_for(r'Done \(', 300)
    # Scale certification must exercise the product's explicit overnight profile.
    # The previous 5k proof accidentally used the BALANCED default and timed out
    # at 72% with healthy forward progress and zero persistent lighting faults.
    command('oceancanvas pregen profile overnight')
    wait_for(r'Pregen profile: OVERNIGHT\.', 30)
    if RESUME:
        # The mod resumes its durable job on level load. Never issue a second
        # start command over it, and never infer successful resume from terrain.
        wait_for(r'Resumed .* job after restart:', 120)
    else:
        command(GEOMETRY.command)
    if not RESUME:
        wait_for(rf'PREGEN-ACCEPTANCE-START .*chunks={CHUNKS} widthBlocks={AUTHORED_WIDTH} centerX=0 centerZ=0', 120)
    try:
        wait_for(rf'PREGEN-ACCEPTANCE-DONE .*chunks={CHUNKS}', STAGE_SECONDS, completion_guard=True)
    except TimeoutError:
        if os.environ.get('OC_CHECKPOINT_SEGMENT') != 'true':
            raise
        segment_saved = True
        print('CHECKPOINT_SEGMENT_PENDING: completion not observed; save and resume the same world', flush=True)
        raise SystemExit(0)
    completion_observed = True
    finish_timing(TIMING, checkpoint_identity, max_hours=8)
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
        '--expected-size-blocks', str(AUTHORED_WIDTH),
        '--expected-center-x', '0', '--expected-center-z', '0',
        '--require-start', '--require-completion', '--scope-latest-run',
        '--require-structured-evidence', '--max-hours', '8',
        '--json-output', str(OUT / 'acceptance.json'),
    ]
    subprocess.run(audit, check=True)
    # Reopen the same saved world and exact installed binary; never regenerate.
    original_log = LOG
    LOG = OUT / 'restart.log'
    lines = []
    line_events = []
    verify_installed_jar(RUN / 'mods' / JAR.name, actual)
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
    verify_installed_jar(RUN / 'mods' / JAR.name, actual)
    (OUT / 'restart.json').write_text(json.dumps({
        'passed': True, 'build': BUILD, 'sourceCommit': SOURCE,
        'jarSha256': actual, 'targetBlocks': AUTHORED_WIDTH,
    'requestedBlocks': WIDTH, 'naturalBorderBlocks': 16 if NATURAL_BORDER else 0,
    'operationBlocks': GEOMETRY.operation_width, 'targetChunks': CHUNKS,
        'scope': 'saved-world-restart-smoke',
        'logSha256': hashlib.sha256(LOG.read_bytes()).hexdigest(),
    }, indent=2))
    LOG = original_log
    print(f'PRODUCTION_{WIDTH}_RUNTIME_PASS chunks={CHUNKS}', flush=True)
finally:
    if process.poll() is None:
        try:
            command('save-all flush')
            command('stop')
            process.wait(timeout=120)
        except Exception:
            process.kill()
            process.wait(timeout=30)
    thread.join(timeout=10)
    for p in RUN.rglob('*.zip'):
        shutil.copy2(p, OUT / p.name)
    # A timeout is still a failure. Keep the saved world and durable job rather
    # than discarding hours of work; only an exact-candidate resume may reuse it.
    (OUT / 'checkpoint-status.json').write_text(json.dumps({
        'resumed': RESUME, 'serverExit': process.returncode,
        'completionObserved': completion_observed,
        'savedWorldPresent': (RUN / 'world').is_dir(),
        'segmentPending': segment_saved, 'scope': 'checkpoint-only-not-certification',
    }, indent=2))
    if process.returncode == 0 and (RUN / 'world/level.dat').is_file():
        write_manifest(OUT, checkpoint_identity)
    elif segment_saved:
        raise RuntimeError('Segment checkpoint did not stop cleanly; preserve diagnostics and do not resume automatically')
