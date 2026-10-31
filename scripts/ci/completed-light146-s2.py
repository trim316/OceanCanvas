"""Specific new upper-light proof on the completed saved main world. No generation commands."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time,base64,datetime
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
from native_observation import native_observation_flags
from host_stall_sampler import stop_sampler
PARENT=Path(os.environ.get('OC_LIGHT_PARENT','verified-light146-parent'));OUT=Path(os.environ.get('OC_LIGHT_OUT','upper-sky146-s2-evidence')).resolve();SERVER=OUT/'server'
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
def main():
 b=json.loads(Path('scripts/ci/light146-s2-parent.json').read_text());assert b['runId']==41226748487
 assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(b['files'])
 for n,h in b['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(PARENT/n)==h,n
 old=PARENT/'upper-sky146-evidence';manifest=json.loads((old/'world-manifest.json').read_text())
 for n,h in manifest.items():assert sha(old/n)==h,n
 prior=json.loads((old/'readout-status.json').read_text());assert prior['segmentPending'] and prior['failure'] is None and prior['serverExit']==0 and not prior['generationObserved']
 assert prior['sourceCommit']==os.environ['EXPECTED_SOURCE_COMMIT'] and prior['nativeCompleted']==13371
 assert not OUT.exists();OUT.mkdir();shutil.copytree(old/'server',SERVER)
 evidence=OUT/'parent-segment-evidence';evidence.mkdir()
 for name in ['readout.log','readout-status.json','readout-progress.json','candidate-transition.json']:shutil.copyfile(old/name,evidence/name)
 shutil.copytree(old/'parent-evidence',OUT/'parent-evidence');shutil.copyfile(old/'readout-progress.json',OUT/'readout-progress.json')
 world=SERVER/'world';job=world/'dimensions/minecraft/overworld/data/oceancanvas/job_state.dat';assert len(nbtlib.load(job)['data']['job'])==0
 before={p.relative_to(OUT).as_posix():sha(p) for p in world.rglob('*') if p.is_file()}
 assert set(before)=={n for n in manifest if n.startswith('server/world/')}
 for n,h in before.items():assert h==manifest[n]
 (OUT/'before-world-manifest.json').write_text(json.dumps(before,indent=2));shutil.copyfile(old/'original-campaign-timing.json',OUT/'original-campaign-timing.json');timing=json.loads((OUT/'original-campaign-timing.json').read_text());assert timing['elapsedHours']>8
 identity=nbtlib.load(world/'dimensions/minecraft/overworld/data/oceancanvas/forever_world_stewardship.dat')['data']['identity'];assert len(identity)==1;token=str(identity[0]).split('|')[0];world_id=base64.urlsafe_b64decode(token+'='*((-len(token))%4)).decode();assert world_id==prior['worldUuid']
 jar,digest=verify_candidate(PARENT/'production-candidate','v253.125.146',os.environ['EXPECTED_SOURCE_COMMIT']);assert digest=='c3549462d65f21e0cda9bd16a30ad34040684ea09688317ee64361b67160dd36'
 assert sha(SERVER/'mods'/jar.name)==digest
 shutil.copytree(PARENT/'production-candidate',Path('production-candidate'))
 addon=SERVER/'mods/upper-sky-readout.jar';assert addon.exists()
 transition={'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'worldUuid':world_id,'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'originalStartedUtc':timing['startedUtc'],'originalElapsedHours':timing['elapsedHours'],'historicalTimingPassed':False,'generationCommands':[],'selection':[-1000,1000,-1000,1000],'scaleAccepted':False}
 (OUT/'candidate-transition.json').write_text(json.dumps(transition,indent=2))
 for n,h in before.items():assert sha(OUT/n)==h
 if os.environ.get('OC_LIGHT146_PREPARE_ONLY')=='true':print(json.dumps({'prepared':True,'allParentFiles':len(b['files']),'allWorldFiles':len(before),'worldUuid':world_id,'jar':digest,'MinecraftLaunched':False}));return
 lines=[];first=[];generation=[];ready=threading.Event();log=(OUT/'readout.log').open('x');start=time.monotonic();ready_at=None;last_progress=start;completed=0;failure=None;done=False;intentional_stop=False
 flags=['java','-Xms1G','-Xmx14G','-XX:+UseZGC','-XX:SoftMaxHeapSize=10G',f'-Doceancanvas.readoutOutput={OUT}',f'-Doceancanvas.readoutSource={os.environ["EXPECTED_SOURCE_COMMIT"]}',f'-Doceancanvas.readoutExpectedWorld={world_id}',*native_observation_flags(OUT)]
 p=subprocess.Popen(flags+['-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 err=(OUT/'host-sampler-1-stderr.txt').open('x');sampler=subprocess.Popen([os.sys.executable,'scripts/ci/host_stall_sampler.py','--pid',str(p.pid),'--output',str(OUT/'host-sampler-1.jsonl'),'--stop',str(OUT/'host-sampler-1.stop')],stdout=subprocess.DEVNULL,stderr=err)
 def collect():
  for line in p.stdout:
   log.write(line);log.flush()
   bad=first_failure(line)
   if bad and not first:first.append(bad)
   if 'Done (' in line:ready.set()
   if 'Resumed pregen job after restart:' in line or 'PREGEN-ACCEPTANCE-DONE' in line:generation.append(line.strip())
 thread=threading.Thread(target=collect,daemon=True);thread.start()
 def command(text):p.stdin.write(text+'\n');p.stdin.flush()
 try:
  while p.poll() is None:
   now=time.monotonic()
   if first:raise RuntimeError('FIRST: '+first[0])
   if generation:raise RuntimeError('Unexpected generation/replay on completed world')
   if ready.is_set() and ready_at is None:ready_at=now;last_progress=now;command('oceancanvas diagnostics lighting')
   if ready_at is None and now-start>240:raise TimeoutError('Startup240 ceiling')
   progress=OUT/'readout-progress.json'
   if progress.exists():
    q=json.loads(progress.read_text());assert q['worldUuid']==world_id and q['sourceCommit']==os.environ['EXPECTED_SOURCE_COMMIT'] and q['total']==15876
    if q['completed']>completed:completed=q['completed'];last_progress=now
    if completed==15876:done=True;intentional_stop=True;break
   if ready_at is not None and now-last_progress>63:raise TimeoutError('Native light proof no-progress63s')
   if ready_at is not None and now-ready_at>=1200:intentional_stop=True;break
   time.sleep(1)
 except BaseException as e:failure=str(e)
 finally:
  if p.poll() is None:
   command('oceancanvas diagnostics lighting');command('save-all flush');command('stop');p.wait(timeout=180)
  thread.join(10);log.close();code=stop_sampler(sampler,OUT/'host-sampler-1.stop');err.close();assert code==0
 if p.returncode!=0:failure=failure or 'Unclean native stop'
 if not intentional_stop:failure=failure or 'Unexpected native exit before proof segment boundary'
 assert len(nbtlib.load(job)['data']['job'])==0 and sha(SERVER/'mods'/jar.name)==digest
 actual_first=first_failure((OUT/'readout.log').read_text());failure=failure or actual_first
 if (OUT/'readout-progress.json').exists():
  final=json.loads((OUT/'readout-progress.json').read_text());assert final['worldUuid']==world_id;completed=final['completed'];done=completed==15876
 current={p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()};(OUT/'world-manifest.json').write_text(json.dumps(current,indent=2))
 report={**transition,'serverExit':p.returncode,'nativeCompleted':completed,'nativeReadoutDone':done,'segmentPending':not done and failure is None,'failure':failure,'generationObserved':bool(generation),'fieldIntegrityAccepted':False,'physicalIntegrityRecheckPending':True};(OUT/'readout-status.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
 if failure:raise RuntimeError(failure)
if __name__=='__main__':main()
