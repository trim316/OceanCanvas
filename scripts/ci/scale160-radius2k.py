from pathlib import Path,PurePosixPath
import json,hashlib,shutil,os,subprocess
from checkpoint_bundle import verify_manifest,write_manifest
from candidate_verifier import verify_candidate
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius2k-parent'))
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-2k-evidence'))
expected=json.loads(Path('scripts/ci/scale160-radius2k-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(expected['files'])
for n,h in expected['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-2k-evidence'
old=json.loads((source/'checkpoint.json').read_text())
assert old['sourceCommit']=='8519872c5f34c5256a25a7b6d7685949beccbc59' and old['targetChunks']==62500
assert old['jarSha256']=='fb6d36d97c25d9ab9142c7651900bc7d7b71a012713755315d013a73cf956618'
manifest=verify_manifest(source,old)
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.160','d9fa1d4b5e67f76771231cb32647edc84b1d7989')
assert digest=='8ad76f44a13225a7e15c170591bf95e218d67425f28f0500020f802910e6364b'
assert not out.exists();shutil.copytree(source,out);verify_manifest(out,old)
history=out/'candidate-transition-parent'/'42338302646';history.mkdir(parents=True)
for name in ['console.log','diagnostic-status.json','finalizer-stages.jsonl','checkpoint.json','checkpoint-timing.json','checkpoint-manifest.json','checkpoint-status.json','identity.json']:
 if (out/name).exists():shutil.copy2(out/name,history/name)
for name in ['console.log','diagnostic-status.json','finalizer-stages.jsonl']:
 if (out/name).exists():(out/name).unlink()
mods=out/'server/mods'
oldjars=list(mods.glob('oceancanvas-*.jar'));assert len(oldjars)==1
assert hashlib.file_digest(oldjars[0].open('rb'),'sha256').hexdigest()==old['jarSha256']
shutil.move(str(oldjars[0]),history/oldjars[0].name)
probe=mods/'scale159-stage-probe.jar'
assert hashlib.file_digest(probe.open('rb'),'sha256').hexdigest()=='a8d8ef3765207e16f6acec27c6aa12843a309fa54ef6819f50db3d582c07702b'
shutil.move(str(probe),history/probe.name)
shutil.copy2(jar,mods/jar.name)
new=dict(old,sourceCommit='d9fa1d4b5e67f76771231cb32647edc84b1d7989',jarSha256=digest,build='v253.125.160')
assert {k for k in old if old[k]!=new[k]}=={'sourceCommit','jarSha256','build'}
timing=json.loads((out/'checkpoint-timing.json').read_text());assert timing['identity']==old
started=timing['startedUtc'];timing['identity']=new
(out/'checkpoint.json').write_text(json.dumps(new,indent=2))
(out/'checkpoint-timing.json').write_text(json.dumps(timing,indent=2))
for n,h in manifest['files'].items():
 if n.startswith('server/world/'):assert hashlib.file_digest((out/n).open('rb'),'sha256').hexdigest()==h,n
transition={'parentRunId':expected['runId'],'parentWholeSha256':expected['wholeSha256'],'oldIdentity':old,'newIdentity':new,'startedUtcPreserved':started,'worldBytesUnchangedBeforeRuntime':True,'addonAbsent':not list(mods.glob('*probe*.jar')),'scaleAccepted':False}
assert transition['addonAbsent']
(out/'candidate-transition.json').write_text(json.dumps(transition,indent=2))
write_manifest(out,new);verify_manifest(out,new)
if os.environ.get('OC_PREPARE_ONLY')=='true':
 print(json.dumps(transition));raise SystemExit(0)
assert os.environ['OC_RESUME']=='true' and os.environ['OC_REQUIRE_CHECKPOINT_MANIFEST']=='true'
assert os.environ.get('OC_DIAGNOSTIC_ONLY')!='true' and os.environ.get('OC_FINALIZER_STAGE_PROBE')!='true'
os.environ['OC_CANDIDATE_TRANSITION']='true'
subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
