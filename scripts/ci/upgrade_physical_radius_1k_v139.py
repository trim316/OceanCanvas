"""Explicit candidate transition on an immutable, fully verified S5 world copy."""
from pathlib import Path,PurePosixPath
import hashlib,json,shutil,subprocess,os
from checkpoint_bundle import verify_manifest,write_manifest
from candidate_verifier import verify_candidate
prepare_only=os.environ.get('OC_PREPARE_ONLY')=='true'
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius-1k-parent')) if prepare_only else Path('verified-radius-1k-parent')
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-1k-evidence')) if prepare_only else Path('physical-radius-1k-evidence')
expected=json.loads(Path('scripts/ci/physical-radius-1k-parent-v138.json').read_text())
assert len(expected['files'])==716 and expected['runId']==41128750189
actual={p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}
assert actual==set(expected['files'])
for n,h in expected['files'].items():
    assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
    assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-1k-evidence';identity=json.loads((source/'identity.json').read_text())
assert identity['sourceCommit']=='635c32adf70f18b8b311900ab09c7fb2cc9dcb7e'
assert identity['jarSha256']=='2f4148ffebf55248302c5d7db349b1e97551d9591815846a7ce6ce2d0f3d3dd9'
assert identity['targetBlocks']==2000 and identity['targetChunks']==15876
status=json.loads((source/'checkpoint-status.json').read_text())
assert status['resumed'] and status['serverExit']==0 and not status['segmentPending'] and not status['completionObserved']
prior_failure=json.loads((source/'first-failure.json').read_text());assert prior_failure['line']==expected['firstIssue']
old=json.loads((source/'checkpoint.json').read_text());manifest=verify_manifest(source,old)
verify_candidate(parent/'production-candidate','v253.125.138',identity['sourceCommit'])
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.139','fb84e2d10722ccb30ea2f67f192678dc81c76bc4')
assert not out.exists();shutil.copytree(source,out)
for n,h in expected['files'].items():
    if n.startswith('physical-radius-1k-evidence/'):
        assert hashlib.file_digest((out/n.removeprefix('physical-radius-1k-evidence/')).open('rb'),'sha256').hexdigest()==h,n
verify_manifest(out,old)
pre=out/'continuation-parent-evidence'/str(expected['runId']);pre.mkdir(parents=True)
for n in ['identity.json','checkpoint.json','checkpoint-timing.json','checkpoint-status.json','checkpoint-manifest.json','ladder-status.json','parent-binding.json']:
    shutil.copyfile(out/n,pre/n)
shutil.copytree(parent/'production-candidate',pre/'production-candidate')
# Start a source-bound log epoch while retaining the original failure and every console byte.
shutil.move(str(out/'console.log'),pre/'console.log')
shutil.move(str(out/'first-failure.json'),pre/'first-failure.json')
installed=list((out/'server/mods').glob('oceancanvas-*.jar'));assert len(installed)==1
assert hashlib.file_digest(installed[0].open('rb'),'sha256').hexdigest()==identity['jarSha256']
shutil.move(str(installed[0]),pre/installed[0].name)
new=dict(old,jarSha256=digest,sourceCommit='fb84e2d10722ccb30ea2f67f192678dc81c76bc4',build='v253.125.139')
assert {k for k in old if old[k]!=new[k]}=={'jarSha256','sourceCommit','build'}
timing=json.loads((out/'checkpoint-timing.json').read_text());assert timing['identity']==old
started=timing['startedUtc'];timing['identity']=new
(out/'checkpoint.json').write_text(json.dumps(new,sort_keys=True,indent=2))
(out/'checkpoint-timing.json').write_text(json.dumps(timing,indent=2))
transition={'parentRunId':expected['runId'],'parentArtifactId':expected['artifactId'],'parentWholeSha256':expected['wholeSha256'],'oldIdentity':old,'newIdentity':new,'startedUtcPreserved':started,'worldBytesUnchangedBeforeRuntime':True,'scaleAccepted':False,'previousFirstIssue':prior_failure['line'],'priorConsoleSha256':expected['files']['physical-radius-1k-evidence/console.log'],'reason':'Native-verified139 scoped cell receipts; explicit20-cell operator attestation after immutable source-water lineage, no general repair lease; prior failure retained, never retroactively passed'}
(out/'candidate-transition.json').write_text(json.dumps(transition,indent=2))
(out/'parent-binding.json').write_text(json.dumps({k:v for k,v in expected.items() if k!='files'},indent=2))
# No NBT mutation or seal editing by this harness. Product format migration owns reproof.
for n,h in manifest['files'].items():
    if n.startswith('server/world/'):
        assert hashlib.file_digest((out/n).open('rb'),'sha256').hexdigest()==h,n
write_manifest(out,new);verify_manifest(out,new)
assert os.environ['OC_RESUME']=='true' and os.environ['OC_REQUIRE_CHECKPOINT_MANIFEST']=='true'
if os.environ.get('OC_PREPARE_ONLY')=='true':
    print(json.dumps(dict(transition,scope='prepare-only-no-native-runtime')));raise SystemExit(0)
os.environ['OC_CANDIDATE_TRANSITION']='true'
subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
