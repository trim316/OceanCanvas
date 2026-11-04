from pathlib import Path
import zipfile,hashlib,subprocess,json
from candidate_verifier import verify_candidate
subprocess.run(['git','diff','--exit-code','4da1236c522dacc96cffc08a1b2d5ad9629ff8a7','--','production-src'],check=True)
p=Path('scripts/ci/frozen/light153-local-candidate.zip');assert hashlib.sha256(p.read_bytes()).hexdigest()=='4d8eefdb1bc2f8f4ebb4b8258acfc8dce7c8b51a54519afb0438a7f292e0cc6a'
assert not Path('production-candidate').exists()
with zipfile.ZipFile(p) as z:
 for n in z.namelist():assert not Path(n).is_absolute() and '..' not in Path(n).parts
 z.extractall('.')
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.153','4da1236c522dacc96cffc08a1b2d5ad9629ff8a7');assert digest=='708dc57d6b9792df8b0afba46c1fbb83fc55fedc0f2648c7245cb80f62a8a633'
print(json.dumps({'origin':'local153 changed source build341PASS','jarSha256':digest,'hostedBuildRerun':False}))
