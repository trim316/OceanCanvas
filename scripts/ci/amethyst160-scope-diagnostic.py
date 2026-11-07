from pathlib import Path, PurePosixPath
import hashlib,json,shutil,os,subprocess
from checkpoint_bundle import verify_manifest,write_manifest
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius2k-parent'))
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-2k-evidence'))
expected=json.loads(Path('scripts/ci/amethyst160-scope-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(expected['files'])
for n,h in expected['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-2k-evidence'
identity=json.loads((source/'checkpoint.json').read_text())
assert identity['sourceCommit']=='d9fa1d4b5e67f76771231cb32647edc84b1d7989' and identity['targetChunks']==62500
assert identity['jarSha256']=='8ad76f44a13225a7e15c170591bf95e218d67425f28f0500020f802910e6364b'
verify_manifest(source,identity)
assert not out.exists()
shutil.copytree(source,out)
verify_manifest(out,identity)
history=out/'diagnostic-parent-evidence'/'42344568330';history.mkdir(parents=True)
for name in ['console.log','first-failure.json','checkpoint-status.json','checkpoint-manifest.json','diagnostic-status.json']:
 if (out/name).exists():shutil.move(str(out/name),history/name)
addon=Path('scripts/ci/frozen/amethyst160-scope-probe.jar')
assert hashlib.file_digest(addon.open('rb'),'sha256').hexdigest()=='f1203adfddd4a5c8996780775340796836f3022ff6e1a252f077757b1385c313'
assert {p.name for p in (out/'server/mods').glob('*probe*.jar')}=={'native160-read-probe-v2.jar'}
shutil.copy2(addon,out/'server/mods'/addon.name)
shutil.copy2(history/'checkpoint-status.json',out/'checkpoint-status.json')
for name,h in expected['files'].items():
 if name.startswith('physical-radius-2k-evidence/server/world/'):
  assert hashlib.file_digest((out/name.removeprefix('physical-radius-2k-evidence/')).open('rb'),'sha256').hexdigest()==h,name
write_manifest(out,identity)
(out/'diagnostic-status.json').write_text(json.dumps({'diagnosticOnly':True,'fieldIntegrityAccepted':False,'scaleAccepted':False,'parentRunId':expected['runId'],'parentWholeSha256':expected['wholeSha256']}))
if os.environ.get('OC_PREPARE_ONLY')!='true':
 assert os.environ['OC_RESUME']=='true' and os.environ['OC_DIAGNOSTIC_ONLY']=='true'
 subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
