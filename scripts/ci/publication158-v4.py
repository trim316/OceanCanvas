"""Diagnostic-only publication attribution on exact latest failed158 snapshot; no acceptance retry."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
PARENT=Path(os.environ.get('OC_ABSOLUTE_PARENT','verified-publication158-v4-parent'));OUT=Path(os.environ.get('OC_ABSOLUTE_OUT','publication158-v4-evidence')).resolve();SERVER=OUT/'server'
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
def main():
 b=json.loads(Path('scripts/ci/publication158-v4-parent.json').read_text());assert b['runId']==41945383125
 assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(b['files'])
 for n,h in b['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(PARENT/n)==h,n
 old=PARENT/'publication158-v3-evidence';assert not OUT.exists();OUT.mkdir();shutil.copytree(old/'server',SERVER)
 manifest=json.loads((old/'world-manifest.json').read_text())
 for n,h in manifest.items():assert sha(OUT/n)==h,n
 assert len(nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat')['data']['job'])==1
 jar,digest=verify_candidate(Path('production-candidate'),'v253.125.158',os.environ['EXPECTED_SOURCE_COMMIT'])
 assert digest=='e63b598688ad7dc885d01b33a2ada6bc1860eaffb839a79b7ec00386f81509ae'
 retained=OUT/'parent-candidate-evidence';retained.mkdir()
 for p in list((SERVER/'mods').glob('oceancanvas-*.jar'))+list((SERVER/'mods').glob('*fixture*.jar')):shutil.move(str(p),retained/p.name)
 assert not list((SERVER/'mods').glob('*fixture*.jar'));shutil.copyfile(jar,SERVER/'mods'/jar.name)
 addon=Path('scripts/ci/frozen/recovery158-publication-probe-v4-fixture.jar');assert sha(addon)=='71ae47d92ac782696fbbf678292927a0229a60860555ab9ef9f5f5b060bdb290';shutil.copyfile(addon,SERVER/'mods'/addon.name)
 (OUT/'candidate-transition.json').write_text(json.dumps({'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'diagnosticReconstruction':False,'readOnlyProbeExtended':True,'scaleAccepted':False},indent=2))
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
  observed=phase('publication-diagnostic',True)
  completed='DIAGNOSTIC_JOB_DONE_OBSERVED_NOT_ACCEPTED' if observed=='RETAINED_JOB_STRICT_DONE_16' else observed
 except BaseException as e:failure=str(e)
 finally:
  inventory={p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()};(OUT/'world-manifest.json').write_text(json.dumps(inventory,indent=2))
  status={'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'parentRunId':b['runId'],'parentWholeSha256':b['wholeSha256'],'failure':failure,'diagnosticOnly':True,'recovery':completed,'cold':cold,'fieldIntegrityAccepted':False,'addonAbsent':not list((SERVER/'mods').glob('*fixture*.jar')),'independentSavedIntegrityPending':True,'scaleAccepted':False}
  trace=OUT/'native-publications.jsonl'; status['publicationTracePresent']=trace.exists()
  if trace.exists():
   rows=[json.loads(x) for x in trace.read_text().splitlines()]; status['publicationTraceRows']=len(rows);status['probeErrors']=sum(x.get('probeErrors',0) for x in rows if x.get('type')=='summary');status['probeSourceBound']=any(x.get('sourceBound') for x in rows if x.get('type')=='summary')
  status['fixturePendingJobs']=len(nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat')['data']['job'])
  (OUT/'fixture-status.json').write_text(json.dumps(status,indent=2));print(json.dumps({k:v for k,v in status.items() if k!='failure'}))
 if failure:raise RuntimeError(failure)
if __name__=='__main__':main()
