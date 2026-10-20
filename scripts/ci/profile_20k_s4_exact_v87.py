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
req=urllib.request.Request('https://api.github.com/repos/trim316/OceanCanvas/actions/artifacts/12281494161/zip',headers={'Authorization':'Bearer '+os.environ['GH_TOKEN']})
with urllib.request.urlopen(req,timeout=180) as response,parent.open('wb') as out:shutil.copyfileobj(response,out)
with parent.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()=='8820804c5db0ba9c74e01141056b9bc2786726dea8abb2c0ffe0d85f60ae0443'
with zipfile.ZipFile(parent) as archive:
    manifest=json.loads(archive.read('checkpoint-manifest.json'))
    identity=json.loads(archive.read('checkpoint.json'));timing=json.loads(archive.read('checkpoint-timing.json'));status=json.loads(archive.read('checkpoint-status.json'))
    assert identity==manifest['identity'] and identity['targetBlocks']==20000 and identity['targetChunks']==1562500
    assert identity['sourceCommit']=='299a2346e54101444e4beb8b1147a9f2aa7d4b3c' and identity['jarSha256']=='d15d0de2a14a894134408e17f5a00fa7c1133fa6f4b39d8385a2e0b6287fbb2e'
    assert timing['startedUtc']=='2026-10-19T20:52:44.316874+00:00' and status['serverExit']==0 and status['segmentPending'] is False
    previous={'candidateSourceCommit':identity['sourceCommit'],'candidateJarSha256':identity['jarSha256'],'noProgressWarnings':['parent failed with heap97.34percent/no-progress61..77sec; NOT waived']}
    assert {n for n in archive.namelist() if n.startswith('server/world/') and not n.endswith('/')}=={n for n in manifest['files'] if n.startswith('server/world/')}
    for name,digest in manifest['files'].items():
        assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
        with archive.open(name) as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest,name
    # The authenticated complete ZIP also binds runtime/cache/log files omitted by the world checkpoint manifest.
    bound_files={}
    for name in archive.namelist():
        if not name.startswith('server/') or name.endswith('/'):continue
        assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
        with archive.open(name) as stream:bound_files[name]=hashlib.file_digest(stream,'sha256').hexdigest()
    (root/'derived-input-server-manifest.json').write_text(json.dumps({'archiveSha256':'8820804c5db0ba9c74e01141056b9bc2786726dea8abb2c0ffe0d85f60ae0443','originalManifestFiles':len(manifest['files']),'files':bound_files},indent=2))
    for name in archive.namelist():
        if not name.startswith('server/') or name.endswith('/'):continue
        target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
        with archive.open(name) as source,target.open('wb') as output:shutil.copyfileobj(source,output)
    for name,digest in bound_files.items():
        with (root/name).open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest,name
jar=next((server/'mods').glob('oceancanvas-*.jar'))
assert hashlib.sha256(jar.read_bytes()).hexdigest()==previous['candidateJarSha256']
candidate_identity={}
if mode=='candidate':
    subprocess.run(['git','diff','--exit-code','b84d8b499e3447d6d338f8e2fdf4b9c85903b867','--','production-src'],check=True)
    gate=Path('accepted-fresh-1k.zip')
    req=urllib.request.Request('https://api.github.com/repos/trim316/OceanCanvas/actions/artifacts/12274613966/zip',headers={'Authorization':'Bearer '+os.environ['GH_TOKEN']})
    with urllib.request.urlopen(req,timeout=180) as response,gate.open('wb') as out:shutil.copyfileobj(response,out)
    with gate.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()=='2a651da51a0591259ca44c313138bed00ce66dd11e2823925336fba45a4a514d'
    with zipfile.ZipFile(gate) as accepted:
        prefix='production-runtime-evidence/'
        acceptedIdentity=json.loads(accepted.read(prefix+'identity.json'))
        assert acceptedIdentity['sourceCommit']=='821ff3102d4a862482fc6dbc26c1900844359850' and acceptedIdentity['freshWorld'] is True
        assert json.loads(accepted.read(prefix+'acceptance.json'))['passed'] is True
        assert json.loads(accepted.read(prefix+'restart.json'))['passed'] is True
        assert json.loads(accepted.read(prefix+'vegetation-persistence.json'))['passed'] is True
        selected=[n for n in accepted.namelist() if n.startswith(prefix+'server/mods/oceancanvas-') and n.endswith('.jar')]
        assert len(selected)==1
        candidate=root/'oceancanvas-26.2-v253.125.87.jar'
        candidate.write_bytes(accepted.read(selected[0]))
    digest=hashlib.sha256(candidate.read_bytes()).hexdigest()
    assert digest==acceptedIdentity['jarSha256']=='d6780ef100a670a537eab8807af102c350ee3a245830619b0b1996aa746eca22'
    jar.unlink();shutil.copy2(candidate,server/'mods'/candidate.name);jar=server/'mods'/candidate.name
    candidate_identity={'candidateSourceCommit':'b84d8b499e3447d6d338f8e2fdf4b9c85903b867','candidateJarSha256':digest,'freshGateArtifactId':12274613966,'freshGateSource':acceptedIdentity['sourceCommit']}
properties=server/'server.properties'
properties.write_text(properties.read_text()+'\nserver-port=25600\n',encoding='utf8')
evidence={'scope':'controlled copy of verified FAILED 20k segment4; exact v87 binary upgrade experiment ONLY; not campaign acceptance',
 'parentArtifactId':12281494161,'parentDiagnosticSource':previous['candidateSourceCommit'],'parentDiagnosticJar':previous['candidateJarSha256'],'parentDiagnosticNoProgressWarnings':previous['noProgressWarnings'],'parentIdentity':identity,'originalTiming':timing,'parentStatus':status,
 'fixtureHeapGiB':14,'parentHeapGiB':14,'sameHeapThroughputComparisonAllowed':False,
 'productBinaryUpgrade':mode=='candidate','profileMode':mode,**candidate_identity,'acceptancePassed':False,'fixtureStartedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}
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
        stop=time.monotonic()+3600;next_diagnostics=0
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
