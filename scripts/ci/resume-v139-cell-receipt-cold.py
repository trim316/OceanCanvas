"""Continue the SAME pending native/queued-operator fixture; no reinjection, addon or build rerun."""
from pathlib import Path
import os,json,hashlib,shutil,subprocess,threading,time,re,zipfile
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
from cell_receipt_native_oracle import audit
PARENT=Path('verified-native-receipt-parent');OUT=Path('cell-receipt-fixture-evidence');SERVER=OUT/'server'
assert not OUT.exists()
binding=json.loads(Path('scripts/ci/v139-native-pending-parent.json').read_text())
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(binding['files'])
for n,h in binding['files'].items():assert sha(PARENT/n)==h,n
shutil.copytree(PARENT/'cell-receipt-fixture-evidence',OUT)
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.139',os.environ['EXPECTED_SOURCE_COMMIT']);assert sha(SERVER/'mods'/jar.name)==digest==binding['jarSha256']
def inventory():return {p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()}
folder=SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas'
def data(name):return nbtlib.load(folder/name)['data']
before=data('cell_repair_receipts.dat');assert len(before['receipts'])==1 and len(before['operatorRequests'])==2
assert len(data('job_state.dat')['job'])==1
files=inventory();(OUT/'immutable-pending-receipts-before-cold-manifest.json').write_text(json.dumps(files,indent=2))
with zipfile.ZipFile(OUT/'immutable-pending-receipts-before-cold.zip','x',zipfile.ZIP_DEFLATED,compresslevel=1) as z:
 for n,h in files.items():assert sha(OUT/n)==h;z.write(OUT/n,n)
(OUT/'fixture-transition.json').write_text(json.dumps({'parentRun':binding['runId'],'parentArtifact':binding['artifactId'],'parentWholeSha256':binding['wholeSha256'],'sameStoppedServerBytesVerified':True,'sameJar':digest,'priorFailurePreserved':'operator-pending-checkpoint.log includes no-progress guard from held fixture; not retroactively passed','runtimeMode':'addon-free-no-publication-hold','scaleAccepted':False},indent=2))
addon=SERVER/'mods/cell-receipts-fixture.jar';assert addon.exists();shutil.move(str(addon),OUT/addon.name)
assert not list((SERVER/'mods').glob('*fixture*.jar'))
logpath=OUT/'addonfree-cold.log';lines=[];reason='';started=time.monotonic()
with logpath.open('w') as log:
 p=subprocess.Popen(['java','-Xms512M','-Xmx3G','-XX:+UseZGC','-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 def reader():
  for line in p.stdout:lines.append(line);log.write(line);log.flush()
 thread=threading.Thread(target=reader,daemon=True);thread.start()
 try:
  while p.poll() is None:
   text=''.join(lines);bad=first_failure(text)
   if bad:raise RuntimeError('Cold fixture FIRST: '+bad)
   if re.search(r'PREGEN-ACCEPTANCE-DONE .*chunks=1(?:\D|$)',text):reason='ADDONFREE_COLD_STRICT_DONE';break
   if 'Done (' not in text and time.monotonic()-started>240:raise TimeoutError('Startup ceiling')
   if time.monotonic()-started>420:raise TimeoutError('Cold phase ceiling')
   time.sleep(.05)
 finally:
  if p.poll() is None:p.stdin.write('stop\n');p.stdin.flush();p.wait(timeout=180)
  thread.join(10)
assert reason and p.returncode==0 and first_failure(logpath.read_text()) is None
(OUT/'addonfree-cold-manifest.json').write_text(json.dumps(inventory(),indent=2))
text=logpath.read_text();assert 'occellreceiptfixture' not in text and re.search(r'Resumed .* job after restart:',text)
assert sha(SERVER/'mods'/jar.name)==digest
rows=list(data('cell_repair_receipts.dat')['receipts']);assert len(rows)==2 and all(int(r['consumed'])==1 for r in rows)
assert {int(r['position']) for r in rows}=={28587302617150,28862180524094}
assert not data('cell_repair_receipts.dat').get('operatorRequests',[])
protected=data('protected_regions.dat');assert any(int(r['z'])==4 and int(r['minX'])<=6<=int(r['maxX']) and int(r['mask'])==15 for r in protected['sealRuns'])
assert any(all(int(b[k])==v for k,v in {'minX':106,'maxX':106,'minY':62,'maxY':62,'minZ':72,'maxZ':72}.items()) for b in protected['protectedRegions'])
assert 'CELL-REPAIR-QUEUED-REFUSED pos=BlockPos{x=106, y=62, z=72}' in text
markers=[json.loads(p.read_text()) for p in (SERVER/'world/oceancanvas-cell-receipts').rglob('*.json')]
assert sum(m.get('consumed') is True for m in markers)==2
assert text.count('SCOPED-CELL-REPAIR build=v253.125.139')==2
profile=audit(SERVER,6,4)
report={'coldStrictDone':True,'sameJar':digest,'nativeAndOperatorConsumed':2,'protectedQueuedRequestRefused':True,'protectedStoneConserved':True,'nativeProfile':profile,'independentWholeVerificationPending':True,'scaleAccepted':False}
(OUT/'fixture-proof.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
