#!/usr/bin/env python3
"""Publish only the explicitly limited, artifact-bound 500-block validation."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def validate(candidate, evidence, build, source):
    props = {}
    for line in (candidate / 'candidate.properties').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in props:
            raise ValueError('Duplicate candidate identity')
        props[key] = value
    if props.get('sourceCommit') != source or props.get('runtimeBuild') != build:
        raise ValueError('Candidate identity mismatch')
    jar = candidate / f'oceancanvas-26.2-{build}.jar'
    if list(candidate.glob('*.jar')) != [jar]:
        raise ValueError('Ambiguous candidate JAR')
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest != (candidate / 'JAR-SHA256.txt').read_text().split()[0]:
        raise ValueError('Candidate checksum mismatch')
    with zipfile.ZipFile(jar) as archive:
        mod = json.loads(archive.read('fabric.mod.json'))
        if mod.get('id') != 'oceancanvas' or mod.get('version') != f'26.2-{build}':
            raise ValueError('Packaged identity mismatch')
    identity = json.loads((evidence / 'identity.json').read_text())
    required = dict(jarSha256=digest, sourceCommit=source, build=build,
                    freshWorld=True, targetBlocks=500, targetChunks=1024)
    if any(identity.get(key) != value for key, value in required.items()):
        raise ValueError('Runtime evidence is not bound to this 500-block candidate')
    if json.loads((evidence / 'acceptance.json').read_text()).get('passed') is not True:
        raise ValueError('Runtime acceptance did not pass')
    restart = json.loads((evidence / 'restart.json').read_text())
    if (restart.get('passed') is not True
            or restart.get('build') != build or restart.get('sourceCommit') != source
            or restart.get('jarSha256') != digest):
        raise ValueError('Save/restart smoke test did not pass')
    return jar, digest


def main():
    build, source = os.environ['EXPECTED_BUILD'], os.environ['EXPECTED_SOURCE_COMMIT']
    candidate, evidence = Path('production-candidate'), Path('production-runtime-evidence')
    jar, digest = validate(candidate, evidence, build, source)
    subprocess.run(['python3', 'scripts/ci/production-acceptance.py',
                    str(evidence / 'console.log'), '--expected-build', build,
                    '--expected-chunks', '1024', '--expected-size-blocks', '500',
                    '--expected-center-x', '0', '--expected-center-z', '0',
                    '--require-start', '--require-completion', '--scope-latest-run',
                    '--require-structured-evidence'], check=True)
    tag = f'production-500-{build}'
    notes = Path('limited-release-notes.md')
    notes.write_text(f'''OceanCanvas production build {build}

Validated scope: a fresh 500 Ã— 500 block canvas (1,024 intersecting chunks).
The exact JAR passed unit tests, structured terrain/lighting/integrity acceptance,
save/flush, and a saved-world restart smoke test.

Larger sizes, including 5k, 10k and 20k, remain experimental and uncertified.
This release does not claim the 20k overnight performance target.

Source: {source}
JAR SHA-256: {digest}
''', encoding='utf-8')
    subprocess.run(['gh', 'release', 'create', tag, '--target', source,
                    '--title', f'OceanCanvas {build} â€” 500-block validated release',
                    '--notes-file', str(notes), str(jar),
                    str(candidate / 'JAR-SHA256.txt'),
                    str(evidence / 'identity.json'), str(evidence / 'acceptance.json'),
                    str(evidence / 'restart.json')], check=True)


if __name__ == '__main__':
    main()
