"""Reuse the exact hosted v136 product build; reject any byte or test-result drift."""
from pathlib import Path,PurePosixPath
import json,hashlib,shutil,sys,xml.etree.ElementTree as E
from candidate_verifier import verify_candidate
parent=Path('verified-v136-build');binding=json.loads(Path('scripts/ci/physical-radius-1k-v136-build-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(binding['files'])
for n,h in binding['files'].items():
    assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
    assert hashlib.file_digest((parent/n).open('rb'),'sha256').hexdigest()==h,n
jar,digest=verify_candidate(parent/'production-candidate','v253.125.136',binding['sourceCommit']);assert digest==binding['jarSha256']
rows=[E.parse(p).getroot() for p in (parent/'production-src/build/test-results/test').glob('TEST-*.xml')]
assert sum(int(x.attrib['tests']) for x in rows)==204
assert sum(int(x.attrib['failures'])+int(x.attrib['errors']) for x in rows)==0
assert 'BUILD SUCCESSFUL' in (parent/'production-build.log').read_text()
if '--verify-only' not in sys.argv:
    assert not Path('production-candidate').exists()
    shutil.copytree(parent/'production-candidate','production-candidate')
print(json.dumps({'scope':'hosted204-product-tests-and-all65-artifact-files','jarSha256':digest,'sourceCommit':binding['sourceCommit'],'runtimeExercised':False,'scaleAccepted':False}))
