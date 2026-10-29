"""Copied failure-state latency investigation; high-frequency JFR is not throughput proof."""
from pathlib import Path, PurePosixPath
import sys,os,json,hashlib,urllib.request,zipfile,shutil,subprocess,threading,time
import xml.etree.ElementTree as ET
tests=[ET.parse(p).getroot() for p in Path('production-src/build/test-results/test').glob('TEST-*.xml')]
assert sum(int(t.attrib['tests']) for t in tests)==147
assert all(int(t.attrib.get('failures',0))==0 and int(t.attrib.get('errors',0))==0 for t in tests)
mode=os.environ['PROFILE_MODE'];assert mode == 'candidate'
scale=os.environ['SCALE'];assert scale=='20k'
aid,zipsha,count,source,jarsha,clock=(12892250429,'53564f227d2c71567b8355a8cc11c1a6f7547b04c3293ad0936b95da72bfd19e',2876,'3ee15621cb553f3dca26371cf7310ea6dcb5cb74','9ece8a1022560c826d0ca4efb8bcc9a6f79a43744315cea9c67f41db3cd8a53a','2026-10-19T20:52:44.316874+00:00')
root=Path('host-stall-attribution-continuation-20k-candidate').resolve();assert not root.exists();root.mkdir();server=root/'server'
parent=Path('verified124-parent')
sha=lambda p:hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
assert sha(parent/'fixture-manifest.json')=='1c4080cd1a03e980737b552e71ac713f1645a0b451f0931819509ab2904a0f49'
assert sha(parent/'evidence.json')=='c62f06378165aef812996c3ad7f591ccc22b4629abfc39ded4af9c062b3c81ce'
manifest=json.loads((parent/'fixture-manifest.json').read_text());previous=json.loads((parent/'evidence.json').read_text())
assert previous['fixtureExit']==0 and previous['candidateSourceCommit']==source and previous['candidateJarSha256']==jarsha
assert previous['originalTiming']['startedUtc']==clock and previous['startupDone'] and previous['stopReason']=='FIRST_FAILURE' and not previous['errors']
assert len(manifest['files'])==count
assert set(manifest['files'])=={f.relative_to(parent).as_posix() for f in (parent/'server').rglob('*') if f.is_file()}
parentLog=(parent/'console.log').read_text()
parentFailures=[line.strip() for line in parentLog.splitlines() if '/ERROR]' in line]
assert parentFailures==previous['errors'] and len(parentFailures)==1
assert parentFailures[0].strip()=='[10:54:49] [OceanCanvas-Stall-Watchdog/ERROR]:'
assert previous['firstPerformanceFailure']==parentFailures[0].strip() and not previous['performanceFailures']
assert 'Done (36.179s)' in parentLog
(root/'retained-parent-failures.json').write_text(json.dumps(parentFailures,indent=2))
for name,digest in manifest['files'].items():
 assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
 assert sha(parent/name)==digest,name
 target=root/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(parent/name,target);assert sha(target)==digest
# Preserve authenticated parent archive on runner; upload excludes duplicate parent.zip, original artifact remains immutable.
jars=list((server/'mods').glob('oceancanvas-*.jar'));assert len(jars)==1
with jars[0].open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==jarsha
parentSource,parentJar=source,jarsha
candidateSource='3ee15621cb553f3dca26371cf7310ea6dcb5cb74'
subprocess.run(['git','diff','--exit-code',candidateSource,'--','production-src'],check=True)
# SAME exact hosted124 binary retained; local build is regression evidence only.
assert source==candidateSource
installed=jars[0]
with zipfile.ZipFile(installed) as jar:assert json.loads(jar.read('fabric.mod.json'))['version']=='26.2-v253.125.124'
assert sha(installed)==parentJar
assert not any('fixture' in f.name.lower() or 'probe' in f.name.lower() for f in (server/'mods').glob('*.jar'))

e={'scope':'Newest fully verified124 checkpoint with SAME hosted124 binary plus independent bounded host stall telemetry. Attribution only; observer cost and no throughput equivalence. All guards, original clock and proofs retained. No acceptance claim',
 'parentArtifactId':aid,'parentArchiveSha256':zipsha,'parentVerifiedFiles':count,'candidateSourceCommit':source,'candidateJarSha256':jarsha,
 'originalTiming':previous['originalTiming'],'parentIdentity':previous['parentIdentity'],'fixtureHeapGiB':14,
 'runtimeFlags':['-Xms1G','-Xmx14G','-XX:+UseZGC'],'binaryUpgrade':False,'profileMode':mode,'parentDiagnosticSource':parentSource,'parentDiagnosticJar':parentJar,'highFrequencyJfrObserverOverhead':False,'throughputEquivalenceClaimed':False,'acceptancePassed':False}
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
 samplerSource=Path('scripts/ci/host_stall_sampler.py').resolve()
 samplerStop=root/'host-sampler.stop';samplerOutput=root/'host-sampler.jsonl'
 samplerError=(root/'host-sampler-stderr.txt').open('x')
 sampler=subprocess.Popen([sys.executable,str(samplerSource),'--pid',str(p.pid),'--output',str(samplerOutput),'--stop',str(samplerStop)],stdout=subprocess.DEVNULL,stderr=samplerError)

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
  try:
   if p.poll() is None:command('stop');p.wait(timeout=240)
  finally:
   from host_stall_sampler import stop_sampler
   samplerExit=stop_sampler(sampler,samplerStop);samplerError.close()
  reader.join(10)
 first=first or first_failure()
 if first:(root/'first-failure.json').write_text(json.dumps({'line':first,'observedMonotonicNs':time.monotonic_ns()}))
assert samplerExit==0 and samplerOutput.stat().st_size<=8*1024*1024
samplerRows=[json.loads(line) for line in samplerOutput.read_text().splitlines()]
assert 2<=len(samplerRows)<=2000 and samplerRows[-1]['terminal']
assert all(row['pid']==p.pid and row['startTicks']==samplerRows[0]['startTicks'] for row in samplerRows)
e['hostSampler']={'sourceSha256':sha(samplerSource),'outputSha256':sha(samplerOutput),'rows':len(samplerRows),'exit':samplerExit,'terminal':True,'intervalSec':1,'maxRecords':2000,'maxBytes':8*1024*1024,'pid':p.pid,'startTicks':samplerRows[0]['startTicks'],'attributionOnly':True}
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
