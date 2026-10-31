"""Reuse the exact hosted v138 product build; reject any byte or test-result drift."""
from pathlib import Path,PurePosixPath
import json,hashlib,shutil,sys,xml.etree.ElementTree as E
from candidate_verifier import verify_candidate
parent=Path('verified-v139-cold-proof');binding=json.loads(Path('scripts/ci/v139-cold-proof-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(binding['files'])
for n,h in binding['files'].items():
    assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
    assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
jar,digest=verify_candidate(parent/'production-candidate','v253.125.139',binding['sourceCommit']);assert digest==binding['jarSha256']
rows=[E.parse(p).getroot() for p in (parent/'production-src/build/test-results/test').glob('TEST-*.xml')]
assert sum(int(x.attrib['tests']) for x in rows)==261
assert sum(int(x.attrib['failures'])+int(x.attrib['errors']) for x in rows)==0
assert 'BUILD SUCCESSFUL' in (parent/'production-build.log').read_text()
if '--verify-only' not in sys.argv:
    assert not Path('production-candidate').exists()
    shutil.copytree(parent/'production-candidate','production-candidate')
    shutil.copyfile(parent/'production-build.log','production-build.log')
    shutil.copytree(parent/'production-src/build/test-results/test','production-src/build/test-results/test')
# Recheck the already-passing native fixture OFFLINE; never restart its Minecraft scenario.
import zipfile,io,gzip
import nbtlib
from cell_receipt_native_oracle import audit
fixture=parent/'cell-receipt-fixture-evidence'
for phase in ['immutable-native-mix-before-operator','immutable-pending-receipts-before-cold']:
    manifest=json.loads((fixture/(phase+'-manifest.json')).read_text())
    with zipfile.ZipFile(fixture/(phase+'.zip')) as z:
        assert set(z.namelist())==set(manifest)
        for n,h in manifest.items():assert hashlib.sha256(z.read(n)).hexdigest()==h,n
cold=json.loads((fixture/'addonfree-cold-manifest.json').read_text())
for n,h in cold.items():assert hashlib.file_digest((fixture/n).open('rb'),'sha256').hexdigest()==h,n
ledger=nbtlib.load(fixture/'server/world/dimensions/minecraft/overworld/data/oceancanvas/cell_repair_receipts.dat')['data']
assert len(ledger['receipts'])==2 and all(int(r['consumed'])==1 for r in ledger['receipts']) and not ledger.get('operatorRequests',[])
assert {int(r['position']) for r in ledger['receipts']}=={28587302617150,28862180524094}
log=(fixture/'addonfree-cold.log').read_text();assert 'PREGEN-ACCEPTANCE-DONE build=v253.125.139' in log and 'occellreceiptfixture' not in log and 'CELL-REPAIR-QUEUED-REFUSED pos=BlockPos{x=106, y=62, z=72}' in log
native=audit(fixture/'server',6,4)
if '--verify-only' not in sys.argv:
    dest=Path('reused-cell-receipt-proof');dest.mkdir()
    for n in ['fixture-proof.json','identity.json','addonfree-cold-manifest.json']:shutil.copyfile(fixture/n,dest/n)
    (dest/'independent-reuse.json').write_text(json.dumps({'parentRun':binding['runId'],'parentArtifact':binding['artifactId'],'parentWholeSha256':binding['wholeSha256'],'all327FilesVerified':True,'nativeProfile':native,'nativeScenarioRerun':False,'scaleAccepted':False},indent=2))
print(json.dumps({'scope':'hosted261-product-tests-and-all327-artifact-files','jarSha256':digest,'sourceCommit':binding['sourceCommit'],'runtimeExercised':False,'scaleAccepted':False}))
