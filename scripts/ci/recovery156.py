"""Specific deliberate diagnostic reconstruction on COPY of latest failed154 snapshot."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
PARENT=Path(os.environ.get('OC_ABSOLUTE_PARENT','verified-recovery156-parent'));OUT=Path(os.environ.get('OC_ABSOLUTE_OUT','recovery156-evidence')).resolve();SERVER=OUT/'server'
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
def main():
 b=json.loads(Path('scripts/ci/recovery156-parent.json').read_text());assert b['runId']==41873761436 and b['causeReview']['snapshots']==13
 assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(b['files'])
 for n,h in b['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(PARENT/n)==h,n
 old=PARENT/'recovery155-stage-evidence';assert not OUT.exists();OUT.mkdir();shutil.copytree(old/'server',SERVER)
 manifest=json.loads((old/'world-manifest.json').read_text())
 for n,h in manifest.items():assert sha(OUT/n)==h,n
 assert len(nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat')['data']['job'])==1
 jar,digest=verify_candidate(Path('production-candidate'),'v253.125.156',os.environ['EXPECTED_SOURCE_COMMIT'])
 assert digest=='08859539e317c0e78890454040a7ab2f4db824fdfc8323f65046dae61db3ca01'
 retained=OUT/'parent-candidate-evidence';retained.mkdir()
 for p in list((SERVER/'mods').glob('oceancanvas-*.jar'))+list((SERVER/'mods').glob('*fixture*.jar')):shutil.move(str(p),retained/p.name)
 assert not list((SERVER/'mods').glob('*fixture*.jar'));shutil.copyfile(jar,SERVER/'mods'/jar.name)
 addon=Path('scripts/ci/frozen/recovery155-stage-probe-v2-fixture.jar');assert sha(addon)=='ce2980e088f75eb5538797c6ae63aac108f41c6e669e50aedf2bc18c8a22b971';shutil.copyfile(addon,SERVER/'mods'/addon.name)
 (OUT/'candidate-transition.json').write_text(json.dumps({'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'diagnosticReconstruction':True,'scaleAccepted':False},indent=2))
 if os.environ.get('OC_POST_PROOF_PREPARE_ONLY')=='true':print(json.dumps({'prepared':True,'allInputFiles':len(b['files']),'jobPending':1,'sourceAwareJar':digest,'MinecraftLaunched':False}));return
 def phase(label,fixture):
  lines=[];log=(OUT/(label+'.log')).open('w');start=time.monotonic();ready=None;diagnostics=False;saved=False;reason=None
  flags=['java','-Xms1G','-Xmx14G','-XX:+UseZGC','-XX:SoftMaxHeapSize=10G']
  if fixture:flags+=['-Doceancanvas.fixture.finalizerStageProbe='+str(OUT/'finalizer-stages.jsonl')]
  process=subprocess.Popen(flags+['-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
  def collect():
   for line in process.stdout:lines.append(line);log.write(line);log.flush()
  thread=threading.Thread(target=collect,daemon=True);thread.start()
  def command(s):process.stdin.write(s+'\n');process.stdin.flush()
  try:
   while process.poll() is None:
    text=''.join(lines);bad=first_failure(text)
    if bad:raise RuntimeError('Recovery FIRST: '+bad)
    if 'Done (' in text and ready is None:ready=time.monotonic()
    if fixture and __import__('re').search(r'PREGEN-ACCEPTANCE-DONE .*chunks=16(?:\D|$)',text):reason='RETAINED_JOB_STRICT_DONE_16';break
    if not fixture and ready is not None and time.monotonic()-ready>=30 and not diagnostics:command('oceancanvas diagnostics');diagnostics=True
    if not fixture and diagnostics and 'Diagnostic bundle ' in text and ' written to ' in text and not saved:command('save-all flush');saved=True
    if not fixture and saved and 'Saved the game' in text:reason='ADDONFREE_COLD_SAVE';break
    if ready is None and time.monotonic()-start>240:raise TimeoutError('Recovery startup ceiling')
    if time.monotonic()-start>600:raise TimeoutError('Recovery phase ceiling')
    time.sleep(.05)
   if reason is None:raise RuntimeError('Recovery phase incomplete')
  finally:
   if process.poll() is None:command('stop');process.wait(timeout=180)
   thread.join(10);log.close()
  assert process.returncode==0
  return reason
 failure=None;completed=None;cold=None
 try:
  completed=phase('recovery',True)
  assert len(nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat')['data']['job'])==0
  shutil.move(str(SERVER/'mods'/addon.name),OUT/addon.name);assert not list((SERVER/'mods').glob('*fixture*.jar'))
  cold=phase('cold',False)
  assert len(nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat')['data']['job'])==0
 except BaseException as e:failure=str(e)
 finally:
  inventory={p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()};(OUT/'world-manifest.json').write_text(json.dumps(inventory,indent=2))
  status={'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'failure':failure,'recovery':completed,'cold':cold,'addonAbsent':not list((SERVER/'mods').glob('*fixture*.jar')),'independentSavedIntegrityPending':True,'scaleAccepted':False}
  (OUT/'fixture-status.json').write_text(json.dumps(status,indent=2));print(json.dumps({k:v for k,v in status.items() if k!='failure'}))
 if failure:raise RuntimeError(failure)
if __name__=='__main__':main()
