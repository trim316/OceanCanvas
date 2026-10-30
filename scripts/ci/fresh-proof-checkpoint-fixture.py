"""New regression: fresh completed authoring -> checkpoint before lighting -> addon-free cold recovery.
Reuses the immutable prior one-chunk fixture world, declares NEW chunk6,4; not a scale verdict.
"""
from pathlib import Path,PurePosixPath
import os,json,hashlib,shutil,subprocess,threading,time,re,zipfile,gzip,io,math
import nbtlib
from candidate_verifier import verify_candidate
from scale_first_failure import first_failure
from fresh_checkpoint_native_oracle import audit

PARENT=Path('fresh-checkpoint-fixture-parent');OUT=Path('fresh-checkpoint-recovery-fixture');SERVER=OUT/'server'
assert not OUT.exists();OUT.mkdir()
binding=json.loads(Path('scripts/ci/fresh-checkpoint-fixture-parent.json').read_text());actual={p.relative_to(PARENT).as_posix() for p in PARENT.rglob('*') if p.is_file()};assert actual==set(binding['files'])
sha=lambda p:hashlib.file_digest(Path(p).open('rb'),'sha256').hexdigest()
for n,h in binding['files'].items():
 assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
 assert sha(PARENT/n)==h,n
oldmanifest=json.loads((PARENT/'addonfree-cold-stopped-manifest.json').read_text())
for n,h in oldmanifest.items():assert sha(PARENT/n)==h,n
shutil.copytree(PARENT/'server',SERVER)
for n,h in oldmanifest.items():assert sha(OUT/n)==h,n
pre=OUT/'parent-evidence';pre.mkdir();shutil.copyfile(PARENT/'identity.json',pre/'identity.json');shutil.copyfile(PARENT/'addonfree-cold-stopped-manifest.json',pre/'stopped-manifest.json')
for p in (SERVER/'mods').glob('oceancanvas-*.jar'):shutil.move(str(p),pre/p.name)
assert not list((SERVER/'mods').glob('*fixture*.jar'))
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.138',os.environ['EXPECTED_SOURCE_COMMIT']);shutil.copyfile(jar,SERVER/'mods'/jar.name)
addon=Path('production-src/build/fixture-libs/fresh-checkpoint-fixture.jar');assert addon.is_file();shutil.copyfile(addon,SERVER/'mods'/addon.name)
key=(6&0xffffffff)|((4&0xffffffff)<<32);historical=(4&0xffffffff)|((4&0xffffffff)<<32);unsealed=(7&0xffffffff)|((4&0xffffffff)<<32)
data_dir=SERVER/'world/dimensions/minecraft/overworld/data/oceancanvas'
def data(name):return nbtlib.load(data_dir/name)['data']
def mask(cx,cz):return next((int(r['mask']) for r in data('protected_regions.dat').get('sealRuns',[]) if int(r['z'])==cz and int(r['minX'])<=cx<=int(r['maxX'])),0)
assert mask(6,4)==0 and mask(7,4)==0 and mask(4,4)==15
assert not data('job_state.dat').get('job',[]) and not data('pregen_recovery_journal.dat').get('journal',[])
(OUT/'identity.json').write_text(json.dumps({'sourceCommit':os.environ['EXPECTED_SOURCE_COMMIT'],'jarSha256':digest,'fixtureJarSha256':sha(addon),'parentArtifactId':binding['artifactId'],'parentWholeSha256':binding['wholeSha256'],'newTarget':[6,4],'oldCompletedTarget':[4,4],'runtimeFlags':['-Xms512M','-Xmx3G','-XX:+UseZGC'],'scope':'specific uncovered fresh-completed-proof checkpoint window; no ladder/scale/performance verdict','scaleAccepted':False},indent=2))
def inventory():return {p.relative_to(OUT).as_posix():sha(p) for p in SERVER.rglob('*') if p.is_file()}
def run_phase(label,hold):
 assert sha(SERVER/'mods'/jar.name)==digest
 logpath=OUT/(label+'.log');lines=[];reason='';exitcode=None
 flags=['java','-Xms512M','-Xmx3G','-XX:+UseZGC']+(['-Doceancanvas.fixture.holdLighting=true'] if hold else [])
 with logpath.open('w') as log:
  p=subprocess.Popen(flags+['-jar','fabric-server-launch.jar','nogui'],cwd=SERVER,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
  def reader():
   for line in p.stdout:lines.append(line);log.write(line);log.flush()
  thread=threading.Thread(target=reader,daemon=True);thread.start();begun=False;started=time.monotonic()
  def command(s):p.stdin.write(s+'\n');p.stdin.flush()
  try:
   while p.poll() is None:
    text=''.join(lines);bad=first_failure(text)
    if bad:raise RuntimeError('Fixture FIRST: '+bad)
    if 'Unknown or incomplete command' in text or 'Incorrect argument' in text:raise RuntimeError('Fixture command refused')
    if 'Done (' in text and not begun:
     if hold:
      command('oceancanvas forever role EXPERIMENT');command('oceancanvas pregen profile overnight');command('oceancanvas pregen start 8 104 72 confirm')
     begun=True
    if hold and 'FRESH-PHYSICAL-CHECKPOINT target=6,4 physical=true lighting=false authority=true' in text:reason='COMPLETED_PHYSICAL_BEFORE_LIGHT';break
    if not hold and re.search(r'PREGEN-ACCEPTANCE-DONE .*chunks=1(?:\D|$)',text):reason='ADDONFREE_COLD_STRICT_DONE';break
    if not begun and time.monotonic()-started>240:raise TimeoutError('Fixture startup ceiling')
    if time.monotonic()-started>420:raise TimeoutError('Fixture phase ceiling')
    time.sleep(.05)
  finally:
   if p.poll() is None:command('stop');p.wait(timeout=180)
   thread.join(10);exitcode=p.returncode
 text=logpath.read_text();assert first_failure(text) is None and exitcode==0
 assert reason
 manifest=inventory();(OUT/(label+'-manifest.json')).write_text(json.dumps(manifest,indent=2))
 return {'reason':reason,'exit':exitcode,'manifestFiles':len(manifest),'scaleAccepted':False}
first=run_phase('completed-physical-checkpoint',True)
assert mask(6,4)&7==7 and mask(6,4)&8==0
journal=data('pregen_recovery_journal.dat')['journal'][0];physical={int(p)&((1<<64)-1) for p in journal['physicalRepairChunks']}
assert key in physical and historical not in physical and unsealed not in physical
first.update(physicalAuthorityPersisted=True,historicalAuthorityExcluded=True,unsealedAuthorityExcluded=True,mask=mask(6,4))
(OUT/'completed-physical-checkpoint-evidence.json').write_text(json.dumps(first,indent=2))
# Immutable pre-cold image: every first-phase byte remains independently verifiable.
manifest=inventory()
with zipfile.ZipFile(OUT/'immutable-completed-physical-checkpoint.zip','x',zipfile.ZIP_DEFLATED,compresslevel=1) as archive:
 for n,h in manifest.items():assert sha(OUT/n)==h;archive.write(OUT/n,n)
shutil.move(str(SERVER/'mods'/addon.name),OUT/addon.name)
assert not list((SERVER/'mods').glob('*fixture*.jar'))
cold=run_phase('addonfree-cold',False);assert mask(6,4)&15==15
assert re.search(r'Resumed .* job after restart:',(OUT/'addonfree-cold.log').read_text())
assert 'ocfreshcheckpointfixture' not in (OUT/'addonfree-cold.log').read_text()
cold.update(mask=mask(6,4),sameExactJar=sha(SERVER/'mods'/jar.name)==digest)
(OUT/'addonfree-cold-evidence.json').write_text(json.dumps(cold,indent=2))
native=audit(SERVER,6,4);(OUT/'native-selected-profile.json').write_text(json.dumps(native,indent=2))
(OUT/'fixture-proof.json').write_text(json.dumps({'first':first,'cold':cold,'completedFreshCheckpointRecovered':True,'nativeSelectedProfile':native,'independentWholeArtifactVerificationPending':True,'scaleAccepted':False},indent=2))
print('FOCUSED-FRESH-CHECKPOINT-PASS: positive authority persisted; addon-free same-jar cold strict done; independent block audit still required',flush=True)
