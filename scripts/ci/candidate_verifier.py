"""Strict immutable production artifact validation shared by scale diagnostics."""
import hashlib
import json
import re
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
    if props.get('runtimeBuild') != build:
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
