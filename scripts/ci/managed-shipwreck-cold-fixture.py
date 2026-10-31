"""Specific uncovered native shipwreck conservation/cold case; never a scale acceptance."""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time,re,zipfile
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
PARENT=Path(os.environ.get('OC_MANAGED_PARENT','verified-managed-parent'));OUT=Path(os.environ.get('OC_MANAGED_OUT','managed-shipwreck-evidence'));SERVER=OUT/'server'
BINDING=Path(os.environ.get('OC_MANAGED_BINDING','scripts/ci/managed-v139-parent.json'))
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
def main():
 assert not OUT.exists();b=json.loads(BINDING.read_text());assert b['runId']==41156473165
 assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(b['files'])
 for n,h in b['files'].items():assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts;assert sha(PARENT/n)==h,n
 old=PARENT/'cell-receipt-fixture-evidence';manifest=json.loads((old/'addonfree-cold-manifest.json').read_text())
 for n,h in manifest.items():assert sha(old/n)==h,n
 OUT.mkdir();shutil.copytree(old/'server',SERVER)
 jar,digest=verify_candidate(Path('production-candidate'),os.environ.get('EXPECTED_BUILD','v253.125.142'),os.environ['EXPECTED_SOURCE_COMMIT']);assert not list((SERVER/'mods').glob('*fixture*.jar'))
 oldJars=list((SERVER/'mods').glob('oceancanvas-*.jar'));assert len(oldJars)==1
 pre=OUT/'parent-candidate-evidence';pre.mkdir();shutil.move(str(oldJars[0]),pre/oldJars[0].name);shutil.copyfile(jar,SERVER/'mods'/jar.name)
 assert sha(SERVER/'mods'/jar.name)==digest
 addon=Path(os.environ.get('OC_MANAGED_ADDON','production-src/build/fixture-libs/managed-shipwreck-fixture.jar'));assert addon.exists()
 if os.environ.get('OC_POST_PROOF_PREPARE_ONLY')=='true':
  print(json.dumps({'prepared':True,'existingFixtureWorld':True,'candidateJar':digest,'nativeStarted':False}));return
 def inventory():return {p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()}
 def phase(label,managed):
  lines=[];start=time.monotonic();sent=False;reason='';logpath=OUT/(label+'.log')
  flags=['java','-Xms512M','-Xmx3G','-XX:+UseZGC']
  if managed:flags+=['-Doceancanvas.fixture.managedEvidence='+str((OUT/'native-template-snapshots').resolve())]
  with logpath.open('w') as log:
   p=subprocess.Popen(flags+['-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
   def reader():
    for line in p.stdout:lines.append(line);log.write(line);log.flush()
   t=threading.Thread(target=reader,daemon=True);t.start()
   def command(s):p.stdin.write(s+'\n');p.stdin.flush()
   try:
    while p.poll() is None:
     text=''.join(lines);bad=first_failure(text)
     if bad:raise RuntimeError('FIRST: '+bad)
     if any(x in text for x in ('Unknown or incomplete command','Incorrect argument','Forever World blocked PREGEN:')):raise RuntimeError('Managed fixture command refused')
     if 'Done (' in text and not sent:
      if label=='role':command('oceancanvas forever status');command('oceancanvas forever role EXPERIMENT')
      elif managed:command('oceancanvas pregen profile overnight');command('oceancanvas pregen start 32 1328 1760 confirm')
      sent=True
     if label=='role' and 'Forever World role set to EXPERIMENT' in text:reason='ROLE_BOUND';break
     if managed and 'MANAGED-SHIPWRECK-PASTE-SNAPSHOT sourceCaptured=true destinationCaptured=true' in text:reason='ACTUAL_NATIVE_BEFORE_AND_PASTE_SNAPSHOTS';break
     if label=='cold' and re.search(r'PREGEN-ACCEPTANCE-DONE .*chunks=16(?:\D|$)',text):reason='ADDONFREE_RESUME_STRICT_DONE_16';break
     if not sent and time.monotonic()-start>240:raise TimeoutError('Startup ceiling')
     if time.monotonic()-start>420:raise TimeoutError('Phase ceiling')
     time.sleep(.05)
   finally:
    if p.poll() is None:command('stop');p.wait(timeout=180)
    t.join(10)
  assert p.returncode==0 and reason;assert first_failure(logpath.read_text()) is None
  (OUT/(label+'-manifest.json')).write_text(json.dumps(inventory(),indent=2));return reason
 role=phase('role',False);shutil.copyfile(addon,SERVER/'mods'/addon.name);pending=phase('managed-before-cold',True)
 snapshots=OUT/'native-template-snapshots';assert all((snapshots/n).is_file() for n in ('before.nbt','before.json','after-paste.nbt','after-paste.json'))
 job=nbtlib.load(SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas/job_state.dat')['data']
 assert len(job['job'])==1,'Cannot prove cold resume after already completed job; do not replay unchanged scenario'
 first=inventory();(OUT/'immutable-before-cold-manifest.json').write_text(json.dumps(first,indent=2))
 with zipfile.ZipFile(OUT/'immutable-before-cold.zip','x',zipfile.ZIP_DEFLATED,compresslevel=1) as z:
  for n,h in first.items():assert sha(OUT/n)==h;z.write(OUT/n,n)
 shutil.move(str(SERVER/'mods'/addon.name),OUT/addon.name);assert not list((SERVER/'mods').glob('*fixture*.jar'));cold=phase('cold',False);assert 'ocmanagedshipwreckfixture' not in (OUT/'cold.log').read_text();assert sha(SERVER/'mods'/jar.name)==digest
 (OUT/'fixture-status.json').write_text(json.dumps({'role':role,'pending':pending,'cold':cold,'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'independentNativeConservationPending':True,'managedStructureAccepted':False,'scaleAccepted':False},indent=2))
if __name__=='__main__':main()
