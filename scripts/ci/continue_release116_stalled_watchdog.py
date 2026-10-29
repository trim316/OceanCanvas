"""Copied failure-state latency investigation; high-frequency JFR is not throughput proof."""
from pathlib import Path, PurePosixPath
import os,json,hashlib,urllib.request,zipfile,shutil,subprocess,threading,time
import xml.etree.ElementTree as ET
tests=[ET.parse(p).getroot() for p in Path('production-src/build/test-results/test').glob('TEST-*.xml')]
assert sum(int(t.attrib['tests']) for t in tests)==114
assert all(int(t.attrib.get('failures',0))==0 and int(t.attrib.get('errors',0))==0 for t in tests)
mode=os.environ['PROFILE_MODE'];assert mode == 'candidate'
scale=os.environ['SCALE'];assert scale=='20k'
aid,zipsha,count,source,jarsha,clock=(12870295244,'b108723f20b01ceb20e1e68bcc83877356f4a80f1e22bdc4e15a8eb9e4216b66',2865,'a0c1b77e579ab319da66025bdd2027fd445bd93e','c394e50073b715bd63029a63aa2b901bceb134e6824ffd4d562b017ca256ce04','2026-10-19T20:52:44.316874+00:00')
root=Path('stalled-watchdog-continuation-20k-candidate').resolve();assert not root.exists();root.mkdir();server=root/'server'
parent=Path('verified115-parent')
sha=lambda p:hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
assert sha(parent/'fixture-manifest.json')=='580a645a70d2c5cc8636bb8824186bde0587584019c8ec2031275e553485492e'
assert sha(parent/'evidence.json')=='fe746f464ede2fceb5acb186a0901afe9c771b49181f3c130086d44825867447'
manifest=json.loads((parent/'fixture-manifest.json').read_text());previous=json.loads((parent/'evidence.json').read_text())
assert previous['fixtureExit']==0 and previous['candidateSourceCommit']==source and previous['candidateJarSha256']==jarsha
assert previous['originalTiming']['startedUtc']==clock and previous['startupDone']
assert len(manifest['files'])==count
assert set(manifest['files'])=={f.relative_to(parent).as_posix() for f in (parent/'server').rglob('*') if f.is_file()}
parentFailures=[line for line in (parent/'console.log').read_text().splitlines() if 'P2 performance budget assertion:' in line and ('=WARN(' in line or '=FAIL(' in line)]
assert any('oc-controller=FAIL(31.99ms)' in line for line in parentFailures)
(root/'retained-parent-failures.json').write_text(json.dumps(parentFailures,indent=2))
for name,digest in manifest['files'].items():
 assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
 assert sha(parent/name)==digest,name
 target=root/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(parent/name,target);assert sha(target)==digest
# Preserve authenticated parent archive on runner; upload excludes duplicate parent.zip, original artifact remains immutable.
jars=list((server/'mods').glob('oceancanvas-*.jar'));assert len(jars)==1
with jars[0].open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==jarsha
parentSource,parentJar=source,jarsha
candidateSource='c8eae1a49cf119d18a51a08b5a53853791919479'
subprocess.run(['git','diff','--exit-code',candidateSource,'--','production-src'],check=True)
pre=root/'binary-upgrade-preimages';pre.mkdir();shutil.move(str(jars[0]),str(pre/jars[0].name))
candidate=Path('production-src/build/libs/oceancanvas-26.2-v253.125.116.jar')
with candidate.open('rb') as f:jarsha=hashlib.file_digest(f,'sha256').hexdigest()
with zipfile.ZipFile(candidate) as jar:assert json.loads(jar.read('fabric.mod.json'))['version']=='26.2-v253.125.116'
code=Path('production-src/src/main/java/net/oceancanvas/mod/OceanCanvas.java').read_text();assert 'public static final String VERSION = "v253.125.116";' in code
installed=server/'mods'/candidate.name;shutil.copyfile(candidate,installed)
with installed.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==jarsha
source=candidateSource;jars=[installed]
assert not any('fixture' in f.name.lower() or 'probe' in f.name.lower() for f in (server/'mods').glob('*.jar'))

