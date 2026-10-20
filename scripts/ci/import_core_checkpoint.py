"""Stopped, same-runtime import. No Minecraft process is launched by this module."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
from checkpoint_bundle import verify_manifest


def import_checkpoint(parent, workspace, expected_identity, expected_started, java_version):
    parent, workspace = Path(parent), Path(workspace)
    if os.name == 'nt':
        # Real checkpoint class names can exceed MAX_PATH once staging prefixes
        # are added. Use Windows extended absolute paths without shortening names.
        def extended(path):
            name = str(path.resolve())
            return Path(name if name.startswith('\\\\?\\') else '\\\\?\\' + name)
        parent, workspace = extended(parent), extended(workspace)
    manifest = json.loads((parent / 'checkpoint-manifest.json').read_text())
    for name in manifest.get('files', {}):
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name:
            raise ValueError('unsafe manifest path')
    if any(p.is_symlink() for p in parent.rglob('*')):
        raise ValueError('checkpoint symlinks are not recovery authority')
    verify_manifest(parent, expected_identity)
    timing = json.loads((parent / 'checkpoint-timing.json').read_text())
    status = json.loads((parent / 'checkpoint-status.json').read_text())
    context = json.loads((parent / 'runtime-context/context.json').read_text())
    if timing != {'identity': expected_identity, 'startedUtc': expected_started}:
        raise ValueError('original clock or identity differs')
    if status.get('phase') != 'FLATTEN' or status.get('completionObserved') is not False:
        raise ValueError('only pending flatten checkpoints are supported')
    if status.get('total') != 4096 or not 0 <= status.get('current', -1) < 4096:
        raise ValueError('invalid flatten progress')
    if context.get('sourceCommit') != expected_identity['sourceCommit'] or context.get('javaVersion') != java_version:
        raise ValueError('source or exact Java runtime differs; explicit upgrade required')
    if context.get('runtimeMode') != 'gradle-development-not-installed-jar':
        raise ValueError('unsupported runtime mode')
    for name, digest in context['files'].items():
        if manifest['files'].get('runtime-context/' + name) != digest:
            raise ValueError('runtime context is not fully bound by parent manifest')
    for prefix in ('build/classes/java/main/', 'build/resources/main/'):
        if not any(n.startswith(prefix) for n in context['files']):
            raise ValueError('missing actual development runtime payload')
    project = workspace / 'runtime-src'
    for name in ('build.gradle', 'gradle.properties'):
        if hashlib.sha256((project / name).read_bytes()).hexdigest() != context['files'][name]:
            raise ValueError('checkout build configuration differs')
    run = project / 'run'
    output = workspace / 'hosted-1k-checkpointed-live'
    stage = workspace / 'core-checkpoint-import-stage'
    if run.exists() or output.exists() or stage.exists():
        raise ValueError('destination is not empty; preserve existing runtime and evidence')
    operation = dict(line.split('=', 1) for line in
                     (parent / 'server/world/oceancanvas-core/full-canvas/operation.properties').read_text().splitlines()
                     if '=' in line and not line.startswith('#'))
    if operation.get('status') != 'RUNNING':
        raise ValueError('routine import refuses paused/failed operation; known-cause recovery review required')
    if operation.get('canvasSize') != '1024' or int(operation.get('nextIndex', '-1')) != status['current']:
        raise ValueError('durable geometry/progress differs from checkpoint status')
    # All parent checks finish before destination mutation. Interrupted staging remains visible.
    stage.mkdir()
    shutil.copytree(parent / 'server', stage / 'run')
    (stage / 'run/oceancanvas-1k-started.json').write_text(json.dumps(timing, indent=2) + '\n')
    (stage / 'run/eula.txt').write_text('eula=true\n')
    binary = stage / 'output/runtime-context-evidence'
    for directory in ('candidate', 'runtime-context'):
        shutil.copytree(parent / directory, binary / directory)
    for name, digest in context['files'].items():
        if not name.startswith('build/'):
            continue
        target = project / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(parent / 'runtime-context' / name, target)
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise ValueError('imported runtime payload differs')
    jar = expected_identity['candidateJarName']
    (project / 'build/libs').mkdir(parents=True, exist_ok=True)
    shutil.copy2(parent / 'candidate' / jar, project / 'build/libs' / jar)
    # Manifest verified source data is copied, never moved out of the immutable parent.
    for name in manifest['files']:
        if name.startswith('server/'):
            copied = stage / 'run' / name.removeprefix('server/')
            if hashlib.sha256(copied.read_bytes()).hexdigest() != manifest['files'][name]:
                raise ValueError('imported world/config inventory differs')
    (stage / 'output/import-lineage.json').write_text(json.dumps({
        'parentIdentity': expected_identity, 'originalStartedUtc': expected_started,
        'parentManifestSha256': hashlib.sha256((parent / 'checkpoint-manifest.json').read_bytes()).hexdigest(),
        'binaryUpgrade': False, 'scope': 'pending core development continuation; not installed-JAR acceptance'
    }, indent=2) + '\n')
    (stage / 'run').rename(run)
    (stage / 'output').rename(output)
    stage.rmdir()
    return status['current']


if __name__ == '__main__':
    import argparse
    import subprocess
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', required=True)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--identity-file', required=True, help='trusted pinned expected identity')
    parser.add_argument('--started', required=True, help='trusted original UTC clock')
    args = parser.parse_args()
    current = import_checkpoint(args.parent, args.workspace,
        json.loads(Path(args.identity_file).read_text()), args.started,
        subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True).strip())
    print('Imported verified pending flatten checkpoint at ' + str(current) + '/4096; no server launched')
