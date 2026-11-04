"""Specific deliberate diagnostic reconstruction on COPY of latest failed154 snapshot."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
PARENT=Path(os.environ.get('OC_ABSOLUTE_PARENT','verified-recovery155-stage-parent'));OUT=Path(os.environ.get('OC_ABSOLUTE_OUT','recovery155-stage-evidence')).resolve();SERVER=OUT/'server'
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
def main():
 b=json.loads(Path('scripts/ci/recovery155-stage-parent.json').read_text());assert b['runId']==41866933904 and b['nativeFieldDiagnostic']['fieldValuesCompared']==3145728
 assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(b['files'])
 for n,h in b['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(PARENT/n)==h,n
 old=PARENT/'absolute155-recovery-evidence';assert not OUT.exists();OUT.mkdir();shutil.copytree(old/'server',SERVER)
 manifest=json.loads((old/'world-manifest.json').read_text())
 for n,h in manifest.items():assert sha(OUT/n)==h,n
 assert len(nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat')['data']['job'])==1
 jar,digest=verify_candidate(Path('production-candidate'),'v253.125.155',os.environ['EXPECTED_SOURCE_COMMIT'])
 assert digest=='8f98021bde95cb0bf650dc74fb6ab67ec938205bc5ede15cfd2ffac2709b39d9'
 retained=OUT/'parent-candidate-evidence';retained.mkdir()
 for p in list((SERVER/'mods').glob('oceancanvas-*.jar'))+list((SERVER/'mods').glob('*fixture*.jar')):shutil.move(str(p),retained/p.name)
 assert not list((SERVER/'mods').glob('*fixture*.jar'));shutil.copyfile(jar,SERVER/'mods'/jar.name)
 addon=Path('scripts/ci/frozen/recovery155-stage-probe-v2-fixture.jar');assert sha(addon)=='ce2980e088f75eb5538797c6ae63aac108f41c6e669e50aedf2bc18c8a22b971';shutil.copyfile(addon,SERVER/'mods'/addon.name)
 (OUT/'candidate-transition.json').write_text(json.dumps({'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'diagnosticReconstruction':True,'scaleAccepted':False},indent=2))
 if os.environ.get('OC_POST_PROOF_PREPARE_ONLY')=='true':print(json.dumps({'prepared':True,'allInputFiles':len(b['files']),'jobPending':1,'sourceAwareJar':digest,'MinecraftLaunched':False}));return
 lines=[];log=(OUT/'stage-diagnostic.log').open('w');start=time.monotonic();failure=None;done=False
 process=subprocess.Popen(['java','-Xms1G','-Xmx14G','-XX:+UseZGC','-XX:SoftMaxHeapSize=10G','-Doceancanvas.fixture.finalizerStageProbe='+str(OUT/'finalizer-stages.jsonl'),'-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 def collect():
  for line in process.stdout:lines.append(line);log.write(line);log.flush()
 thread=threading.Thread(target=collect,daemon=True);thread.start()
 try:
  while process.poll() is None:
   text=''.join(lines);bad=first_failure(text)
   if bad:raise RuntimeError(bad)
   if __import__('re').search(r'PREGEN-ACCEPTANCE-DONE .*chunks=16(?:\D|$)',text):done=True;break
   if time.monotonic()-start>600:raise TimeoutError('Diagnostic ceiling; no deadline waiver')
   time.sleep(.05)
 except BaseException as e:failure=str(e)
 finally:
  if process.poll() is None:process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=180)
  thread.join(10);log.close()
  (OUT/'world-manifest.json').write_text(json.dumps({p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()},indent=2))
  (OUT/'fixture-status.json').write_text(json.dumps({'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'failure':failure,'serverExit':process.returncode,'jobDoneObserved':done,'diagnosticOnly':True,'scaleAccepted':False},indent=2))
 if failure or process.returncode!=0:raise RuntimeError('Diagnostic failed; preserve actual FIRST and state snapshots')
if __name__=='__main__':main()
