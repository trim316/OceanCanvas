#!/usr/bin/env python3
"""Checkpointed + live-progress 1024x1024 chunk-cover OceanCanvas proof using current runtime."""
import hashlib
import subprocess
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import time
from datetime import datetime, timezone

HERE = Path(__file__).resolve()
spec = importlib.util.spec_from_file_location('fullproof', HERE.with_name('hosted-full-canvas-command-proof.py'))
full = importlib.util.module_from_spec(spec)
spec.loader.exec_module(full)
single = full.single
PROJECT = full.PROJECT
RUN = full.RUN
WORLD = RUN / 'world'
ROOT = WORLD / 'oceancanvas-core'
FULL = ROOT / 'full-canvas'
FLATTEN = FULL / 'operation.properties'
RESTORE = FULL / 'restore.properties'
SHIP = ROOT / 'shipwreck-population' / 'operation.properties'
MON = ROOT / 'monument-population' / 'operation.properties'
HEALTH = ROOT / 'health-scan' / 'operation.properties'
OUTPUT = Path(os.environ.get('GITHUB_WORKSPACE', '.')) / 'hosted-1k-checkpointed-live'
LOGDIR = OUTPUT / 'logs'
CHECKPOINT = Path(os.environ.get('GITHUB_WORKSPACE', '.')) / 'checkpoint-1k-evidence'
STARTED = RUN / 'oceancanvas-1k-started.json'
SEED = os.environ.get('OCEANCANVAS_1K_SEED', '4182042')
SOURCE = os.environ.get('OCEANCANVAS_PRODUCT_SOURCE', os.environ.get('GITHUB_SHA', 'unknown'))
CANVAS_SIZE = 1024
IDENTITY = {'sourceCommit': SOURCE, 'requestedBlocks': 1000, 'targetBlocks': CANVAS_SIZE, 'targetChunks': 4096, 'seed': SEED, 'centerX': 0, 'centerZ': 0}
HEARTBEAT = 30
LAST_STOP_CLEAN = False
BINARY_EVIDENCE = OUTPUT / 'runtime-context-evidence'
full.OUTPUT = LOGDIR

sys.path.insert(0, str(HERE.parent))
from checkpoint_bundle import write_manifest, archive_checkpoint


def props(path):
    return single.properties(path) if path.is_file() else {}


