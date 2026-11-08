from pathlib import Path,PurePosixPath
import hashlib,json,shutil,subprocess,os
from checkpoint_bundle import verify_manifest,write_manifest
from candidate_verifier import verify_candidate
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius2k-parent'))
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-2k-evidence'))
expected=json.loads(Path('scripts/ci/amethyst163-scope-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(expected['files'])
for n,h in expected['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-2k-evidence'
identity=json.loads((source/'identity.json').read_text())
assert identity['sourceCommit']=='21606293e8fa40ab87eb595c4923fb5bf9c522a7'
assert identity['jarSha256']=='ba00dfa14affc30fd0146a3b5b49eebab318b7d4f86cddbb7b369ed8c7ca5fd6'
assert identity['targetBlocks']==4000 and identity['targetChunks']==62500
status=json.loads((source/'checkpoint-status.json').read_text())
assert status['serverExit']==0 and not status['segmentPending'] and not status['completionObserved']
checkpoint=json.loads((source/'checkpoint.json').read_text())
verify_manifest(source,checkpoint)
verify_candidate(parent/'production-candidate','v253.125.163',identity['sourceCommit'])
candidate=Path(os.environ.get('OC_PREPARE_CANDIDATE','production-candidate'))
jar,digest=verify_candidate(candidate,'v253.125.163',identity['sourceCommit'])
assert digest==identity['jarSha256']
assert len(list((source/'server/mods').glob('*probe*.jar')))==1
assert not out.exists()
shutil.copytree(source,out)
for n,h in expected['files'].items():
 if n.startswith('physical-radius-2k-evidence/'):
  assert hashlib.file_digest((out/n.removeprefix('physical-radius-2k-evidence/')).open('rb'),'sha256').hexdigest()==h,n
verify_manifest(out,checkpoint)
history=out/'diagnostic-parent-evidence'/'42489691526';history.mkdir(parents=True)
for n in ['identity.json','checkpoint.json','checkpoint-status.json','checkpoint-timing.json','checkpoint-manifest.json']:
 shutil.copy2(out/n,history/n)
for n in ['console.log','first-failure.json','finalizer-stages.jsonl','diagnostic-status.json','cooperative-invalidations.jsonl','proof-lifecycle.jsonl']:
 if (out/n).exists():shutil.move(str(out/n),history/n)
oldProbe=out/'server/mods/stall163-lifecycle-probe.jar'
assert hashlib.file_digest(oldProbe.open('rb'),'sha256').hexdigest()=='05a4913c34e57474fe40efdcdc817fd5020593436ba1289c1ae7579b18dfad09'
shutil.move(str(oldProbe),history/oldProbe.name)
addon=Path('scripts/ci/frozen/amethyst163-scope-probe.jar')
assert hashlib.file_digest(addon.open('rb'),'sha256').hexdigest()=='678958325f7a7d57cb01dfc6f26aa3112c567664d22677fd6c17d9144ef6be9b'
shutil.copy2(addon,out/'server/mods'/addon.name)
write_manifest(out,checkpoint)
(out/'diagnostic-status.json').write_text(json.dumps({'diagnosticOnly':True,'scaleAccepted':False,'fieldIntegrityAccepted':False,'parentRunId':expected['runId']}))
(out/'parent-binding.json').write_text(json.dumps({k:v for k,v in expected.items() if k!='files'},indent=2))
if os.environ.get('OC_PREPARE_ONLY')=='true':
 print(json.dumps({'prepared':True,'worldBytesUnchanged':True,'sourceCommit':identity['sourceCommit'],'jarSha256':digest,'parentRunId':expected['runId'],'scope':'no Minecraft'}))
 raise SystemExit(0)
assert os.environ['OC_RESUME']=='true' and os.environ['OC_REQUIRE_CHECKPOINT_MANIFEST']=='true' and os.environ['OC_DIAGNOSTIC_ONLY']=='true'
subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
