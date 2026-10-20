"""Bounded hosted diagnostic copy, not a replacement campaign or acceptance gate."""
from pathlib import Path,PurePosixPath
import shutil,hashlib,json,zipfile,subprocess,threading,time,os,urllib.request

mode=os.environ['PROFILE_MODE'];assert mode in ('baseline','candidate')
root=Path('profile-'+mode).resolve();assert not root.exists();root.mkdir()
server=root/'server';parent=Path('parent-checkpoint.zip')
class StripAuth(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):return urllib.request.Request(newurl)
urllib.request.install_opener(urllib.request.build_opener(StripAuth()))
assert not parent.exists()
req=urllib.request.Request('https://api.github.com/repos/trim316/OceanCanvas/actions/artifacts/12284193392/zip',headers={'Authorization':'Bearer '+os.environ['GH_TOKEN']})
with urllib.request.urlopen(req,timeout=180) as response,parent.open('wb') as out:shutil.copyfileobj(response,out)
with parent.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()=='adf16c61bd936f2fce0a608852c04b42c9592f3ab5894aa1b28685397045eba5'
with zipfile.ZipFile(parent) as archive:
    manifest=json.loads(archive.read('fixture-manifest.json'))
    previous=json.loads(archive.read('evidence.json'))
    assert previous['fixtureExit']==0 and previous['candidateSourceCommit']=='b84d8b499e3447d6d338f8e2fdf4b9c85903b867'
    assert previous['candidateJarSha256']=='d6780ef100a670a537eab8807af102c350ee3a245830619b0b1996aa746eca22'
    identity=previous['parentIdentity'];timing=previous['originalTiming'];status=previous['parentStatus']
    assert timing['startedUtc']=='2026-10-19T20:52:44.385257+00:00'
    assert {n for n in archive.namelist() if n.startswith('server/') and not n.endswith('/')}==set(manifest['files'])
    for name,digest in manifest['files'].items():
        assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
        with archive.open(name) as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest,name
    for name in archive.namelist():
        if not name.startswith('server/') or name.endswith('/'):continue
        target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
        with archive.open(name) as source,target.open('wb') as output:shutil.copyfileobj(source,output)
    for name,digest in manifest['files'].items():
        with (root/name).open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest,name
jar=next((server/'mods').glob('oceancanvas-*.jar'))
assert hashlib.sha256(jar.read_bytes()).hexdigest()==previous['candidateJarSha256']
candidate_identity={}
assert previous['noProgressWarnings']==[] and previous['freshGateArtifactId']==12274613966
candidate_identity={'candidateSourceCommit':previous['candidateSourceCommit'],'candidateJarSha256':previous['candidateJarSha256'],'freshGateArtifactId':12274613966,'freshGateSource':previous['freshGateSource']}
properties=server/'server.properties'
properties.write_text(properties.read_text()+'\nserver-port=25600\n',encoding='utf8')
evidence={'scope':'continued verified exact-release-v87 diagnostic checkpoint; all earned lighting seals retained; not authoritative campaign or acceptance',
 'parentArtifactId':12284193392,'parentDiagnosticSource':previous['candidateSourceCommit'],'parentDiagnosticJar':previous['candidateJarSha256'],'parentDiagnosticNoProgressWarnings':previous['noProgressWarnings'],'parentIdentity':identity,'originalTiming':timing,'parentStatus':status,
 'fixtureHeapGiB':14,'parentHeapGiB':14,'sameHeapThroughputComparisonAllowed':False,
 'productBinaryUpgrade':False,'profileMode':mode,**candidate_identity,'acceptancePassed':False,'fixtureStartedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}
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
        stop=time.monotonic()+7200;next_diagnostics=0
        while time.monotonic()<stop:
            if process.poll() is not None:raise RuntimeError('diagnostic process exited')
            if time.monotonic()>=next_diagnostics:
                command('oceancanvas diagnostics lighting');command('oceancanvas diagnostics lighting probe -29 -18');command('oceancanvas diagnostics lighting probe -29 -19');next_diagnostics=time.monotonic()+60
            time.sleep(1)
        command('save-all flush');command('stop');process.wait(timeout=180)
        assert process.returncode==0
    finally:
        if process.poll() is None:
            command('stop');process.wait(timeout=180)
        reader.join(5)
with jar.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==candidate_identity.get('candidateJarSha256',previous['candidateJarSha256'])
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
evidence['lightingSummaries']=[l.strip() for l in lines if 'LIGHT-DIAG SUMMARY' in l]

evidence['aggregateSamples']=[l.strip() for l in lines if 'LIGHT-DIAG AGGREGATE' in l]
(root/'evidence.json').write_text(json.dumps(evidence,indent=2))
print('Diagnostic copy stopped cleanly; inspect evidence/JFR. No acceptance claim.')
