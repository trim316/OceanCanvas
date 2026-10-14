"""Fork stopped diagnostic worlds across candidates; never produce release proof."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import threading
import time

from candidate_verifier import verify_candidate
from runtime_support import prepare_server, cached_download


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inventory(root, folders=('world', 'config')):
    files = {}
    for folder in folders:
        if (root / 'server' / folder).is_symlink():
            raise ValueError('Snapshot cannot contain symlink directories')
        for path in (root / 'server' / folder).rglob('*'):
            if path.is_symlink():
                raise ValueError('Snapshot cannot contain symlinks')
            if path.is_file():
                files[path.relative_to(root).as_posix()] = digest(path)
    return files


def write_snapshot(root, identity, server_exit, known_world_files=None):
    root = Path(root)
    if server_exit != 0 or not (root / 'server/world/level.dat').is_file():
        raise ValueError('Replay snapshot requires a clean server stop')
    # Scale checkpoints have just hashed the stopped world. Reuse those hashes
    # rather than reading a multi-GB world twice before upload.
    files = (inventory(root) if known_world_files is None else
             dict({name: value for name, value in known_world_files.items()
                   if name.startswith('server/world/')}, **inventory(root, ('config',))))
    if not any(name.startswith('server/config/') for name in files):
        raise ValueError('Snapshot requires the original configuration')
    payload = dict(schema=1, identity=identity, files=files, serverExit=0,
                   scope='diagnostic-replay-only', freshWorld=False)
    temporary = root / 'replay-manifest.json.partial'
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding='utf-8')
    temporary.replace(root / 'replay-manifest.json')
    return payload


def verify_snapshot(root):
    root = Path(root)
    payload = json.loads((root / 'replay-manifest.json').read_text(encoding='utf-8'))
    if (payload.get('schema') != 1 or payload.get('serverExit') != 0
            or payload.get('scope') != 'diagnostic-replay-only'
            or payload.get('freshWorld') is not False):
        raise ValueError('Invalid diagnostic snapshot')
    if 'server/world/level.dat' not in payload.get('files', {}):
        raise ValueError('Snapshot lacks level.dat')
    if inventory(root) != payload['files']:
        raise ValueError('Snapshot inventory or checksum mismatch')
    return payload


def fork_snapshot(parent, out, candidate, build, source):
    parent, out = Path(parent).resolve(), Path(out).resolve()
    if out.exists() or out.is_relative_to(parent) or parent.is_relative_to(out):
        raise ValueError('Replay must use a new directory separate from the parent')
    original = verify_snapshot(parent)
    jar, jar_sha = verify_candidate(Path(candidate), build, source)
    out.mkdir(parents=True)
    for folder in ('world', 'config'):
        shutil.copytree(parent / 'server' / folder, out / 'server' / folder)
    if inventory(out) != original['files']:
        raise ValueError('Copied snapshot differs from its parent')
    identity = dict(build=build, sourceCommit=source, jarSha256=jar_sha,
                    parentIdentity=original['identity'],
                    parentManifestSha256=digest(parent / 'replay-manifest.json'),
                    freshWorld=False, releaseVerdict='HOLD',
                    scope='cross-candidate-saved-world-diagnostic')
    (out / 'identity.json').write_text(json.dumps(identity, indent=2), encoding='utf-8')
    return jar, identity


def replay(parent, out, candidate, build, source, seconds, cache):
    if not 1 <= seconds <= 1800:
        raise ValueError('Replay observation must be between 1 and 1800 seconds')
    out = Path(out)
    jar, identity = fork_snapshot(parent, out, candidate, build, source)
    run = out / 'server'
    prepare_server(run, Path(cache))
    (run / 'mods').mkdir()
    shutil.copy2(jar, run / 'mods' / jar.name)
    cached_download('https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.156.0+26.2/fabric-api-0.156.0+26.2.jar',
                    run / 'mods/fabric-api.jar', Path(cache) / 'downloads')
    (run / 'eula.txt').write_text('eula=true\n')
    (run / 'server.properties').write_text(
        'level-name=world\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=25567\n'
        'view-distance=2\nsimulation-distance=2\npause-when-empty-seconds=-1\nmax-tick-time=120000\n')
    process = subprocess.Popen(['java', '-Xms1G', '-Xmx14G',
        '-XX:StartFlightRecording=filename=replay.jfr,settings=profile,dumponexit=true',
        '-jar', 'fabric-server-launch.jar', 'nogui'], cwd=run,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding='utf-8', errors='replace', bufsize=1)
    ready = threading.Event()
    observations = dict(completionObserved=False, durableResumeObserved=False, serverErrors=0)

    def collect():
        with (out / 'replay.log').open('w', encoding='utf-8') as log:
            for line in process.stdout:
                log.write(line)
                if 'Done (' in line:
                    ready.set()
                if 'PREGEN-ACCEPTANCE-DONE' in line:
                    observations['completionObserved'] = True
                if ' job after restart:' in line and 'Resumed ' in line:
                    observations['durableResumeObserved'] = True
                if '[Server thread/ERROR]' in line:
                    observations['serverErrors'] += 1

    def command(value):
        process.stdin.write(value + '\n')
        process.stdin.flush()

    thread = threading.Thread(target=collect, daemon=True)
    thread.start()
    started = time.monotonic()
    try:
        deadline = started + 300
        while not ready.wait(1):
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Replay server failed to become ready')
        # Preserve saved profile/config and durable operation; never start a replacement job.
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Replay server exited during observation')
            time.sleep(1)
        command('oceancanvas diagnostics')
        command('save-all flush')
        command('stop')
        process.wait(timeout=180)
        if process.returncode:
            raise RuntimeError('Replay server did not stop cleanly')
    finally:
        if process.poll() is None:
            try:
                command('stop')
                process.wait(timeout=120)
            except Exception:
                process.kill()
                process.wait(timeout=30)
        thread.join(timeout=10)
        result = dict(identity, **observations, serverExit=process.returncode,
                      observationWallSeconds=time.monotonic() - started)
        (out / 'replay-result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        if process.returncode == 0:
            write_snapshot(out, identity, 0)
        for path in run.rglob('*.zip'):
            shutil.copy2(path, out / path.name)
        if (run / 'replay.jfr').is_file():
            shutil.copy2(run / 'replay.jfr', out / 'replay.jfr')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('parent', 'out', 'candidate', 'build', 'source'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--cache', default='.runtime-cache')
    replay(**vars(parser.parse_args()))
