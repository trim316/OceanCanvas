"""Bind the hosted build to frozen production source, never a release verdict."""
from pathlib import Path
import os, subprocess, shutil, hashlib, json
from candidate_verifier import verify_candidate
source='3d2e0b73dbf06681b033f042dc65efe51fd40552'
subprocess.run(['git','diff','--exit-code',source,'--','production-src'],check=True)
root=Path('production-candidate');root.mkdir()
jar=Path('production-src/build/libs/oceancanvas-26.2-v253.125.135.jar')
shutil.copyfile(jar,root/jar.name)
digest=hashlib.sha256(jar.read_bytes()).hexdigest()
(root/'JAR-SHA256.txt').write_text(digest+'  '+jar.name+'\n')
(root/'candidate.properties').write_text(f'sourceCommit={source}\nruntimeBuild=v253.125.135\nbuildRunId={os.environ["GITHUB_RUN_ID"]}\n')
verify_candidate(root,'v253.125.135',source)
print(json.dumps({'sourceCommit':source,'jarSha256':digest,'runtimeBuild':'v253.125.135','acceptancePassed':False}))
