from pathlib import Path, PurePosixPath
import hashlib,json,shutil,os,subprocess
from checkpoint_bundle import verify_manifest,write_manifest
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius2k-parent'))
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-2k-evidence'))
expected=json.loads(Path('scripts/ci/native161-central-s3-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(expected['files'])
for n,h in expected['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-2k-evidence'
identity=json.loads((source/'checkpoint.json').read_text())
assert identity['sourceCommit']=='9b5abeadedbfa44b621b437a4949b0fbd45ee099' and identity['targetChunks']==62500
assert identity['jarSha256']=='6e6d3f7d1ff56717499c0743713a6c23189c539ca7ff504653267615fe300447'
verify_manifest(source,identity)
assert not out.exists()
shutil.copytree(source,out)
verify_manifest(out,identity)
history=out/'diagnostic-parent-evidence'/'42365715778';history.mkdir(parents=True)
for name in ['console.log','first-failure.json','checkpoint-status.json','checkpoint-manifest.json','native-read-events.jsonl','diagnostic-status.json']:
 if (out/name).exists():shutil.move(str(out/name),history/name)
addon=Path('scripts/ci/frozen/native161-central-read-probe.jar')
assert hashlib.file_digest(addon.open('rb'),'sha256').hexdigest()=='406b15ac3b23b4834d7e5552e4b21fccfe19226888f47a53cc4ad2fabdd43d96'
installed=out/'server/mods'/addon.name
assert hashlib.file_digest(installed.open('rb'),'sha256').hexdigest()==hashlib.file_digest(addon.open('rb'),'sha256').hexdigest()
assert list((out/'server/mods').glob('*probe*.jar'))==[installed]
shutil.copy2(history/'checkpoint-status.json',out/'checkpoint-status.json')
for name,h in expected['files'].items():
 if name.startswith('physical-radius-2k-evidence/server/world/'):
  assert hashlib.file_digest((out/name.removeprefix('physical-radius-2k-evidence/')).open('rb'),'sha256').hexdigest()==h,name
write_manifest(out,identity)
(out/'diagnostic-status.json').write_text(json.dumps({'diagnosticOnly':True,'fieldIntegrityAccepted':False,'scaleAccepted':False,'parentRunId':expected['runId'],'parentWholeSha256':expected['wholeSha256']}))
if os.environ.get('OC_PREPARE_ONLY')!='true':
 assert os.environ['OC_RESUME']=='true' and os.environ['OC_DIAGNOSTIC_ONLY']=='true'
 subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
