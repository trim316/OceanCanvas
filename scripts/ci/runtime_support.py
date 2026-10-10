"""Shared disposable-server setup and exact-candidate checkpoint binding."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def cached_download(url, destination, cache):
    cache = Path(cache)
    cache.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256(url.encode()).hexdigest()
    artifact, digest = cache / key, cache / (key + '.sha256')
    if not artifact.is_file() or not digest.is_file() or sha(artifact) != digest.read_text():
        temporary = cache / (key + '.partial')
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        temporary.replace(artifact)
        digest.write_text(sha(artifact))
    shutil.copy2(artifact, destination)


def prepare_server(run, cache):
    """Cache only immutable installer/libraries, never a world or candidate mod."""
    run, cache = Path(run), Path(cache)
    template = cache / 'fabric-26.2-0.19.3-1.1.2'
    manifest = cache / 'fabric-template.json'
    valid = False
    if manifest.is_file() and template.is_dir():
        hashes = json.loads(manifest.read_text())
        valid = bool(hashes) and all((template / p).is_file() and sha(template / p) == h
                                     for p, h in hashes.items())
    if not valid:
        if template.exists():
            shutil.rmtree(template)
        template.mkdir(parents=True)
        cached_download('https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar',
                        template / 'installer.jar', cache / 'downloads')
        subprocess.run(['java', '-jar', 'installer.jar', 'server', '-mcversion', '26.2',
                        '-loader', '0.19.3', '-downloadMinecraft'], cwd=template,
                       check=True, timeout=300)
        manifest.write_text(json.dumps({str(p.relative_to(template)): sha(p)
                                       for p in template.rglob('*') if p.is_file()}))
    shutil.copytree(template, run, dirs_exist_ok=True)


def bind_checkpoint(path, identity, resume):
    path = Path(path)
    if resume:
        if not path.is_file() or json.loads(path.read_text()) != identity:
            raise ValueError('Checkpoint candidate/settings mismatch; fresh run required')
    elif path.exists():
        raise ValueError('Refusing to overwrite an existing checkpoint')
    else:
        path.write_text(json.dumps(identity, sort_keys=True, indent=2))
