"""Bounded hosted diagnostic copy, not a replacement campaign or acceptance gate."""
from pathlib import Path,PurePosixPath
import shutil,hashlib,json,zipfile,subprocess,threading,time,os,urllib.request

mode=os.environ['PROFILE_MODE'];assert mode in ('baseline','telemetry')
root=Path('profile-'+mode).resolve();assert not root.exists();root.mkdir()
server=root/'server';parent=Path('parent-checkpoint.zip')
class StripAuth(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):return urllib.request.Request(newurl)
urllib.request.install_opener(urllib.request.build_opener(StripAuth()))
assert not parent.exists()
req=urllib.request.Request('https://api.github.com/repos/trim316/OceanCanvas/actions/artifacts/12248132047/zip',headers={'Authorization':'Bearer '+os.environ['GH_TOKEN']})
with urllib.request.urlopen(req,timeout=180) as response,parent.open('wb') as out:shutil.copyfileobj(response,out)
with parent.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()=='c1c2bfd91f3d45ec75006375de609d383eff53cc9c49e5e6a67d7364e95360e5'
with zipfile.ZipFile(parent) as archive:
    manifest=json.loads(archive.read('checkpoint-manifest.json'))
    identity=json.loads(archive.read('checkpoint.json'))
    timing=json.loads(archive.read('checkpoint-timing.json'))
    status=json.loads(archive.read('checkpoint-status.json'))
    assert identity['sourceCommit']=='299a2346e54101444e4beb8b1147a9f2aa7d4b3c'
    assert identity['jarSha256']=='d15d0de2a14a894134408e17f5a00fa7c1133fa6f4b39d8385a2e0b6287fbb2e'
    assert status['serverExit']==0 and status['segmentPending'] is False
    assert timing['startedUtc']=='2026-10-19T20:52:44.385257+00:00'
    for name in archive.namelist():
        if not name.startswith('server/') or name.endswith('/'):continue
        assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
        target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
        with archive.open(name) as source,target.open('wb') as output:
            import shutil
            shutil.copyfileobj(source,output)
    for name,digest in manifest['files'].items():
        if name.startswith('server/'):
            with (root/name).open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest,name
jar=next((server/'mods').glob('oceancanvas-*.jar'))
assert hashlib.sha256(jar.read_bytes()).hexdigest()==identity['jarSha256']
candidate_identity={}
if mode=='telemetry':
    subprocess.run(['git','diff','--exit-code','70c23b28b76daaf52d1e0e795482209f68903e24','--','production-src'],check=True)
    candidate=Path('production-src/build/libs/oceancanvas-26.2-v253.125.82.jar').resolve()
    digest=hashlib.sha256(candidate.read_bytes()).hexdigest()
    jar.unlink();shutil.copy2(candidate,server/'mods'/candidate.name);jar=server/'mods'/candidate.name
    candidate_identity={'candidateSourceCommit':'70c23b28b76daaf52d1e0e795482209f68903e24','candidateJarSha256':digest}
properties=server/'server.properties'
properties.write_text(properties.read_text()+'\nserver-port=25600\n',encoding='utf8')
evidence={'scope':'copied failed5k checkpoint diagnostic, not campaign continuation or acceptance',
 'parentArtifactId':12248132047,'parentIdentity':identity,'originalTiming':timing,'parentStatus':status,
 'fixtureHeapGiB':14,'parentHeapGiB':14,'sameHeapThroughputComparisonAllowed':False,
 'productBinaryUpgrade':mode=='telemetry','profileMode':mode,**candidate_identity,'acceptancePassed':False,'fixtureStartedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}
(root/'identity.json').write_text(json.dumps(evidence,indent=2))
lines=[]
java='java'
(root/'fixture-java-version.txt').write_text(subprocess.check_output([java,'-version'],stderr=subprocess.STDOUT,text=True))
(root/'harness-source.txt').write_text(subprocess.check_output(['git','rev-parse','HEAD'],text=True))
with (root/'console.log').open('w',encoding='utf8') as log:
    process=subprocess.Popen([java,'-Xms1G','-Xmx14G',
      '-XX:StartFlightRecording=filename=stall-copy.jfr,settings=profile,dumponexit=true',
      '-jar','fabric-server-launch.jar','nogui'],cwd=server,stdin=subprocess.PIPE,
      stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',errors='replace')
    def collect():
        for line in process.stdout:lines.append(line);log.write(line);log.flush()
    reader=threading.Thread(target=collect,daemon=True);reader.start()
    def command(value):process.stdin.write(value+'\n');process.stdin.flush()
    try:
        deadline=time.monotonic()+240
        while not any('Done (' in line for line in lines):
            if process.poll() is not None:raise RuntimeError('fixture startup failed; retain evidence')
            if time.monotonic()>deadline:raise TimeoutError('fixture startup')
            time.sleep(.2)
        command('oceancanvas pregen profile overnight')
        stop=time.monotonic()+1200;next_diagnostics=0
        while time.monotonic()<stop:
            if process.poll() is not None:raise RuntimeError('diagnostic process exited')
            if time.monotonic()>=next_diagnostics:
                command('oceancanvas diagnostics');next_diagnostics=time.monotonic()+60
            time.sleep(1)
        command('save-all flush');command('stop');process.wait(timeout=180)
        assert process.returncode==0
    finally:
        if process.poll() is None:
            command('stop');process.wait(timeout=180)
        reader.join(5)
with jar.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==candidate_identity.get('candidateJarSha256',identity['jarSha256'])
evidence['admissionSamples']=[l.strip() for l in lines if 'LIGHT-ADMISSION-REASONS' in l]
evidence['releaseSamples']=[l.strip() for l in lines if 'LIGHT-RELEASE-REASONS' in l]
files={}
for p in sorted(server.rglob('*')):
    if p.is_file():
        with p.open('rb') as stream:files[p.relative_to(root).as_posix()]=hashlib.file_digest(stream,'sha256').hexdigest()
(root/'fixture-manifest.json').write_text(json.dumps({'identity':evidence,'files':files},indent=2))
evidence['fixtureExit']=process.returncode
evidence['diagnosticCollected']=True
evidence['noProgressWarnings']=[l.strip() for l in lines if 'no-progress=FAIL' in l]
evidence['aggregateSamples']=[l.strip() for l in lines if 'LIGHT-DIAG AGGREGATE' in l]
(root/'evidence.json').write_text(json.dumps(evidence,indent=2))
print('Diagnostic copy stopped cleanly; inspect evidence/JFR. No acceptance claim.')
