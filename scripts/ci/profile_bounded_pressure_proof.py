"""Copied failure-state latency investigation; high-frequency JFR is not throughput proof."""
from pathlib import Path, PurePosixPath
import os,json,hashlib,urllib.request,zipfile,shutil,subprocess,threading,time
mode=os.environ['PROFILE_MODE'];assert mode in ['baseline','candidate']
scale=os.environ['SCALE'];assert scale in ['5k','20k']
pins={
 '5k':(12310016114,'0b8b1a76c02a489fb5a0b5673fbbbcea6868427b8f01ab1c13652b03e66cfcbd',565,'259c9d45c03d92335bcf493f76f626604da7d36e','9103a611c14fc97e93639c6a0b81c8cacc0b12b61fc9e1dedf5abbfc24aa9960','2026-10-19T20:52:44.385257+00:00'),
 '20k':(12311000485,'f13207b3ec2b6eee5a0494cfcf8b4092eddcbb1d68500d742c7ad39e2fe7a191',2717,'259c9d45c03d92335bcf493f76f626604da7d36e','e245f519fc0f6e107bd0c7b6e38c402989208544df58a5a70b771540e3f2b57c','2026-10-19T20:52:44.316874+00:00')}
aid,zipsha,count,source,jarsha,clock=pins[scale]
root=Path('pressure-proof-'+scale+'-'+mode).resolve();assert not root.exists();root.mkdir();server=root/'server'
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
parent.unlink()
jars=list((server/'mods').glob('oceancanvas-*.jar'));assert len(jars)==1
with jars[0].open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==jarsha
parentSource,parentJar=source,jarsha
if mode=='candidate':
 subprocess.run(['git','diff','--exit-code','85a4d0ef7bc7dd52068a9b4ac80dcdb3d9d3cf12','--','production-src'],check=True)
 candidate=Path('production-src/build/libs/oceancanvas-26.2-v253.125.90.jar').resolve()
 with candidate.open('rb') as f:jarsha=hashlib.file_digest(f,'sha256').hexdigest()
 source='85a4d0ef7bc7dd52068a9b4ac80dcdb3d9d3cf12'
 jars[0].unlink();destination=server/'mods'/candidate.name;shutil.copy2(candidate,destination);jars=[destination]
e={'scope':'first-failure latency investigation on copied failed checkpoint, not campaign resume or acceptance','parentArtifactId':aid,'parentArchiveSha256':zipsha,'parentVerifiedFiles':count,'candidateSourceCommit':source,'candidateJarSha256':jarsha,'originalTiming':previous['originalTiming'],'parentIdentity':previous['parentIdentity'],'fixtureHeapGiB':14,'binaryUpgrade':mode=='candidate','profileMode':mode,'parentDiagnosticSource':parentSource,'parentDiagnosticJar':parentJar,'highFrequencyJfrObserverOverhead':False,'throughputEquivalenceClaimed':False,'acceptancePassed':False}
(root/'identity.json').write_text(json.dumps(e,indent=2));(root/'java-version.txt').write_text(subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True));(root/'harness-source.txt').write_text(subprocess.check_output(['git','rev-parse','HEAD'],text=True))
config=root/'latency.jfc';subprocess.run(['jfr','configure','--input','profile.jfc','--output',str(config),'jdk.ExecutionSample#period=10ms','jdk.NativeMethodSample#period=10ms'],check=True)
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
  command('oceancanvas pregen profile overnight');end=time.monotonic()+1200
  while time.monotonic()<end:
   failed=[s for s in lines if 'P2 performance budget assertion:' in s and '=FAIL' in s]
   if failed:
    first=failed[0].strip();(root/'first-failure.json').write_text(json.dumps({'line':first,'observedMonotonicNs':time.monotonic_ns()}));break
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
