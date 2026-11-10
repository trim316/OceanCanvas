from pathlib import Path,PurePosixPath
import hashlib,json,shutil,subprocess,os
from checkpoint_bundle import verify_manifest,write_manifest
from candidate_verifier import verify_candidate
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius2k-parent'))
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-2k-evidence'))
expected=json.loads(Path('scripts/ci/stall165-lifecycle-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(expected['files'])
for n,h in expected['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-2k-evidence'
identity=json.loads((source/'identity.json').read_text())
assert identity['sourceCommit']=='637e03a072c5ff9a3288b80c3e147b5eb5bba233'
assert identity['jarSha256']=='49c29c64e7441c901921f6d71e6f2f0bf93e33ba3d9d076067fdd9b1ff0c2ac7'
assert identity['targetBlocks']==4000 and identity['targetChunks']==62500
status=json.loads((source/'checkpoint-status.json').read_text())
assert status['serverExit']==0 and not status['segmentPending'] and status['savedWorldPresent'] and not status['completionObserved']
checkpoint=json.loads((source/'checkpoint.json').read_text())
verify_manifest(source,checkpoint)
verify_candidate(parent/'production-candidate','v253.125.164',identity['sourceCommit'])
candidate=Path(os.environ.get('OC_PREPARE_CANDIDATE','production-candidate'))
jar,digest=verify_candidate(candidate,'v253.125.164',identity['sourceCommit'])
assert digest==identity['jarSha256']
assert len(list((source/'server/mods').glob('*probe*.jar')))==0
assert not out.exists()
shutil.copytree(source,out)
for n,h in expected['files'].items():
 if n.startswith('physical-radius-2k-evidence/'):
  assert hashlib.file_digest((out/n.removeprefix('physical-radius-2k-evidence/')).open('rb'),'sha256').hexdigest()==h,n
verify_manifest(out,checkpoint)
history=out/'diagnostic-parent-evidence'/'42678226272';history.mkdir(parents=True)
for n in ['identity.json','checkpoint.json','checkpoint-status.json','checkpoint-timing.json','checkpoint-manifest.json']:
 shutil.copy2(out/n,history/n)
for n in ['console.log','first-failure.json','finalizer-stages.jsonl','diagnostic-status.json','cooperative-invalidations.jsonl']:
 if (out/n).exists():shutil.move(str(out/n),history/n)
addon=Path('scripts/ci/frozen/stall165-lifecycle-probe.jar')
assert hashlib.file_digest(addon.open('rb'),'sha256').hexdigest()=='98a609d9eecd56b83f984ace336a47f449d8c5a514323b0ebf69779486a66608'
shutil.copy2(addon,out/'server/mods'/addon.name)
write_manifest(out,checkpoint)
(out/'diagnostic-status.json').write_text(json.dumps({'diagnosticOnly':True,'scaleAccepted':False,'fieldIntegrityAccepted':False,'parentRunId':expected['runId']}))
(out/'parent-binding.json').write_text(json.dumps({k:v for k,v in expected.items() if k!='files'},indent=2))
if os.environ.get('OC_PREPARE_ONLY')=='true':
 print(json.dumps({'prepared':True,'worldBytesUnchanged':True,'sourceCommit':identity['sourceCommit'],'jarSha256':digest,'parentRunId':expected['runId'],'scope':'no Minecraft'}))
 raise SystemExit(0)
assert os.environ['OC_RESUME']=='true' and os.environ['OC_REQUIRE_CHECKPOINT_MANIFEST']=='true' and os.environ['OC_DIAGNOSTIC_ONLY']=='true'
subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
