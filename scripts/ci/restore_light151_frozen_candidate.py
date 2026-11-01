from pathlib import Path
import json,hashlib,zipfile,shutil,subprocess,xml.etree.ElementTree as E
from candidate_verifier import verify_candidate
parent=Path('verified-light151-build');binding=json.loads(Path('scripts/ci/light151-build-parent.json').read_text())
assert {p.relative_to(parent).as_posix() for p in parent.rglob('*') if p.is_file()}==set(binding['files'])
for n,h in binding['files'].items():assert hashlib.sha256((parent/n).read_bytes()).hexdigest()==h,n
rs=[E.parse(p).getroot() for p in (parent/'production-src/build/test-results/test').glob('TEST-*.xml')]
assert {k:sum(int(r.get(k,0)) for r in rs) for k in ['tests','failures','errors']}=={'tests':334,'failures':0,'errors':0}
subprocess.run(['git','diff','--exit-code','94db18c94a12f0d15822f98d3f8de8cc36f74291','--','production-src'],check=True)
bundle=Path('scripts/ci/frozen/light151-local-candidate.zip');assert hashlib.sha256(bundle.read_bytes()).hexdigest()=='c4fe7e7c6a9eee82c5bb82bfbbee02b1b3ae9b39c59e7b9fc75e468904559a00'
candidate=Path('production-candidate');assert not candidate.exists();candidate.mkdir()
with zipfile.ZipFile(bundle) as z:
 assert set(z.namelist())=={'oceancanvas-26.2-v253.125.151.jar','candidate.properties','JAR-SHA256.txt','binary-provenance.json'}
 z.extractall(candidate)
jar,digest=verify_candidate(candidate,'v253.125.151','94db18c94a12f0d15822f98d3f8de8cc36f74291')
assert digest=='66b9dc2fd7bb02b9b3454a34ae93b49e2ba84685bdf12380e20054971283fd40'
shutil.copytree(parent/'production-src/build/test-results/test','production-src/build/test-results/test')
shutil.copyfile(parent/'production-build.log','production-build.log')
print(json.dumps({'candidateBinaryOrigin':'already-tested local151','jarSha256':digest,'reusedHostedTestsRunId':41287703012,'productBuildRerun':False,'nativeFieldAcceptance':False}))
