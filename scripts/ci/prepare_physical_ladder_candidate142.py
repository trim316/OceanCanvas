"""Bind the hosted build to frozen production source, never a release verdict."""
from pathlib import Path
import os, subprocess, shutil, hashlib, json
from candidate_verifier import verify_candidate
import xml.etree.ElementTree as ET
source='7ca560ce5c9d5375c3ce6fb2fb8a637c8c31004c'
subprocess.run(['git','diff','--exit-code',source,'--','production-src'],check=True)
results=[ET.parse(p).getroot() for p in Path('production-src/build/test-results/test').glob('TEST-*.xml')]
assert sum(int(r.attrib['tests']) for r in results)==271
assert sum(int(r.attrib['failures'])+int(r.attrib['errors']) for r in results)==0
root=Path('production-candidate');root.mkdir()
jar=Path('production-src/build/libs/oceancanvas-26.2-v253.125.142.jar')
shutil.copyfile(jar,root/jar.name)
digest=hashlib.sha256(jar.read_bytes()).hexdigest()
(root/'JAR-SHA256.txt').write_text(digest+'  '+jar.name+'\n')
(root/'candidate.properties').write_text(f'sourceCommit={source}\nruntimeBuild=v253.125.142\nbuildRunId={os.environ["GITHUB_RUN_ID"]}\n')
verify_candidate(root,'v253.125.142',source)
print(json.dumps({'sourceCommit':source,'jarSha256':digest,'runtimeBuild':'v253.125.142','acceptancePassed':False}))
