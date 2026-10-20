"""Atomic, checksummed manifests for stopped disposable scale worlds."""
import hashlib
import json
from pathlib import Path


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_manifest(out, identity):
    out = Path(out)
    status = json.loads((out / 'checkpoint-status.json').read_text())
    if status['serverExit'] != 0 or not (out / 'server/world/level.dat').is_file():
        raise ValueError('Checkpoint requires a clean server stop and level.dat')
    paths = [out / name for name in ('checkpoint.json', 'checkpoint-timing.json', 'checkpoint-status.json')]
    paths += sorted(p for p in (out / 'server/world').rglob('*') if p.is_file())
    for name in ('candidate', 'runtime-context', 'server/config'):
        paths += sorted(p for p in (out / name).rglob('*') if p.is_file())
    if (out / 'server/server.properties').is_file(): paths.append(out / 'server/server.properties')
    payload = dict(schema=1, identity=identity,
                   files={p.relative_to(out).as_posix(): digest(p) for p in paths})
    temporary = out / 'checkpoint-manifest.json.partial'
    temporary.write_text(json.dumps(payload, sort_keys=True, indent=2), encoding='utf-8')
    temporary.replace(out / 'checkpoint-manifest.json')
    return payload


def verify_manifest(out, identity):
    out = Path(out)
    payload = json.loads((out / 'checkpoint-manifest.json').read_text())
    if payload.get('schema') != 1 or payload.get('identity') != identity:
        raise ValueError('Checkpoint manifest identity mismatch')
    files = payload.get('files', {})
    required = {'checkpoint.json', 'checkpoint-timing.json', 'checkpoint-status.json', 'server/world/level.dat'}
    if not required.issubset(files):
        raise ValueError('Checkpoint manifest is incomplete')
    actual_world = {p.relative_to(out).as_posix() for p in (out / 'server/world').rglob('*') if p.is_file()}
    if actual_world != {p for p in files if p.startswith('server/world/')}:
        raise ValueError('Checkpoint world inventory mismatch')
    for root in ('candidate', 'runtime-context', 'server/config'):
        actual = {p.relative_to(out).as_posix() for p in (out / root).rglob('*') if p.is_file()}
        if actual != {name for name in files if name.startswith(root + '/')}:
            raise ValueError('Checkpoint runtime inventory mismatch: ' + root)
    if identity.get('runtimeMode') == 'gradle-development-not-installed-jar':
        candidate = 'candidate/' + identity['candidateJarName']
        if files.get(candidate) != identity['candidateJarSha256'] or files.get('runtime-context/context.json') != identity['runtimeContextSha256']:
            raise ValueError('Checkpoint exact candidate/runtime binding mismatch')
        if not any(name.startswith('server/config/') for name in files) or 'server/server.properties' not in files:
            raise ValueError('Checkpoint runtime configuration missing')
    for name, expected in files.items():
        path = out / name
        if not path.is_file() or digest(path) != expected:
            raise ValueError('Checkpoint file mismatch: ' + name)
    status = json.loads((out / 'checkpoint-status.json').read_text())
    if status.get('serverExit') != 0:
        raise ValueError('Checkpoint did not stop cleanly')
    return payload


def archive_checkpoint(out, identity):
    import zipfile
    out = Path(out)
    payload = verify_manifest(out, identity)
    temporary = out / 'saved-checkpoint.zip.partial'
    with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
        for name in sorted(payload['files']):
            archive.write(out / name, name)
        archive.write(out / 'checkpoint-manifest.json', 'checkpoint-manifest.json')
    destination = out / 'saved-checkpoint.zip'
    temporary.replace(destination)
    return destination
