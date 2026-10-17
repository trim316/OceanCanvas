"""Controlled companion-probe tests on a copied world and immutable engine JAR."""
import hashlib, json, os, pathlib, re, shutil, subprocess, threading, time
from datetime import datetime, timezone
from candidate_verifier import verify_candidate

root=pathlib.Path.cwd()
incoming=root/'retained'
identity_path=next(incoming.rglob('identity.json'))
identity=json.loads(identity_path.read_text())
assert identity['sourceCommit']==os.environ['EXPECTED_SOURCE_COMMIT'], identity
assert identity['javaMaxHeapGiB']==14, identity
candidate_manifest=next((root/'candidate-download').rglob('candidate.properties'))
candidate_jar,candidate_sha=verify_candidate(candidate_manifest.parent,identity['build'],identity['sourceCommit'])
assert candidate_sha==identity['jarSha256'], 'retained world differs from published candidate'
out=root/'vegetation-probe-evidence'
out.mkdir(exist_ok=False)
shutil.copytree(candidate_manifest.parent,out/'production-candidate')
run=out/'server'
source=identity_path.parent/'server'
def sha(p):
    with p.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()
inventory={p.relative_to(source/'world').as_posix():sha(p) for p in (source/'world').rglob('*') if p.is_file()}
assert inventory and 'level.dat' in inventory
shutil.copytree(source,run)
assert inventory=={p.relative_to(run/'world').as_posix():sha(p) for p in (run/'world').rglob('*') if p.is_file()}
engine=list((run/'mods').glob('oceancanvas-*.jar'))
assert len(engine)==1 and sha(engine[0])==identity['jarSha256']
probe=root/'focused-vegetation-probe.jar'
assert probe.is_file()
shutil.copy2(probe,run/'mods'/probe.name)
provenance=dict(engine=identity, probeJarSha256=sha(probe), javaMaxHeapGiB=14,
    probeSourceCommit=os.environ['GITHUB_SHA'], startedUtc=datetime.now(timezone.utc).isoformat(),
    scope='controlled-retained-world-copy-vegetation-probe', randomTickSpeed=0,
    originalWorldInventorySha256=hashlib.sha256(json.dumps(inventory,sort_keys=True).encode()).hexdigest())
(out/'identity.json').write_text(json.dumps(provenance,indent=2)+'\n')
(out/'original-world-inventory.json').write_text(json.dumps(inventory,sort_keys=True,indent=2)+'\n')
assert not (run/'focused-vegetation-probe-snapshot.tsv').exists()
def launch(stage,marker):
    assert sha(engine[0])==identity['jarSha256']
    assert sha(run/'mods'/probe.name)==provenance['probeJarSha256']
    lines=[]
    proc=subprocess.Popen(['java','-Xms1G','-Xmx14G','-jar','fabric-server-launch.jar','nogui'],
        cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
    def collect():
        with (out/f'{stage}.log').open('w',encoding='utf-8',buffering=65536) as log:
            for line in proc.stdout:
                lines.append(line); log.write(line)
                if 'FOCUSED_VEGETATION_' in line or '/ERROR' in line: print(line,end='',flush=True)
    thread=threading.Thread(target=collect,daemon=True); thread.start()
    def command(text): proc.stdin.write(text+'\n'); proc.stdin.flush()
    def wait(pattern,seconds):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            if any('FOCUSED_VEGETATION_PROBE_FAILED' in line for line in lines):
                raise RuntimeError('actual candidate failed focused vegetation probe')
            if any(re.search(pattern,line) for line in lines): return
            if proc.poll() is not None: raise RuntimeError(f'server exited early: {proc.returncode}')
            time.sleep(.1)
        raise TimeoutError(pattern)
    try:
        wait(r'Done \(',300)
        (run/'run-focused-vegetation-probe.flag').write_text('controlled exact-candidate fixture\n')
        wait(marker,900)
        command('save-all flush'); command('stop')
        proc.wait(timeout=180); thread.join(timeout=15)
        assert proc.returncode==0
        assert sha(engine[0])==identity['jarSha256']
    finally:
        if proc.poll() is None:
            try:
                command('save-all flush'); command('stop'); proc.wait(timeout=180)
            except Exception:
                proc.kill(); proc.wait(timeout=30)
        thread.join(timeout=15)
        (out/f'{stage}-status.json').write_text(json.dumps(dict(serverExit=proc.returncode,marker=marker)))
launch('fixture',r'FOCUSED_VEGETATION_PROBE_SAVE_READY')
assert (run/'focused-vegetation-probe-snapshot.tsv').is_file()
launch('restart',r'FOCUSED_VEGETATION_PERSISTENCE_PASS')
(out/'result.json').write_text(json.dumps(dict(passed=True,**provenance),indent=2)+'\n')
print('EXACT_CANDIDATE_CONTROLLED_VEGETATION_PROBE_PASS',flush=True)

