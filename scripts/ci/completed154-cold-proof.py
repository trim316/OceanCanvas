"""Uncovered addon-free154 cold-save proof; no generation, rebuild, or fixture compilation."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time,base64
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
from native_observation import native_observation_flags
from host_stall_sampler import stop_sampler
PARENT=Path(os.environ.get('OC_COMPLETED_PARENT','verified-cold154-parent'));OUT=Path(os.environ.get('OC_COLD_PROOF_OUT','completed154-cold-evidence')).resolve();SERVER=OUT/'server'
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
def main():
 b=json.loads(Path('scripts/ci/cold154-parent.json').read_text());assert b['runId']==41803089033
 assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(b['files'])
 for n,h in b['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(PARENT/n)==h,n
 old=PARENT/'block-light154-s13-evidence';prior=json.loads((old/'readout-status.json').read_text());manifest=json.loads((old/'world-manifest.json').read_text())
 assert prior['nativeReadoutDone'] and prior['nativeCompleted']==15876 and prior['failure'] is None and prior['serverExit']==0 and not prior['generationObserved']
 assert b['nativeFieldReview']['fieldValues']==3072000000 and b['nativeFieldReview']['skyMismatches']==b['nativeFieldReview']['blockMismatches']==0 and b['modelApplicability']['snapshotComparisonApplicable']
 assert not OUT.exists();OUT.mkdir();shutil.copytree(old/'server',SERVER)
 if not Path('production-candidate').exists():shutil.copytree(PARENT/'production-candidate','production-candidate')
 jar,digest=verify_candidate(Path('production-candidate'),'v253.125.154',os.environ['EXPECTED_SOURCE_COMMIT']);assert digest==prior['jarSha256']=='888a6f8381546206c886f2cc0771f6bbf6ac2f385ad80709f3dce90519f8c6c6'
 for source_file in [*(PARENT/'production-src/build/test-results/test').glob('TEST-*.xml'),PARENT/'production-build.log']:
  target=Path(source_file.relative_to(PARENT));target.parent.mkdir(parents=True,exist_ok=True)
  if target.exists():assert sha(target)==sha(source_file)
  else:shutil.copyfile(source_file,target)
 assert sha(SERVER/'mods'/jar.name)==digest
 evidence=OUT/'parent-addon-evidence';evidence.mkdir();addon=SERVER/'mods/upper-sky-readout.jar';assert addon.is_file();shutil.move(str(addon),evidence/addon.name)
 assert not list((SERVER/'mods').glob('*fixture*.jar')) and not (SERVER/'mods/upper-sky-readout.jar').exists()
 jobfile=SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat';assert len(nbtlib.load(jobfile)['data']['job'])==0
 identity=nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/forever_world_stewardship.dat')['data']['identity'];assert len(identity)==1;token=str(identity[0]).split('|')[0];world_id=base64.urlsafe_b64decode(token+'='*((-len(token))%4)).decode();assert world_id==prior['worldUuid']
 inventory=lambda:{p.relative_to(OUT).as_posix():sha(p) for p in (SERVER/'world').rglob('*') if p.is_file()}
 before=inventory();assert set(before)=={n for n in manifest if n.startswith('server/world/')}
 for n,h in before.items():assert h==manifest[n]
 (OUT/'before-world-manifest.json').write_text(json.dumps(before,indent=2));shutil.copyfile(old/'original-campaign-timing.json',OUT/'original-campaign-timing.json')
 for name in ['readout-status.json','readout-progress.json','completed-format6-scheduling-receipt.json']:shutil.copyfile(old/name,OUT/('parent-'+name))
 (OUT/'candidate-transition.json').write_text(json.dumps({'sourceCommit':prior['sourceCommit'],'jarSha256':digest,'worldUuid':world_id,'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'addonRemoved':True,'scaleAccepted':False},indent=2))
 if os.environ.get('OC_POST_PROOF_PREPARE_ONLY')=='true':print(json.dumps({'prepared':True,'allParentFiles':len(b['files']),'allWorldFiles':len(before),'jobEmpty':True,'worldUuid':world_id,'jar':digest,'addonAbsent':True,'MinecraftLaunched':False}));return
 lines=[];log=(OUT/'cold.log').open('w');start=time.monotonic();ready=None;diag=None;saved=False
 flags=['java','-Xms1G','-Xmx14G','-XX:+UseZGC','-XX:SoftMaxHeapSize=10G',*native_observation_flags(OUT)]
 process=subprocess.Popen(flags+['-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 err=(OUT/'host-sampler-1-stderr.txt').open('x');sampler=subprocess.Popen([os.sys.executable,'scripts/ci/host_stall_sampler.py','--pid',str(process.pid),'--output',str(OUT/'host-sampler-1.jsonl'),'--stop',str(OUT/'host-sampler-1.stop')],stdout=subprocess.DEVNULL,stderr=err)
 def collect():
  for line in process.stdout:lines.append(line);log.write(line);log.flush()
 thread=threading.Thread(target=collect,daemon=True);thread.start()
 def command(s):process.stdin.write(s+'\n');process.stdin.flush()
 failure=None;generation=[]
 try:
  while process.poll() is None:
   text=''.join(lines);bad=first_failure(text)
   if bad:raise RuntimeError('Cold FIRST: '+bad)
   generation=[s for s in ['Resumed pregen job after restart:','PREGEN-ACCEPTANCE-DONE','PREGEN-ACCEPTANCE-START','Starting pregen','Pregen started'] if s in text]
   if generation:raise RuntimeError('Completed case unexpectedly resumed generation')
   if 'Done (' in text and ready is None:ready=time.monotonic()
   if ready is not None and time.monotonic()-ready>=30 and diag is None:command('oceancanvas diagnostics');diag=time.monotonic()
   if diag is not None and 'Diagnostic bundle ' in text and ' written to ' in text and not saved:command('save-all flush');saved=True
   if saved and 'Saved the game' in text:break
   if ready is None and time.monotonic()-start>240:raise TimeoutError('Cold startup ceiling')
   if time.monotonic()-start>600:raise TimeoutError('Cold proof ceiling')
   time.sleep(.05)
 except BaseException as e:failure=str(e)
 finally:
  if process.poll() is None:command('stop');process.wait(timeout=180)
  thread.join(10);log.close();code=stop_sampler(sampler,OUT/'host-sampler-1.stop');err.close()
  if code!=0:failure=failure or 'Host sampler failed'
 after=inventory();(OUT/'world-manifest.json').write_text(json.dumps(after,indent=2));timing=json.loads((OUT/'original-campaign-timing.json').read_text());assert timing['elapsedHours']>8
 if process.returncode!=0 or ready is None or not saved:failure=failure or 'Cold startup/save/exit incomplete'
 if sha(SERVER/'mods'/jar.name)!=digest or len(nbtlib.load(jobfile)['data']['job'])!=0:failure=failure or 'Binary or empty job identity changed'
 report={'sourceCommit':prior['sourceCommit'],'jarSha256':digest,'worldUuid':world_id,'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'serverExit':process.returncode,'sameBinary':True,'generationObserved':bool(generation),'nativeColdSaveCompleted':failure is None,'addonAbsent':not (SERVER/'mods/upper-sky-readout.jar').exists(),'originalStartedUtc':timing['startedUtc'],'historicalTimingPassed':False,'originalElapsedHours':timing['elapsedHours'],'failure':failure,'firstIssue':first_failure((OUT/'cold.log').read_text()),'independentPhysicalLightIntegrityPending':True,'saveRestartAccepted':False,'scaleAccepted':False}
 (OUT/'cold-proof.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
 if failure:raise RuntimeError(failure)
if __name__=='__main__':main()
