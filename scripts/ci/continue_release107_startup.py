"""Copied failure-state latency investigation; high-frequency JFR is not throughput proof."""
from pathlib import Path, PurePosixPath
import os,json,hashlib,urllib.request,zipfile,shutil,subprocess,threading,time
mode=os.environ['PROFILE_MODE'];assert mode == 'candidate'
scale=os.environ['SCALE'];assert scale=='20k'
pins={'20k':(12846144523,'bc55aa810fc4b8f28cf81b124cebaac1cb4611469b2a5d54450b8977ff14db2e',2735,'674461ed45746761092d93c0ad0994431444be57','2bb214e70fb8a00ad6e92e3d42f6e9a08a15160ec78cc8e177ee0027896eb74f','2026-10-19T20:52:44.316874+00:00')}
aid,zipsha,count,source,jarsha,clock=pins[scale]
root=Path('startup-progress-continuation-'+scale+'-'+mode).resolve();assert not root.exists();root.mkdir();server=root/'server'
class StripAuth(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):return urllib.request.Request(newurl)
urllib.request.install_opener(urllib.request.build_opener(StripAuth()))
parent=root/'parent.zip'
request=urllib.request.Request(f'https://api.github.com/repos/trim316/OceanCanvas/actions/artifacts/{aid}/zip',headers={'Authorization':'Bearer '+os.environ['GH_TOKEN']})
with urllib.request.urlopen(request,timeout=180) as src,parent.open('xb') as out:shutil.copyfileobj(src,out)
with parent.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==zipsha
with zipfile.ZipFile(parent) as z:
 assert len(z.namelist())==len(set(z.namelist()))
 manifest=json.loads(z.read('fixture-manifest.json'));previous=json.loads(z.read('evidence.json'))
 assert previous['fixtureExit']==0 and previous['candidateSourceCommit']==source and previous['candidateJarSha256']==jarsha
 assert previous['originalTiming']['startedUtc']==clock
 assert len(manifest['files'])==count
 assert set(manifest['files'])=={n for n in z.namelist() if n.startswith('server/') and not n.endswith('/')}
 parentFailures=[line for line in z.read('console.log').decode('utf8','replace').splitlines() if 'P2 performance budget assertion:' in line and '=FAIL' in line]
 assert not parentFailures and previous['stopReason']=='STARTUP_CEILING' and not previous['startupDone']
 (root/'retained-parent-failures.json').write_text(json.dumps(parentFailures,indent=2))
 for name,digest in manifest['files'].items():
  assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
  with z.open(name) as f:assert hashlib.file_digest(f,'sha256').hexdigest()==digest,name
 for name in manifest['files']:
  target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
  with z.open(name) as src,target.open('xb') as out:shutil.copyfileobj(src,out)
 for name,digest in manifest['files'].items():
  with (root/name).open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==digest,name
# Preserve authenticated parent archive on runner; upload excludes duplicate parent.zip, original artifact remains immutable.
jars=list((server/'mods').glob('oceancanvas-*.jar'));assert len(jars)==1
with jars[0].open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==jarsha
parentSource,parentJar=source,jarsha
candidateSource='0de6f14ff67bb91c74abb50dcfe10853d5cc17cf'
subprocess.run(['git','diff','--exit-code',candidateSource,'--','production-src'],check=True)
pre=root/'binary-upgrade-preimages';pre.mkdir();shutil.move(str(jars[0]),str(pre/jars[0].name))
candidate=Path('production-src/build/libs/oceancanvas-26.2-v253.125.107.jar')
with candidate.open('rb') as f:jarsha=hashlib.file_digest(f,'sha256').hexdigest()
with zipfile.ZipFile(candidate) as jar:assert json.loads(jar.read('fabric.mod.json'))['version']=='26.2-v253.125.107'
installed=server/'mods'/candidate.name;shutil.copyfile(candidate,installed)
with installed.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==jarsha
source=candidateSource;jars=[installed]

e={'scope':'Newest fully verified ZGC checkpoint; explicit105-to107 binary upgrade eliminates redundant startup PROGRESS-only scan. Native pendingChunks readiness poll and native waiting are unchanged. Same ZGC/Java/14GiB/240sec startup/strict proofs/budgets/health limits/original clock. Full build/tests required before Minecraft; no acceptance claim',
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
