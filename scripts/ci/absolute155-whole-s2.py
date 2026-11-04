"""Specific deliberate diagnostic reconstruction on COPY of latest failed154 snapshot."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
PARENT=Path(os.environ.get('OC_ABSOLUTE_PARENT','verified-absolute155-whole-s2-parent'));OUT=Path(os.environ.get('OC_ABSOLUTE_OUT','absolute155-whole-s2-evidence')).resolve();SERVER=OUT/'server'
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
def main():
 b=json.loads(Path('scripts/ci/absolute155-whole-s2-parent.json').read_text());assert b['runId']==41853238642 and b['previousWholeFixtureFailure']=='Replay fixture ceiling'
 assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(b['files'])
 for n,h in b['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(PARENT/n)==h,n
 old=PARENT/'absolute155-whole-evidence';assert not OUT.exists();OUT.mkdir();shutil.copytree(old/'server',SERVER)
 manifest=json.loads((old/'world-manifest.json').read_text())
 for n,h in manifest.items():assert sha(OUT/n)==h,n
 assert len(nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat')['data']['job'])==1
 jar,digest=verify_candidate(Path('production-candidate'),'v253.125.155',os.environ['EXPECTED_SOURCE_COMMIT'])
 assert digest=='8f98021bde95cb0bf650dc74fb6ab67ec938205bc5ede15cfd2ffac2709b39d9'
 retained=OUT/'parent-candidate-evidence';retained.mkdir()
 for p in list((SERVER/'mods').glob('oceancanvas-*.jar'))+list((SERVER/'mods').glob('*fixture*.jar')):shutil.move(str(p),retained/p.name)
 assert not list((SERVER/'mods').glob('*fixture*.jar'));shutil.copyfile(jar,SERVER/'mods'/jar.name)
 addon=Path('scripts/ci/frozen/absolute155-whole-s2-fixture.jar');assert sha(addon)=='2d5ff996adf95a609d5300ac4bcb71b85e4807134a3bc66aa0668645b85e986e';shutil.copyfile(addon,SERVER/'mods'/addon.name)
 (OUT/'candidate-transition.json').write_text(json.dumps({'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'diagnosticReconstruction':True,'scaleAccepted':False},indent=2))
 if os.environ.get('OC_POST_PROOF_PREPARE_ONLY')=='true':print(json.dumps({'prepared':True,'allInputFiles':len(b['files']),'jobPending':1,'sourceAwareJar':digest,'MinecraftLaunched':False}));return
 snapshots=OUT/'native-replay-snapshots';lines=[];log=(OUT/'replay.log').open('w');start=time.monotonic();observed=None;failure=None;ready=False
 process=subprocess.Popen(['java','-Xms1G','-Xmx14G','-XX:+UseZGC','-XX:SoftMaxHeapSize=10G','-Doceancanvas.fixture.collisionReplay='+str(snapshots),'-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 def collect():
  for line in process.stdout:lines.append(line);log.write(line);log.flush()
 thread=threading.Thread(target=collect,daemon=True);thread.start()
 try:
  while process.poll() is None:
   text=''.join(lines);ready=ready or 'Done (' in text
   if 'Encountered an unexpected exception' in text:raise RuntimeError('Native server crash; expected handled operation failure was not achieved')
   if (snapshots/'result.json').exists() and observed is None:
    result=json.loads((snapshots/'result.json').read_text());assert result['jobStillRunning'] is False and result['playerZoneEnabled'] is False and result['wholeAuthorityConfigured'] and result['sentinelBedrock'] and result['pendingOriginalRetained'] and result['rescueTickets']==0,result
    observed=time.monotonic()
   if observed is not None and time.monotonic()-observed>=10:break
   if not ready and time.monotonic()-start>240:raise TimeoutError('Replay startup ceiling')
   if time.monotonic()-start>600:raise TimeoutError('Replay fixture ceiling')
   time.sleep(.05)
  if observed is None:raise RuntimeError('No actual typed protection refusal observed')
 except BaseException as e:failure=str(e)
 finally:
  if process.poll() is None:process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=180)
  thread.join(10);log.close()
  inventory={p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()};(OUT/'world-manifest.json').write_text(json.dumps(inventory,indent=2))
  status={'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'serverExit':process.returncode,'failure':failure,'actualTypedRefusalObserved':observed is not None,'diagnosticReconstruction':True,'firstIssue':first_failure((OUT/'replay.log').read_text()),'independentSnapshotVerificationPending':True,'protectionAccepted':False,'scaleAccepted':False}
  (OUT/'fixture-status.json').write_text(json.dumps(status,indent=2));print(json.dumps({k:v for k,v in status.items() if k!='firstIssue'}))
 if failure or process.returncode!=0:raise RuntimeError(failure or 'Nonzero native exit')
if __name__=='__main__':main()
