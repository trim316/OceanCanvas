"""Same-source/same-JAR failed checkpoint; lower GC soft target with unchanged hard heap and health guards."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess
from checkpoint_bundle import verify_manifest,write_manifest
from candidate_verifier import verify_candidate
prepare=os.environ.get('OC_PREPARE_ONLY')=='true'
parent=Path(os.environ.get('OC_PREPARE_PARENT','verified-radius-1k-parent')) if prepare else Path('verified-radius-1k-parent')
out=Path(os.environ.get('OC_PREPARE_OUT','physical-radius-1k-evidence')) if prepare else Path('physical-radius-1k-evidence')
binding=json.loads(Path('scripts/ci/main139-s4-failed-parent.json').read_text());assert binding['runId']==41165928350 and len(binding['files'])==604
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(binding['files'])
for n,h in binding['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(parent/n)==h,n
source=parent/'physical-radius-1k-evidence';identity=json.loads((source/'identity.json').read_text())
assert identity['sourceCommit']=='fb84e2d10722ccb30ea2f67f192678dc81c76bc4' and identity['jarSha256']=='e11c4944a852ccfeac84960cbb3716fe3b47b8a8ae035330e0e01f7472c0010f' and identity['targetBlocks']==2000
status=json.loads((source/'checkpoint-status.json').read_text());assert status['serverExit']==0 and not status['segmentPending'] and not status['completionObserved']
failure=json.loads((source/'first-failure.json').read_text());assert failure['line']==binding['firstIssue']
checkpoint=json.loads((source/'checkpoint.json').read_text());manifest=verify_manifest(source,checkpoint)
verify_candidate(parent/'production-candidate','v253.125.139',identity['sourceCommit'])
assert not out.exists();shutil.copytree(source,out)
if Path('production-candidate').exists():
 assert prepare;verify_candidate(Path('production-candidate'),'v253.125.139',identity['sourceCommit'])
else:shutil.copytree(parent/'production-candidate','production-candidate')
for n,h in binding['files'].items():
 if n.startswith('physical-radius-1k-evidence/'):assert sha(out/n.removeprefix('physical-radius-1k-evidence/'))==h,n
verify_manifest(out,checkpoint)
pre=out/'corrective-input-parent-evidence'/str(binding['runId']);pre.mkdir(parents=True)
for name in ['first-failure.json','console.log']:shutil.move(str(out/name),pre/name)
for name in ['checkpoint-status.json','checkpoint-manifest.json','parent-binding.json']:shutil.copyfile(out/name,pre/name)
bundle=Path('scripts/ci/main139-s4-failed-parent.json')
transition={'parentRun':binding['runId'],'parentArtifact':binding['artifactId'],'parentWholeSha256':binding['wholeSha256'],'sourceAndJarUnchanged':True,'worldBytesUnchangedBeforeRuntime':True,'previousFirstIssue':failure['line'],'priorConsoleSha256':binding['files']['physical-radius-1k-evidence/console.log'],'newExactOperatorCells':0,'runtimePolicyChange':{'oldSoftMaxGiB':14,'newSoftMaxGiB':10,'hardMaxGiB':14,'XmsGiB':1,'forceGc':False,'healthGuardsUnchanged':True},'newBundleSha256':sha(bundle),'startedUtcPreserved':json.loads((out/'checkpoint-timing.json').read_text())['startedUtc'],'scaleAccepted':False}
(out/'corrective-input-transition.json').write_text(json.dumps(transition,indent=2));(out/'parent-binding.json').write_text(json.dumps({k:v for k,v in binding.items() if k!='files'},indent=2))
for n,h in manifest['files'].items():
 if n.startswith('server/world/'):assert sha(out/n)==h,n
write_manifest(out,checkpoint);verify_manifest(out,checkpoint)
if prepare:print(json.dumps(transition));raise SystemExit(0)
assert os.environ['OC_RESUME']=='true' and os.environ['OC_REQUIRE_CHECKPOINT_MANIFEST']=='true' and os.environ['OC_OPERATOR_CELL_EVIDENCE']=='false'
subprocess.run(['python3','scripts/ci/production-scale.py'],check=True)
