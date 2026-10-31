"""NEW changed-path fixture: native lava event and explicit operator evidence survive cold recovery.
Uses previously passing immutable R1 world; does not rerun its completed-fresh-proof scenario.
"""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time,re,zipfile,gzip,io
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
from cell_receipt_native_oracle import audit
PARENT=Path('verified-cell-receipt-parent');OUT=Path('cell-receipt-fixture-evidence');SERVER=OUT/'server'
assert not OUT.exists();OUT.mkdir()
binding=json.loads(Path('scripts/ci/physical-radius-1k-v138-r1-proof-parent.json').read_text())
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
assert {p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()}==set(binding['files'])
for n,h in binding['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert sha(PARENT/n)==h,n
old=PARENT/'fresh-checkpoint-recovery-fixture';manifest=json.loads((old/'addonfree-cold-manifest.json').read_text())
for n,h in manifest.items():assert sha(old/n)==h,n
shutil.copytree(old/'server',SERVER);pre=OUT/'parent-evidence';pre.mkdir()
shutil.copyfile(old/'addonfree-cold-manifest.json',pre/'stopped-manifest.json')
for p in (SERVER/'mods').glob('oceancanvas-*.jar'):shutil.move(str(p),pre/p.name)
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.139',os.environ['EXPECTED_SOURCE_COMMIT']);shutil.copyfile(jar,SERVER/'mods'/jar.name)
addon=Path('production-src/build/fixture-libs/cell-receipts-fixture.jar');assert addon.exists();shutil.copyfile(addon,SERVER/'mods'/addon.name)
(OUT/'identity.json').write_text(json.dumps({'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'addonSha256':sha(addon),'parentArtifactId':binding['artifactId'],'parentWholeSha256':binding['wholeSha256'],'scope':'new native-mix and explicit-cell receipt cold integration; not scale acceptance','protectedExactCell':[106,62,72],'scaleAccepted':False},indent=2))
def inventory():return {p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()}
def image(name):
 files=inventory();(OUT/(name+'-manifest.json')).write_text(json.dumps(files,indent=2))
 with zipfile.ZipFile(OUT/(name+'.zip'),'x',zipfile.ZIP_DEFLATED,compresslevel=1) as z:
  for n,h in files.items():assert sha(OUT/n)==h;z.write(OUT/n,n)
 return sha(OUT/(name+'.zip'))
def run_phase(label,mode,failed_archive=None):
 hold=mode in ('native','operator');lines=[];started=time.monotonic();begun=False;reason='';imports_sent=False;positive=negative=None
 flags=['java','-Xms512M','-Xmx3G','-XX:+UseZGC']+(['-Doceancanvas.fixture.holdReceipts=true'] if hold else [])
 logpath=OUT/(label+'.log')
 with logpath.open('w') as log:
  p=subprocess.Popen(flags+['-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
  def reader():
   for line in p.stdout:lines.append(line);log.write(line);log.flush()
  thread=threading.Thread(target=reader,daemon=True);thread.start()
  def command(s):p.stdin.write(s+'\n');p.stdin.flush()
  try:
   while p.poll() is None:
    text=''.join(lines);bad=first_failure(text)
    if bad:raise RuntimeError('Fixture FIRST: '+bad)
    if any(x in text for x in ['Unknown or incomplete command','Incorrect argument','Forever World blocked PREGEN:']):raise RuntimeError('Fixture command refused')
    if 'Done (' in text and not begun:
     if mode=='role':command('oceancanvas forever status');command('oceancanvas forever role EXPERIMENT')
     elif mode=='native':command('oceancanvas pregen profile overnight');command('oceancanvas pregen start 8 104 72 confirm')
     begun=True
    if mode=='role' and 'Forever World role set to EXPERIMENT' in text:reason='EXPLICIT_ROLE_BOUND';break
    ready=re.search(r'CELL-RECEIPT-FIXTURE-READY world=(\S+) operation=(\S+) native=(-?\d+) operator=(-?\d+) protected=(-?\d+)',text)
    if mode=='native' and ready:reason='CONFIRMED_NATIVE_MIX_PENDING_NO_OPERATOR_AUTHORITY';break
    if mode=='operator' and ready and not imports_sent:
     assert failed_archive;world,operation,native,operator,protected=ready.groups()
     imports=SERVER/'world/oceancanvas-cell-repair-imports';imports.mkdir(exist_ok=True)
     for cell,kind in [(int(operator),'positive'),(int(protected),'negative')]:
      payload={'world':world,'operation':operation,'position':cell,'previous':'minecraft:water[level=0]','expected':'minecraft:stone','replacement':'minecraft:water[level=0]','parentArchive':binding['wholeSha256'],'failedArchive':failed_archive}
      raw=json.dumps(payload,separators=(',',':')).encode();h=hashlib.sha256(raw).hexdigest();(imports/(h+'.json')).write_bytes(raw)
      if kind=='positive':positive=h
      else:negative=h
      command('oceancanvas cell-repair import '+h+' confirm')
     imports_sent=True
    if mode=='operator' and imports_sent and 'CELL-RECEIPT-CHECKPOINT-READY nativePending=true operatorPending=true protectedAuthority=false' in text and ('CELL-REPAIR-IMPORT-ACCEPTED evidence='+positive) in text and ('CELL-REPAIR-IMPORT-REFUSED evidence='+negative) in text:reason='NATIVE_AND_OPERATOR_PENDING_PROTECTED_REFUSED';break
    if mode=='cold' and re.search(r'PREGEN-ACCEPTANCE-DONE .*chunks=1(?:\D|$)',text):reason='ADDONFREE_COLD_STRICT_DONE';break
    if not begun and time.monotonic()-started>240:raise TimeoutError('Fixture startup ceiling')
    if time.monotonic()-started>420:raise TimeoutError('Fixture phase ceiling')
    time.sleep(.05)
  finally:
   if p.poll() is None:command('stop');p.wait(timeout=180)
   thread.join(10)
 assert p.returncode==0 and reason
 assert first_failure(logpath.read_text()) is None
 (OUT/(label+'-manifest.json')).write_text(json.dumps(inventory(),indent=2))
 return {'reason':reason,'exit':p.returncode,'positiveDigest':positive,'negativeDigest':negative,'scaleAccepted':False}
role=run_phase('role-rebind','role')
native=run_phase('native-mix-pending','native');failed=image('immutable-native-mix-before-operator')
operator=run_phase('operator-pending-checkpoint','operator',failed);image('immutable-pending-receipts-before-cold')
shutil.move(str(SERVER/'mods'/addon.name),OUT/addon.name);assert not list((SERVER/'mods').glob('*fixture*.jar'))
cold=run_phase('addonfree-cold','cold');text=(OUT/'addonfree-cold.log').read_text();assert 'occellreceiptfixture' not in text and re.search(r'Resumed .* job after restart:',text)
assert sha(SERVER/'mods'/jar.name)==digest
folder=SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas'
data=nbtlib.load(folder/'cell_repair_receipts.dat')['data'];rows=list(data['receipts']);assert len(rows)==2 and all(int(r['consumed'])==1 for r in rows)
protected=nbtlib.load(folder/'protected_regions.dat')['data'];assert any(int(r['z'])==4 and int(r['minX'])<=6<=int(r['maxX']) and int(r['mask'])==15 for r in protected['sealRuns'])
assert any(all(int(b[k])==v for k,v in {'minX':106,'maxX':106,'minY':62,'maxY':62,'minZ':72,'maxZ':72}.items()) for b in protected['protectedRegions'])
markers=[json.loads(p.read_text()) for p in (SERVER/'world/oceancanvas-cell-receipts').rglob('*.json')]
assert sum(m.get('consumed') is True for m in markers)==2
assert text.count('SCOPED-CELL-REPAIR build=v253.125.139')==2
profile=audit(SERVER,6,4)
report={'role':role,'native':native,'operator':operator,'cold':cold,'profile':profile,'consumedReceipts':2,'protectedAuthorityRefused':True,'protectedStoneConserved':True,'sameExactJar':True,'independentWholeVerificationPending':True,'scaleAccepted':False}
(OUT/'fixture-proof.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
