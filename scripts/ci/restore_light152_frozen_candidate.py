from pathlib import Path
import zipfile,hashlib,subprocess,json
from candidate_verifier import verify_candidate
subprocess.run(['git','diff','--exit-code','370bc4ab9269158f80289cb1143e169134e7a7ea','--','production-src'],check=True)
p=Path('scripts/ci/frozen/light152-local-candidate.zip');assert hashlib.sha256(p.read_bytes()).hexdigest()=='56cbf713b6163434665363d71610da79f923cba6572da9e21c3e671fac79abf9'
assert not Path('production-candidate').exists()
with zipfile.ZipFile(p) as z:
 for n in z.namelist():assert not Path(n).is_absolute() and '..' not in Path(n).parts
 z.extractall('.')
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.152','370bc4ab9269158f80289cb1143e169134e7a7ea');assert digest=='3ae31f1c73f15df10173fc5c79d1404b5b6a6e5e83273dec55f9f8735af8803b'
print(json.dumps({'origin':'local152 changed source build337PASS','jarSha256':digest,'hostedBuildRerun':False}))
