from pathlib import Path, PurePosixPath
import hashlib,json,shutil,os,subprocess
from checkpoint_bundle import verify_manifest,write_manifest
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius2k-parent'))
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-2k-evidence'))
expected=json.loads(Path('scripts/ci/scale159-radius2k-diagnostic-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(expected['files'])
for n,h in expected['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
source=parent/'physical-radius-2k-evidence'
identity=json.loads((source/'checkpoint.json').read_text())
assert identity['sourceCommit']=='8519872c5f34c5256a25a7b6d7685949beccbc59' and identity['targetChunks']==62500
assert identity['jarSha256']=='fb6d36d97c25d9ab9142c7651900bc7d7b71a012713755315d013a73cf956618'
verify_manifest(source,identity)
assert not out.exists()
shutil.copytree(source,out)
verify_manifest(out,identity)
history=out/'diagnostic-parent-evidence'/'42335959450';history.mkdir(parents=True)
for name in ['console.log','first-failure.json','checkpoint-status.json','checkpoint-manifest.json']:
 if (out/name).exists():shutil.move(str(out/name),history/name)
addon=Path('scripts/ci/frozen/scale159-stage-probe.jar')
assert hashlib.file_digest(addon.open('rb'),'sha256').hexdigest()=='a8d8ef3765207e16f6acec27c6aa12843a309fa54ef6819f50db3d582c07702b'
assert not list((out/'server/mods').glob('*probe*.jar'))
shutil.copy2(addon,out/'server/mods'/addon.name)
shutil.copy2(history/'checkpoint-status.json',out/'checkpoint-status.json')
write_manifest(out,identity)
(out/'diagnostic-status.json').write_text(json.dumps({'diagnosticOnly':True,'fieldIntegrityAccepted':False,'scaleAccepted':False,'parentRunId':expected['runId'],'parentWholeSha256':expected['wholeSha256']}))
if os.environ.get('OC_PREPARE_ONLY')!='true':
 assert os.environ['OC_RESUME']=='true' and os.environ['OC_DIAGNOSTIC_ONLY']=='true'
 subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
