from pathlib import Path
import zipfile,hashlib,subprocess,json
from candidate_verifier import verify_candidate
subprocess.run(['git','diff','--exit-code','0f3be95f7da782763ee2f8aa7417427a2338faf8','--','production-src'],check=True)
p=Path('scripts/ci/frozen/light154-local-candidate.zip');assert hashlib.sha256(p.read_bytes()).hexdigest()=='17a06b5ae23f455157a44d34cd4e94d01130d850fe75c4f5dda9ae52d4a8f433'
assert not Path('production-candidate').exists()
with zipfile.ZipFile(p) as z:
 for n in z.namelist():assert not Path(n).is_absolute() and '..' not in Path(n).parts
 z.extractall('.')
jar,digest=verify_candidate(Path('production-candidate'),'v253.125.154','0f3be95f7da782763ee2f8aa7417427a2338faf8');assert digest=='888a6f8381546206c886f2cc0771f6bbf6ac2f385ad80709f3dce90519f8c6c6'
print(json.dumps({'origin':'local154 changed source build348PASS','jarSha256':digest,'hostedBuildRerun':False}))
