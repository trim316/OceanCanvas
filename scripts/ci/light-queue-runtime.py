"""Bounded capacity-retention companion on a verified copied 6b34 checkpoint; never acceptance."""
import re,hashlib,json,pathlib,shutil,subprocess,threading,time,zipfile,sys,os
root=pathlib.Path.cwd();sys.path.insert(0,str(root/'engine-source/scripts/ci'))
from checkpoint_bundle import verify_manifest,write_manifest,archive_checkpoint
from runtime_support import prepare_server,cached_download
from candidate_verifier import verify_candidate
mode=os.environ['LIGHT_QUEUE_MODE'];assert mode in ('control','retain')
out=root/'light-queue-evidence';out.mkdir(exist_ok=False)
(out/'diagnostic-only.json').write_text(json.dumps(dict(certificationEligible=False,releaseVerdict='HOLD',freshWorld=False,purpose='compare native light queue capacity retention on independently verified saved copy',seconds=600,mode=mode)))
parent=root/'parent-checkpoint';parent.mkdir()
with zipfile.ZipFile(root/'retained/saved-checkpoint.zip') as z:z.extractall(parent)
identity=json.loads((parent/'checkpoint.json').read_text());manifest=verify_manifest(parent,identity)
assert identity['sourceCommit']=='6b34f2f0f1b618742946ad11630ecf9228695fcc'
assert identity['jarSha256']=='fe91faca308eec7b004defc8d4e33d8265beb1b53fc018bfd9961074eecad173'
assert identity['profile']=='OVERNIGHT' and identity['targetChunks']==98596
assert json.loads((parent/'checkpoint-timing.json').read_text())['startedUtc']=='2026-10-15T18:05:25.284934+00:00'
shutil.copytree(parent,out,dirs_exist_ok=True);verify_manifest(out,identity)
jar,actual=verify_candidate(root/'production-candidate','v253.125.80',identity['sourceCommit']);assert actual==identity['jarSha256']
run=out/'server';prepare_server(run,root/'.runtime-cache');(run/'mods').mkdir(exist_ok=True)
shutil.copy2(jar,run/'mods'/jar.name)
cached_download('https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.156.0+26.2/fabric-api-0.156.0+26.2.jar',run/'mods/fabric-api.jar',root/'.runtime-cache/downloads')
shutil.copy2(root/'light-queue-probe.jar',run/'mods/light-queue-probe.jar')
(run/'eula.txt').write_text('eula=true\n')
(run/'server.properties').write_text('level-name=world\nlevel-seed=4182026\nonline-mode=false\nserver-ip=127.0.0.1\nview-distance=2\nsimulation-distance=2\npause-when-empty-seconds=-1\nmax-tick-time=120000\n')
(run/'config').mkdir(exist_ok=True)
(run/'config/oceancanvas.properties').write_text('canvasSize=20000\ncenterX=0\ncenterZ=0\npregenEnabled=true\noceanFloorY=-25\nbackupEnabled=true\n')
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
(out/'identity.json').write_text(json.dumps(dict(engine=identity,probeSha256=sha(root/'light-queue-probe.jar'),mode=mode,javaMaxHeapGiB=14,freshWorld=False,releaseVerdict='HOLD',certificationEligible=False),indent=2))
lines=[]
proc=subprocess.Popen(['java','-Doceancanvas.test.retainLightQueues='+str(mode=='retain').lower(),'-Xms1G','-Xmx14G','-jar','fabric-server-launch.jar','nogui'],cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
def collect():
 with (out/'console.log').open('w',encoding='utf-8') as f:
  for line in proc.stdout:
   lines.append(line);f.write(line)
   if 'CARVE_PRESSURE' in line or 'LIGHT_QUEUE' in line or '/ERROR' in line:print(line,end='',flush=True)
th=threading.Thread(target=collect,daemon=True);th.start()
def command(s):proc.stdin.write(s+'\n');proc.stdin.flush()
def wait(marker,seconds):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if any(marker in x for x in lines):return
  if proc.poll() is not None:raise RuntimeError('Server exited early')
  time.sleep(.2)
 raise TimeoutError(marker)
try:
 wait('Done (',300);wait('Resumed ',120)
 deadline=time.monotonic()+600
 while time.monotonic()<deadline:
  if proc.poll() is not None:raise RuntimeError('Server exited during observation')
  time.sleep(.5)
finally:
 if proc.poll() is None:
  try:command('save-all flush');command('stop');proc.wait(timeout=180)
  except Exception:proc.kill();proc.wait(timeout=30)
 th.join(timeout=15)
 (out/'checkpoint-status.json').write_text(json.dumps(dict(serverExit=proc.returncode,completionObserved=False,savedWorldPresent=(run/'world/level.dat').is_file(),scope='diagnostic-only-not-certification')))
 if proc.returncode==0:write_manifest(out,identity);archive_checkpoint(out,identity)
 verify_manifest(parent,identity)
 assert sha(run/'mods'/jar.name)==actual
assert proc.returncode==0
assert sum('CARVE_PRESSURE_SNAPSHOT' in x for x in lines)>=2
snapshots=[x for x in lines if 'LIGHT_QUEUE_SAMPLE' in x]
assert len(snapshots)>=2
assert any('retain='+str(mode=='retain').lower() in x for x in snapshots)
assert any(int(re.search(r'marked=(\d+)',x).group(1))>0 for x in snapshots)
assert any(int(re.search(r'resizedArrayBytes=(\d+)',x).group(1))>0 for x in snapshots)
print('LIGHT_QUEUE_DIAGNOSTIC_COMPLETE HOLD',flush=True)