def bind_runtime_context():
    properties = single.properties(PROJECT / 'gradle.properties')
    jar = PROJECT / 'build' / 'libs' / (properties['archives_base_name'] + '-' + properties['mod_version'] + '.jar')
    if not jar.is_file(): raise RuntimeError('build the exact candidate before binding runtime context')
    files = []
    for directory in ('build/classes/java/main', 'build/resources/main'):
        entries = sorted(p for p in (PROJECT / directory).rglob('*') if p.is_file())
        if not entries: raise RuntimeError('missing development runtime payload: ' + directory)
        files += entries
    files += [PROJECT / 'gradle.properties', PROJECT / 'build.gradle']
    manifest = {'schema': 1, 'runtimeMode': 'gradle-development-not-installed-jar',
                'sourceCommit': SOURCE,
                'javaVersion': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True).strip(),
                'files': {p.relative_to(PROJECT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
    encoded = (json.dumps(manifest, sort_keys=True, indent=2) + '\n').encode()
    IDENTITY.update(candidateJarName=jar.name, candidateJarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                    runtimeMode=manifest['runtimeMode'], runtimeContextSha256=hashlib.sha256(encoded).hexdigest())
    if BINARY_EVIDENCE.exists():
        if (BINARY_EVIDENCE / 'runtime-context/context.json').read_bytes() != encoded:
            raise RuntimeError('runtime context changed; preserve parent and refuse implicit upgrade')
        if hashlib.sha256((BINARY_EVIDENCE / 'candidate' / jar.name).read_bytes()).hexdigest() != IDENTITY['candidateJarSha256']:
            raise RuntimeError('retained candidate differs from exact built candidate')
        for name, expected in manifest['files'].items():
            if hashlib.sha256((BINARY_EVIDENCE / 'runtime-context' / name).read_bytes()).hexdigest() != expected:
                raise RuntimeError('retained runtime payload differs: ' + name)
        return
    (BINARY_EVIDENCE / 'candidate').mkdir(parents=True)
    shutil.copy2(jar, BINARY_EVIDENCE / 'candidate' / jar.name)
    for path in files:
        dest = BINARY_EVIDENCE / 'runtime-context' / path.relative_to(PROJECT)
        dest.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(path, dest)
    (BINARY_EVIDENCE / 'runtime-context/context.json').write_bytes(encoded)


def initialize():
    if CANVAS_SIZE <= 0 or CANVAS_SIZE % 16:
        raise ValueError("core proof width must be positive and chunk-aligned")
    bind_runtime_context()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    LOGDIR.mkdir(parents=True, exist_ok=True)
    RUN.mkdir(parents=True, exist_ok=True)
    if not (RUN / 'server.properties').exists():
        (RUN / 'eula.txt').write_text('eula=true\n')
        (RUN / 'server.properties').write_text(
            'level-name=world\nlevel-seed=' + SEED + '\nonline-mode=false\n'
            'spawn-protection=0\nview-distance=2\nsimulation-distance=2\n'
            'pause-when-empty-seconds=-1\n'
            'enable-rcon=true\nrcon.port=' + str(single.RCON_PORT) + '\n'
            'rcon.password=' + single.PASSWORD + '\nserver-port=25591\n')
        cfg = RUN / 'config'; cfg.mkdir(exist_ok=True)
        (cfg / 'oceancanvas-core.properties').write_text(
            f'mode=CORE_AUTHORING\ncanvasSize={CANVAS_SIZE}\ncenterX=0\ncenterZ=0\n'
            'waterSurfaceY=62\noceanFloorY=25\noceanFloorVariation=5\n'
            'expansionEnabled=true\nsingleChunkEnabled=false\nacceptanceHarnessEnabled=false\n'
            'maxBlockWritesPerTick=1024\nmaxChecksPerTick=4096\n'
            'stageWallBudgetMicros=6000\nphysicalSettleTicks=20\nlightSettleTicks=20\n')
    # Each workflow phase is a separate Python process. Reuse the credential
    # written into this disposable server, rather than a new import-time token.
    server_properties = single.properties(RUN / 'server.properties')
    saved_password = server_properties.get('rcon.password', '')
    if not saved_password or server_properties.get('enable-rcon') != 'true':
        raise RuntimeError('saved disposable server lacks enabled RCON credentials')
    single.PASSWORD = saved_password
    single.RCON_PORT = int(server_properties['rcon.port'])
    if not 1 <= single.RCON_PORT <= 65535:
        raise RuntimeError('saved RCON port is invalid')
    configured = single.properties(RUN / 'config' / 'oceancanvas-core.properties')
    if int(configured.get('canvasSize', '0')) != CANVAS_SIZE:
        raise RuntimeError('incompatible saved configuration; preserve parent and refuse implicit geometry upgrade')
    if STARTED.exists() and json.loads(STARTED.read_text())['identity'] != IDENTITY:
        raise RuntimeError('checkpoint identity differs; preserve original source and clock')
    if not STARTED.exists():
        STARTED.write_text(json.dumps({'startedUtc': datetime.now(timezone.utc).isoformat(), 'identity': IDENTITY}, indent=2) + '\n')


def elapsed():
    # Configuration is checked while stopped in initialize(), not while the
    # runtime may be rewriting it. Progress reporting only reads the fixed clock.
    if not STARTED.exists(): return 0.0
    started = json.loads(STARTED.read_text())['startedUtc']
    return (datetime.now(timezone.utc) - datetime.fromisoformat(started)).total_seconds()


def count_state(path):
    p = props(path)
    current = int(p.get('nextIndex', p.get('cursor', p.get('nextCellIndex', '0'))) or 0)
    total = int(p.get('totalChunks', p.get('total', p.get('totalCells', '0'))) or 0)
    return p, current, total


def overall(phase, current=0, total=0):
    frac = (current / total) if total else 0.0
    if phase == 'FLATTEN': return min(45.0, 45.0 * frac)
    if phase == 'STRUCTURES': return 45.0
    if phase == 'HEALTH': return 50.0 + 5.0 * frac
    if phase == 'RESTORE': return 55.0 + 40.0 * frac
    if phase == 'FINAL': return 97.0
    if phase == 'COMPLETE': return 100.0
    return 0.0


def heartbeat(phase, current, total, checkpoint_no, extra=''):
    pct = 100.0 * current / total if total else 0.0
    print(f'OC_PROGRESS checkpoint={checkpoint_no} phase={phase} chunks={current}/{total or "?"} '
          f'phasePct={pct:.2f}% overallApprox={overall(phase,current,total):.2f}% '
          f'elapsedMin={elapsed()/60:.1f} {extra}'.rstrip(), flush=True)


def clean_stop(process, sink):
    global LAST_STOP_CLEAN
    LAST_STOP_CLEAN = False
    response = full.stop(process, sink)
    LAST_STOP_CLEAN = process.returncode == 0
    return response


def snapshot(phase, current, total, checkpoint_no, failure=None):
    if CHECKPOINT.exists(): shutil.rmtree(CHECKPOINT)
    (CHECKPOINT / 'server').mkdir(parents=True)
    if not WORLD.exists(): raise RuntimeError('cannot checkpoint absent world')
    shutil.copytree(WORLD, CHECKPOINT / 'server' / 'world', copy_function=os.link)
    for name in ('candidate', 'runtime-context'):
        shutil.copytree(BINARY_EVIDENCE / name, CHECKPOINT / name)
    shutil.copytree(RUN / 'config', CHECKPOINT / 'server/config')
    shutil.copy2(RUN / 'server.properties', CHECKPOINT / 'server/server.properties')
    timing = json.loads(STARTED.read_text())
    status = {
        'serverExit': 0, 'completionObserved': phase == 'COMPLETE', 'phase': phase,
        'current': current, 'total': total, 'checkpointNumber': checkpoint_no,
        'phasePercent': (100.0 * current / total if total else 0.0),
        'overallApproxPercent': overall(phase, current, total),
        'failure': failure,
        'savedUtc': datetime.now(timezone.utc).isoformat()
    }
    (CHECKPOINT / 'checkpoint.json').write_text(json.dumps(IDENTITY, sort_keys=True, indent=2) + '\n')
    (CHECKPOINT / 'checkpoint-timing.json').write_text(json.dumps(timing, sort_keys=True, indent=2) + '\n')
    (CHECKPOINT / 'checkpoint-status.json').write_text(json.dumps(status, sort_keys=True, indent=2) + '\n')
    write_manifest(CHECKPOINT, IDENTITY)
    archive_checkpoint(CHECKPOINT, IDENTITY)
    heartbeat(phase, current, total, checkpoint_no, 'CHECKPOINT_SAVED')


def launch(label):
    global LAST_STOP_CLEAN
    LAST_STOP_CLEAN = False
    LOGDIR.mkdir(parents=True, exist_ok=True)
    return full.launch(label)


def flatten_segment(target_pct, checkpoint_no):
    initialize()
    if CHECKPOINT.exists(): shutil.rmtree(CHECKPOINT)
    process, sink, log = launch(f'flatten-{checkpoint_no}.log')
    try:
        p = props(FLATTEN)
        if not p:
            full.assert_command_ok(full.rcon('oceancanvas flatten start ERASE_CONFIGURED_CANVAS'), 'flatten start')
            if not FLATTEN.is_file():
                raise RuntimeError('flatten start created no durable state; refusing idle wait')
        elif p.get('status') == 'PAUSED':
            full.assert_command_ok(full.rcon('oceancanvas flatten resume'), 'flatten resume')
        last = 0.0
        segment_seconds = int(os.environ.get('OCEANCANVAS_SEGMENT_SECONDS', '14400'))
        if not 1 <= segment_seconds <= 14400:
            raise ValueError('invalid bounded segment duration')
        deadline = time.monotonic() + segment_seconds
        while True:
            p, current, total = count_state(FLATTEN)
            now = time.monotonic()
            if now - last >= HEARTBEAT:
                heartbeat('FLATTEN', current, total, checkpoint_no, 'status=' + p.get('status','?'))
                last = now
            if p.get('status') == 'COMPLETE' or (total and current / total >= target_pct / 100.0):
                break
            if time.monotonic() >= deadline:
                heartbeat('FLATTEN', current, total, checkpoint_no, 'SEGMENT_PENDING_CLEAN_STOP')
                break
            if process.poll() is not None:
                raise RuntimeError('server exited during flatten segment; log=' + str(log))
            time.sleep(2)
    finally:
        clean_stop(process, sink)
    p, current, total = count_state(FLATTEN)
    snapshot('FLATTEN' if p.get('status') != 'COMPLETE' else 'STRUCTURES', current, total, checkpoint_no)


def wait_state(path, wanted, phase, checkpoint_no, timeout):
    deadline = time.monotonic() + timeout; last = 0.0
    while time.monotonic() < deadline:
        p, current, total = count_state(path)
        now = time.monotonic()
        if now - last >= HEARTBEAT:
            heartbeat(phase, current, total, checkpoint_no, 'status=' + p.get('status','?'))
            last = now
        if p.get('status') == wanted: return p, current, total
        time.sleep(2)
    raise RuntimeError(f'timeout {phase} waiting for {wanted}')


def postflat(checkpoint_no):
    initialize()
    if props(FLATTEN).get('status') != 'COMPLETE': raise RuntimeError('postflat requires flatten COMPLETE')
    if CHECKPOINT.exists(): shutil.rmtree(CHECKPOINT)
    process, sink, log = launch(f'postflat-{checkpoint_no}.log')
    try:
        full.assert_command_ok(full.rcon('oceancanvas structurerules set shipwreck force_on'), 'shipwreck rule')
        full.assert_command_ok(full.rcon('oceancanvas structurerules set monument force_on'), 'monument rule')
        if props(SHIP).get('status') != 'COMPLETE':
            if props(SHIP).get('status') == 'PAUSED': full.rcon('oceancanvas shipwreck populate resume')
            elif not props(SHIP): full.rcon('oceancanvas shipwreck populate start')
            wait_state(SHIP, 'COMPLETE', 'STRUCTURES', checkpoint_no, 1800)
        if props(MON).get('status') != 'COMPLETE':
            if props(MON).get('status') == 'PAUSED': full.rcon('oceancanvas monument populate resume')
            elif not props(MON): full.rcon('oceancanvas monument populate start')
            wait_state(MON, 'COMPLETE', 'STRUCTURES', checkpoint_no, 1800)
        if props(HEALTH).get('status') != 'COMPLETE':
            if not props(HEALTH): full.rcon('oceancanvas health deep')
            wait_state(HEALTH, 'COMPLETE', 'HEALTH', checkpoint_no, 7200)
    finally:
        clean_stop(process, sink)
    _, current, total = count_state(HEALTH)
    snapshot('RESTORE', current, total, checkpoint_no)


def restore_segment(target_pct, checkpoint_no):
    initialize()
    if CHECKPOINT.exists(): shutil.rmtree(CHECKPOINT)
    process, sink, log = launch(f'restore-{checkpoint_no}.log')
    try:
        p = props(RESTORE)
        if not p:
            full.assert_command_ok(full.rcon('oceancanvas restore start RESTORE_CONFIGURED_CANVAS'), 'restore start')
        elif p.get('status') == 'PAUSED':
            full.assert_command_ok(full.rcon('oceancanvas restore resume'), 'restore resume')
        last = 0.0
        while True:
            p, current, total = count_state(RESTORE)
            now = time.monotonic()
            if now - last >= HEARTBEAT:
                heartbeat('RESTORE', current, total, checkpoint_no, 'status=' + p.get('status','?'))
                last = now
            if p.get('status') == 'COMPLETE' or (total and current / total >= target_pct / 100.0): break
            if process.poll() is not None: raise RuntimeError('server exited during restore segment')
            time.sleep(2)
    finally:
        clean_stop(process, sink)
    p, current, total = count_state(RESTORE)
    snapshot('FINAL' if p.get('status') == 'COMPLETE' else 'RESTORE', current, total, checkpoint_no)


def final(checkpoint_no):
    initialize()
    if props(RESTORE).get('status') != 'COMPLETE': raise RuntimeError('final requires restore COMPLETE')
    if CHECKPOINT.exists(): shutil.rmtree(CHECKPOINT)
    process, sink, log = launch('final-cold-restart.log')
    try:
        if props(FLATTEN).get('status') != 'COMPLETE' or props(RESTORE).get('status') != 'COMPLETE':
            raise RuntimeError('terminal state missing after final cold restart')
        full.assert_command_ok(full.rcon('oceancanvas restore status'), 'final restore status')
        heartbeat('COMPLETE', 1, 1, checkpoint_no, 'terminalStateVerified=true')
    finally:
        clean_stop(process, sink)
    snapshot('COMPLETE', 1, 1, checkpoint_no)


def run_with_failure_checkpoint(mode, action, *args):
    try:
        return action(*args)
    except BaseException as failure:
        # The action's finally must have finished a clean stop. Never label a
        # live/unclean world as resumable, or turn partial progress into a pass.
        if LAST_STOP_CLEAN and (WORLD / 'level.dat').is_file():
            state = FLATTEN if mode == 'flatten' else RESTORE if mode in {'restore', 'final'} else HEALTH
            _, current, total = count_state(state)
            snapshot('FAILED_' + mode.upper(), current, total, args[-1], failure=str(failure))
        raise


def main():
    if len(sys.argv) < 3: raise SystemExit('usage: mode target/checkpoint checkpoint?')
    mode = sys.argv[1]
    if mode == 'bind': initialize()
    elif mode in {'flatten','restore'}:
        target = float(sys.argv[2]); checkpoint_no = int(sys.argv[3])
        run_with_failure_checkpoint(mode, flatten_segment if mode == 'flatten' else restore_segment, target, checkpoint_no)
    elif mode == 'postflat': run_with_failure_checkpoint(mode, postflat, int(sys.argv[2]))
    elif mode == 'final': run_with_failure_checkpoint(mode, final, int(sys.argv[2]))
    else: raise SystemExit('unknown mode ' + mode)


if __name__ == '__main__': main()
