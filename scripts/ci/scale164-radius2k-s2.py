from pathlib import Path,PurePosixPath
import hashlib,json,shutil,subprocess,os
from checkpoint_bundle import verify_manifest
from candidate_verifier import verify_candidate
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius2k-parent'))
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-2k-evidence'))
expected=json.loads(Path('scripts/ci/scale164-radius2k-s2-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(expected['files'])
for n,h in expected['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-2k-evidence'
identity=json.loads((source/'identity.json').read_text())
assert identity['sourceCommit']=='7893c1ba8b5292b3a5aec8f7f87fe84f8b82fff7'
assert identity['jarSha256']=='bab93fbeb3ab701d6f543accd778ecd9c2e92789e491efa37f1bf3412bac258e'
assert identity['targetBlocks']==4000 and identity['targetChunks']==62500
status=json.loads((source/'checkpoint-status.json').read_text())
assert status['serverExit']==0 and status['segmentPending'] and not status['completionObserved']
checkpoint=json.loads((source/'checkpoint.json').read_text())
verify_manifest(source,checkpoint)
verify_candidate(parent/'production-candidate','v253.125.164',identity['sourceCommit'])
candidate=Path(os.environ.get('OC_PREPARE_CANDIDATE','production-candidate'))
jar,digest=verify_candidate(candidate,'v253.125.164',identity['sourceCommit'])
assert digest==identity['jarSha256']
assert not list((source/'server/mods').glob('*probe*.jar'))
assert not out.exists()
shutil.copytree(source,out)
for n,h in expected['files'].items():
 if n.startswith('physical-radius-2k-evidence/'):
  assert hashlib.file_digest((out/n.removeprefix('physical-radius-2k-evidence/')).open('rb'),'sha256').hexdigest()==h,n
verify_manifest(out,checkpoint)
history=out/'continuation-parent-evidence'/'42497159437';history.mkdir(parents=True)
for n in ['identity.json','checkpoint.json','checkpoint-status.json','checkpoint-timing.json','checkpoint-manifest.json']:
 shutil.copy2(out/n,history/n)
(out/'parent-binding.json').write_text(json.dumps({k:v for k,v in expected.items() if k!='files'},indent=2))
if os.environ.get('OC_PREPARE_ONLY')=='true':
 print(json.dumps({'prepared':True,'worldBytesUnchanged':True,'sourceCommit':identity['sourceCommit'],'jarSha256':digest,'parentRunId':expected['runId'],'scope':'no Minecraft'}))
 raise SystemExit(0)
assert os.environ['OC_RESUME']=='true' and os.environ['OC_REQUIRE_CHECKPOINT_MANIFEST']=='true'
subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
