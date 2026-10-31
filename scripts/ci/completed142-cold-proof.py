"""New cold-save integrity case on completed142 world; never reruns generation or timing."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time,xml.etree.ElementTree as E
import nbtlib
from candidate_verifier import verify_candidate
from checkpoint_bundle import verify_manifest
from scale_first_failure import first_failure
from native_observation import native_observation_flags
from host_stall_sampler import stop_sampler
PARENT=Path(os.environ.get('OC_COMPLETED_PARENT','verified-completed142-parent'));OUT=Path(os.environ.get('OC_COLD_PROOF_OUT','completed142-cold-evidence')).resolve();SERVER=OUT/'server'
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
def main():
 b=json.loads(Path(os.environ.get('OC_COMPLETED_BINDING','scripts/ci/completed142-parent.json')).read_text());assert b['runId']==41187979592
 assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(b['files'])
 for n,h in b['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(PARENT/n)==h,n
 old=PARENT/'physical-radius-1k-evidence';identity=json.loads((old/'identity.json').read_text());checkpoint=json.loads((old/'checkpoint.json').read_text());verify_manifest(old,checkpoint)
 status=json.loads((old/'checkpoint-status.json').read_text());assert status['completionObserved'] and status['serverExit']==0 and not status['segmentPending']
 xml=[E.parse(p).getroot() for p in (PARENT/'production-src/build/test-results/test').glob('TEST-*.xml')];assert sum(int(t.attrib['tests']) for t in xml)==271 and sum(int(t.attrib['failures'])+int(t.attrib['errors']) for t in xml)==0
 assert not OUT.exists();OUT.mkdir();shutil.copytree(old/'server',SERVER);assert not Path('production-candidate').exists();shutil.copytree(PARENT/'production-candidate','production-candidate')
 jar,digest=verify_candidate(Path('production-candidate'),'v253.125.142',os.environ['EXPECTED_SOURCE_COMMIT']);assert digest==identity['jarSha256']=='8c4bef84914d945978e48ff4da5c593e549c20250ab1beae3eebd35d0a60f524';assert sha(SERVER/'mods'/jar.name)==digest
 assert not list((SERVER/'mods').glob('*fixture*.jar'))
 jobfile=SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat';assert len(nbtlib.load(jobfile)['data']['job'])==0,'Generation is incomplete; do not start a second job'
 inventory=lambda:{p.relative_to(OUT).as_posix():sha(p) for p in (SERVER/'world').rglob('*') if p.is_file()}
 before=inventory();(OUT/'before-world-manifest.json').write_text(json.dumps(before,indent=2));shutil.copyfile(old/'checkpoint-timing.json',OUT/'original-campaign-timing.json');shutil.copyfile(old/'identity.json',OUT/'identity.json')
 for n,h in before.items():assert sha(OUT/n)==h
 if os.environ.get('OC_POST_PROOF_PREPARE_ONLY')=='true':
  print(json.dumps({'prepared':True,'worldBytesVerified':True,'sameHostedJar':digest,'nativeStarted':False}));return
 lines=[];log=(OUT/'cold.log').open('w');start=time.monotonic();ready=None;diag=None;saved=False
 flags=['java','-Xms1G','-Xmx14G','-XX:+UseZGC','-XX:SoftMaxHeapSize=10G',*native_observation_flags(OUT)]
 process=subprocess.Popen(flags+['-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 err=(OUT/'host-sampler-1-stderr.txt').open('x');sampler=subprocess.Popen([os.sys.executable,'scripts/ci/host_stall_sampler.py','--pid',str(process.pid),'--output',str(OUT/'host-sampler-1.jsonl'),'--stop',str(OUT/'host-sampler-1.stop')],stdout=subprocess.DEVNULL,stderr=err)
 def collect():
  for line in process.stdout:lines.append(line);log.write(line);log.flush()
 thread=threading.Thread(target=collect,daemon=True);thread.start()
 def command(s):process.stdin.write(s+'\n');process.stdin.flush()
 try:
  while process.poll() is None:
   text=''.join(lines);bad=first_failure(text)
   if bad:raise RuntimeError('Cold FIRST: '+bad)
   if 'Resumed pregen job after restart:' in text or 'PREGEN-ACCEPTANCE-DONE' in text:raise RuntimeError('Completed case unexpectedly resumed generation')
   if 'Done (' in text and ready is None:ready=time.monotonic()
   if ready is not None and time.monotonic()-ready>=30 and diag is None:command('oceancanvas diagnostics');diag=time.monotonic()
   if diag is not None and 'Diagnostic bundle ' in text and ' written to ' in text and not saved:command('save-all flush');saved=True
   if saved and 'Saved the game' in text:break
   if ready is None and time.monotonic()-start>240:raise TimeoutError('Cold startup ceiling')
   if time.monotonic()-start>600:raise TimeoutError('Cold proof ceiling')
   time.sleep(.05)
 finally:
  if process.poll() is None:command('stop');process.wait(timeout=180)
  thread.join(10);log.close();code=stop_sampler(sampler,OUT/'host-sampler-1.stop');err.close();assert code==0
 assert process.returncode==0 and ready is not None and saved and first_failure((OUT/'cold.log').read_text()) is None
 assert sha(SERVER/'mods'/jar.name)==digest and len(nbtlib.load(jobfile)['data']['job'])==0
 after=inventory();(OUT/'cold-world-manifest.json').write_text(json.dumps(after,indent=2));timing=json.loads((OUT/'original-campaign-timing.json').read_text());assert timing['elapsedHours']>8
 report={'sourceCommit':identity['sourceCommit'],'jarSha256':digest,'parentRun':b['runId'],'parentWholeSha256':b['wholeSha256'],'serverExit':0,'sameBinary':True,'generationRerun':False,'nativeColdSaveCompleted':True,'originalStartedUtc':timing['startedUtc'],'historicalTimingPassed':False,'elapsedCampaignHours':timing['elapsedHours'],'independentPhysicalLightIntegrityPending':True,'saveRestartAccepted':False,'scaleAccepted':False}
 (OUT/'cold-proof.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
