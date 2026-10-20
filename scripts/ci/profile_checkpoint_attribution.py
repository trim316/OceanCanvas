"""Copied failure-state latency investigation; high-frequency JFR is not throughput proof."""
from pathlib import Path, PurePosixPath
import os,json,hashlib,urllib.request,zipfile,shutil,subprocess,threading,time
scale=os.environ['SCALE'];assert scale in ['5k','20k']
pins={
 '5k':(12303113544,'a10a515fbc4ed76866b6628e5043e10b4d74c87753944821a16da7916e3adcb1',564,'b84d8b499e3447d6d338f8e2fdf4b9c85903b867','d6780ef100a670a537eab8807af102c350ee3a245830619b0b1996aa746eca22','2026-10-19T20:52:44.385257+00:00'),
 '20k':(12303606052,'b433d21530a1d67c3ff1eaee3c7d0bfbef7ddfff742e1854d91359e5984d56a7',2715,'9022480839d221c8f830b07c79e3bec479222ddd','9b4a92a19dff486c42b9d40ef6ff0998f2ced57327c363b4a82710efc214b512','2026-10-19T20:52:44.316874+00:00')}
aid,zipsha,count,source,jarsha,clock=pins[scale]
root=Path('latency-'+scale).resolve();assert not root.exists();root.mkdir();server=root/'server'
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
 assert len(parentFailures)==1
 (root/'retained-parent-failures.json').write_text(json.dumps(parentFailures,indent=2))
 for name,digest in manifest['files'].items():
  assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
  with z.open(name) as f:assert hashlib.file_digest(f,'sha256').hexdigest()==digest,name
 for name in manifest['files']:
  target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
  with z.open(name) as src,target.open('xb') as out:shutil.copyfileobj(src,out)
 for name,digest in manifest['files'].items():
  with (root/name).open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==digest,name
parent.unlink() # Only downloaded scratch archive; original authenticated artifact stays immutable.
jars=list((server/'mods').glob('oceancanvas-*.jar'));assert len(jars)==1
with jars[0].open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==jarsha
parentSource, parentJar = source,jarsha
subprocess.run(['git','diff','--exit-code','259c9d45c03d92335bcf493f76f626604da7d36e','--','production-src'],check=True)
candidate=Path('production-src/build/libs/oceancanvas-26.2-v253.125.89.jar').resolve()
with candidate.open('rb') as f:jarsha=hashlib.file_digest(f,'sha256').hexdigest()
source='259c9d45c03d92335bcf493f76f626604da7d36e'
jars[0].unlink();destination=server/'mods'/candidate.name;shutil.copy2(candidate,destination);jars=[destination]

e={'scope':'first-failure latency investigation on copied failed checkpoint, not campaign resume or acceptance',
 'parentArtifactId':aid,'parentArchiveSha256':zipsha,'parentVerifiedFiles':count,'candidateSourceCommit':source,'candidateJarSha256':jarsha,
 'originalTiming':previous['originalTiming'],'parentIdentity':previous['parentIdentity'],'fixtureHeapGiB':14,
 'binaryUpgrade':True,'parentDiagnosticSource':parentSource,'parentDiagnosticJar':parentJar,'telemetryOnlyRelativeToReleased88':True,'fiveKAlsoIncludesPreviouslyValidated88ProtectionQuery':scale=='5k','highFrequencyJfrObserverOverhead':True,'throughputEquivalenceClaimed':False,'acceptancePassed':False}
(root/'identity.json').write_text(json.dumps(e,indent=2))
(root/'java-version.txt').write_text(subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True))
(root/'harness-source.txt').write_text(subprocess.check_output(['git','rev-parse','HEAD'],text=True))
config=root/'latency.jfc'
subprocess.run(['jfr','configure','--input','profile.jfc','--output',str(config),'jdk.ExecutionSample#period=1ms','jdk.NativeMethodSample#period=1ms'],check=True)
lines=[];first=None
with (root/'console.log').open('x',encoding='utf8') as log:
 p=subprocess.Popen(['java','-Xms1G','-Xmx14G',f'-XX:StartFlightRecording=filename=first-failure.jfr,settings={config},dumponexit=true','-jar','fabric-server-launch.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',errors='replace')
 def read():
  for line in p.stdout:lines.append(line);log.write(line);log.flush()
 reader=threading.Thread(target=read,daemon=True);reader.start()
 def command(s):p.stdin.write(s+'\n');p.stdin.flush()
 try:
  end=time.monotonic()+240
  while not any('Done (' in s for s in lines):
   if p.poll() is not None:raise RuntimeError('startup exited')
   if time.monotonic()>end:raise TimeoutError('startup')
   time.sleep(.2)
  command('oceancanvas pregen profile overnight');end=time.monotonic()+900
  while time.monotonic()<end:
   failed=[s for s in lines if 'P2 performance budget assertion:' in s and '=FAIL' in s]
   if failed:
    first=failed[0].strip();(root/'first-failure.json').write_text(json.dumps({'line':first,'observedMonotonicNs':time.monotonic_ns()}))
    break
   if p.poll() is not None:raise RuntimeError('process exited during profile')
   time.sleep(.05)
 finally:
  if p.poll() is None:command('stop');p.wait(timeout=240)
  reader.join(10)
 assert p.returncode==0
files={}
for f in sorted(server.rglob('*')):
 if f.is_file():
  with f.open('rb') as stream:files[f.relative_to(root).as_posix()]=hashlib.file_digest(stream,'sha256').hexdigest()
assert files[jars[0].relative_to(root).as_posix()]==jarsha
(root/'fixture-manifest.json').write_text(json.dumps({'identity':e,'files':files},indent=2))
e['checkpointLatencySamples']=[line.strip() for line in lines if 'CHECKPOINT-LATENCY attributionOnly=true' in line]
e.update(fixtureExit=p.returncode,firstPerformanceFailure=first,performanceFailures=[s.strip() for s in lines if 'P2 performance budget assertion:' in s and '=FAIL' in s],errors=[s.strip() for s in lines if '/ERROR]' in s],cleanStop=True,fullAcceptancePassed=False)
(root/'evidence.json').write_text(json.dumps(e,indent=2))
if first or e['errors']:raise RuntimeError('Diagnostic retained first failure; not a performance pass')
