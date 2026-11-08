from pathlib import Path,PurePosixPath
import json,hashlib,shutil,os,subprocess
from checkpoint_bundle import verify_manifest,write_manifest
from candidate_verifier import verify_candidate
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius2k-parent'))
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-2k-evidence'))
expected=json.loads(Path('scripts/ci/scale162-radius2k-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(expected['files'])
for n,h in expected['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-2k-evidence'
old=json.loads((source/'checkpoint.json').read_text())
assert old['sourceCommit']=='9b5abeadedbfa44b621b437a4949b0fbd45ee099' and old['targetChunks']==62500
assert old['jarSha256']=='6e6d3f7d1ff56717499c0743713a6c23189c539ca7ff504653267615fe300447'
manifest=verify_manifest(source,old)
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.162','28cd1b92419b9a8580758e63509be493d542c11b')
assert digest=='82100ee84f50d3706eddaff0f5f257696944ca3057d598d7dedeea40706564a7'
assert not out.exists();shutil.copytree(source,out);verify_manifest(out,old)
history=out/'candidate-transition-parent'/'42388342137';history.mkdir(parents=True)
for name in ['console.log','first-failure.json','diagnostic-status.json','finalizer-stages.jsonl','checkpoint.json','checkpoint-timing.json','checkpoint-manifest.json','checkpoint-status.json','identity.json']:
 if (out/name).exists():shutil.copy2(out/name,history/name)
for name in ['console.log','first-failure.json','diagnostic-status.json','finalizer-stages.jsonl']:
 if (out/name).exists():(out/name).unlink()
mods=out/'server/mods'
oldjars=list(mods.glob('oceancanvas-*.jar'));assert len(oldjars)==1
assert hashlib.file_digest(oldjars[0].open('rb'),'sha256').hexdigest()==old['jarSha256']
shutil.move(str(oldjars[0]),history/oldjars[0].name)
for name,digestExpected in [('native161-central-read-probe.jar','406b15ac3b23b4834d7e5552e4b21fccfe19226888f47a53cc4ad2fabdd43d96'),('stall161-stage-probe-v2.jar','ce810685135d0e061002fc7a5830a28f102f667a51072ce68170fb58439157fc')]:
 probe=mods/name
 assert hashlib.file_digest(probe.open('rb'),'sha256').hexdigest()==digestExpected
 shutil.move(str(probe),history/probe.name)
shutil.copy2(jar,mods/jar.name)
new=dict(old,sourceCommit='28cd1b92419b9a8580758e63509be493d542c11b',jarSha256=digest,build='v253.125.162')
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
