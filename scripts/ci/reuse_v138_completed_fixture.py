"""Reuse the exact hosted v138 product build; reject any byte or test-result drift."""
from pathlib import Path,PurePosixPath
import json,hashlib,shutil,sys,xml.etree.ElementTree as E
from candidate_verifier import verify_candidate
parent=Path('verified-v138-proof');binding=json.loads(Path('scripts/ci/physical-radius-1k-v138-r1-proof-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(binding['files'])
for n,h in binding['files'].items():
    assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
    assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
jar,digest=verify_candidate(parent/'production-candidate','v253.125.138',binding['sourceCommit']);assert digest==binding['jarSha256']
rows=[E.parse(p).getroot() for p in (parent/'production-src/build/test-results/test').glob('TEST-*.xml')]
assert sum(int(x.attrib['tests']) for x in rows)==219
assert sum(int(x.attrib['failures'])+int(x.attrib['errors']) for x in rows)==0
assert 'BUILD SUCCESSFUL' in (parent/'production-build.log').read_text()
if '--verify-only' not in sys.argv:
    assert not Path('production-candidate').exists()
    shutil.copytree(parent/'production-candidate','production-candidate')
    shutil.copyfile(parent/'production-build.log','production-build.log')
    shutil.copytree(parent/'production-src/build/test-results/test','production-src/build/test-results/test')
# Reuse completed native scenario; verify immutable pre-cold authority and final profile offline only.
import zipfile,io,gzip
import nbtlib
from fresh_checkpoint_native_oracle import audit
fixture=parent/'fresh-checkpoint-recovery-fixture'
first=json.loads((fixture/'completed-physical-checkpoint-manifest.json').read_text());cold=json.loads((fixture/'addonfree-cold-manifest.json').read_text())
for n,h in cold.items():assert hashlib.file_digest((fixture/n).open('rb'),'sha256').hexdigest()==h,n
with zipfile.ZipFile(fixture/'immutable-completed-physical-checkpoint.zip') as z:
    assert set(z.namelist())==set(first)
    for n,h in first.items():assert hashlib.sha256(z.read(n)).hexdigest()==h,n
    journal=nbtlib.File.parse(io.BytesIO(gzip.decompress(z.read('server/world/dimensions/minecraft/overworld/data/oceancanvas/pregen_recovery_journal.dat'))))['data']['journal'][0]
    authority={int(x)&((1<<64)-1) for x in journal['physicalRepairChunks']};assert 6+(4<<32) in authority and 4+(4<<32) not in authority and 7+(4<<32) not in authority
log=(fixture/'addonfree-cold.log').read_text();assert 'PREGEN-ACCEPTANCE-DONE build=v253.125.138' in log and 'ocfreshcheckpointfixture' not in log and 'Resumed ' in log
assert not list((fixture/'server/mods').glob('*fixture*.jar'))
native=audit(fixture/'server',6,4)
if '--verify-only' not in sys.argv:
    shutil.copytree(fixture,'fresh-checkpoint-recovery-fixture')
    Path('fresh-checkpoint-recovery-fixture/offline-reverified-native-profile.json').write_text(json.dumps(native,indent=2))
print(json.dumps({'scope':'hosted219-product-tests-and-all286-artifact-files','jarSha256':digest,'sourceCommit':binding['sourceCommit'],'runtimeExercised':False,'scaleAccepted':False}))