e={'scope':'Newest fully verified115 checkpoint upgraded to116 watchdog scheduling candidate. Existing5second stall return applies before direct READY recovery; healthy work remains normal bounded queue. Same stalled recovery and final drain, original clock and strict guards retained; no acceptance claim',
 'parentArtifactId':aid,'parentArchiveSha256':zipsha,'parentVerifiedFiles':count,'candidateSourceCommit':source,'candidateJarSha256':jarsha,
 'originalTiming':previous['originalTiming'],'parentIdentity':previous['parentIdentity'],'fixtureHeapGiB':14,
 'runtimeFlags':['-Xms1G','-Xmx14G','-XX:+UseZGC'],'binaryUpgrade':True,'profileMode':mode,'parentDiagnosticSource':parentSource,'parentDiagnosticJar':parentJar,'highFrequencyJfrObserverOverhead':False,'throughputEquivalenceClaimed':False,'acceptancePassed':False}
(root/'identity.json').write_text(json.dumps(e,indent=2))
(root/'java-version.txt').write_text(subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True))
(root/'harness-source.txt').write_text(subprocess.check_output(['git','rev-parse','HEAD'],text=True))
config=root/'latency.jfc'
subprocess.run(['jfr','configure','--input','profile.jfc','--output',str(config),'jdk.ExecutionSample#period=10ms','jdk.NativeMethodSample#period=10ms'],check=True)
oldRecording=server/'first-failure.jfr'
if oldRecording.exists():
 pre=root/'recording-preimages';pre.mkdir();shutil.move(str(oldRecording),str(pre/oldRecording.name))
lines=[];first=None
with (root/'console.log').open('x',encoding='utf8') as log:
 p=subprocess.Popen(['java','-Xms1G','-Xmx14G','-XX:+UseZGC',f'-XX:StartFlightRecording=filename=first-failure.jfr,settings={config},dumponexit=true','-jar','fabric-server-launch.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',errors='replace')
 def read():
  for line in p.stdout:lines.append(line);log.write(line);log.flush()
 reader=threading.Thread(target=read,daemon=True);reader.start()
 def command(s):p.stdin.write(s+'\n');p.stdin.flush()
 def first_failure():
  return next((s.strip() for s in lines if ('P2 performance budget assertion:' in s and ('=WARN(' in s or '=FAIL(' in s)) or ('/ERROR]' in s and 'Perflib' not in s) or 'TRUNCATED' in s),None)
 reason=None;done=False
 try:
  end=time.monotonic()+240
  while not any('Done (' in s for s in lines):
   first=first_failure()
   if first:reason='FIRST_FAILURE_DURING_STARTUP';break
   if p.poll() is not None:reason='STARTUP_PROCESS_EXIT';break
   if time.monotonic()>end:reason='STARTUP_CEILING';break
   time.sleep(.05)
  if reason is None:
   done=True;command('oceancanvas pregen profile overnight');end=time.monotonic()+1200
   while time.monotonic()<end:
    first=first_failure()
    if first:reason='FIRST_FAILURE';break
    if p.poll() is not None:reason='PROFILE_PROCESS_EXIT';break
    time.sleep(.05)
   reason=reason or 'BOUNDED_1200SEC_CONTINUATION'
 finally:
  if p.poll() is None:command('stop');p.wait(timeout=240)
  reader.join(10)
 first=first or first_failure()
 if first:(root/'first-failure.json').write_text(json.dumps({'line':first,'observedMonotonicNs':time.monotonic_ns()}))
files={}
for f in sorted(server.rglob('*')):
 if f.is_file():
  with f.open('rb') as stream:files[f.relative_to(root).as_posix()]=hashlib.file_digest(stream,'sha256').hexdigest()
assert files[jars[0].relative_to(root).as_posix()]==jarsha
(root/'fixture-manifest.json').write_text(json.dumps({'identity':e,'files':files},indent=2))
e['checkpointLatencySamples']=[line.strip() for line in lines if 'CHECKPOINT-LATENCY attributionOnly=true' in line]
e.update(fixtureExit=p.returncode,firstPerformanceFailure=first,performanceFailures=[s.strip() for s in lines if 'P2 performance budget assertion:' in s and ('=WARN(' in s or '=FAIL(' in s)],errors=[s.strip() for s in lines if '/ERROR]' in s],cleanStop=p.returncode==0,startupDone=done,stopReason=reason,boundedNativePerformancePassed=p.returncode==0 and first is None and reason=='BOUNDED_1200SEC_CONTINUATION',fullAcceptancePassed=False)
(root/'evidence.json').write_text(json.dumps(e,indent=2))
if first or e['errors'] or p.returncode!=0 or reason!='BOUNDED_1200SEC_CONTINUATION':raise RuntimeError('Diagnostic retained first failure; not a performance pass')
