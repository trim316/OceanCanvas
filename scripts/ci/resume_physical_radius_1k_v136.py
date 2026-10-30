"""Resume only the independently verified exact same 1000-radius checkpoint."""
from pathlib import Path, PurePosixPath
import hashlib,json,shutil,subprocess,os
from checkpoint_bundle import verify_manifest
from candidate_verifier import verify_candidate
parent=Path('verified-radius-1k-parent');out=Path('physical-radius-1k-evidence')
expected=json.loads(Path('scripts/ci/physical-radius-1k-parent-v136-r1.json').read_text())
actual={p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}
assert actual==set(expected['files']), 'Parent artifact inventory differs'
for n,h in expected['files'].items():
    assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
    assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-1k-evidence';identity=json.loads((source/'identity.json').read_text())
assert identity['jarSha256']=='faeb8ecdeb9160f7d7d9fb2b3b786aee91b26ab12f9827dc58c88b73d1fc65ee'
assert identity['sourceCommit']=='922e1cd8662b7eca0812955a752ac27d074f8723' and identity['targetBlocks']==2000 and identity['targetChunks']==15876
assert json.loads((source/'checkpoint-status.json').read_text())=={'resumed':True,'serverExit':0,'completionObserved':False,'savedWorldPresent':True,'segmentPending':True,'scope':'checkpoint-only-not-certification'}
checkpoint=json.loads((source/'checkpoint.json').read_text());verify_manifest(source,checkpoint)
assert not out.exists() and not Path('production-candidate').exists()
shutil.copytree(source,out);shutil.copytree(parent/'production-candidate','production-candidate')
for n,h in expected['files'].items():
    if n.startswith('physical-radius-1k-evidence/'):
        target=out/n.removeprefix('physical-radius-1k-evidence/')
        assert hashlib.file_digest(target.open('rb'),'sha256').hexdigest()==h,n
verify_manifest(out,checkpoint)
verify_candidate(Path('production-candidate'),'v253.125.136',identity['sourceCommit'])
# Keep exact predecessor metadata independently; console and UTC campaign clock are continued in place.
pre=out/'continuation-parent-evidence'/str(expected['runId']);pre.mkdir(parents=True)
for n in ['identity.json','checkpoint-status.json','checkpoint-manifest.json','ladder-status.json','parent-binding.json']:
    shutil.copyfile(out/n,pre/n)
(out/'parent-binding.json').write_text(json.dumps({k:v for k,v in expected.items() if k!='files'},indent=2))
assert os.environ['OC_RESUME']=='true' and os.environ['OC_REQUIRE_CHECKPOINT_MANIFEST']=='true'
subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
