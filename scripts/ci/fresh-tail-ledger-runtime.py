"""Pinned production runtime plus a read-only probe; never a release certificate."""
import hashlib,json,os,pathlib,shutil,sys
root=pathlib.Path.cwd()
retained=root/'retained'
identity=json.loads((retained/'identity.json').read_text())
source='d29e8c1716309291bd6d854f8b147f6b4131e7e7'
digest='c66a45d0b2b611d414002c59944358a4a547e418db70f03ba09f93691006331d'
assert identity['sourceCommit']==source and identity['jarSha256']==digest
jar=retained/'server/mods/oceancanvas-26.2-v253.125.80.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
candidate=root/'production-candidate';candidate.mkdir(exist_ok=False)
shutil.copy2(jar,candidate/jar.name)
(candidate/'JAR-SHA256.txt').write_text(digest+'  '+jar.name+'\n')
(candidate/'candidate.properties').write_text('sourceCommit='+source+'\nruntimeBuild=v253.125.80\nreleaseStatus=HOLD\n')
os.environ.update(EXPECTED_BUILD='v253.125.80',EXPECTED_SOURCE_COMMIT=source,
 OC_TEST_WIDTH='1000',OC_CENTER_X='0',OC_CENTER_Z='0',OC_PROFILE='true',
 OC_NATURAL_BORDER='true',OC_STAGE_SECONDS='900')
runtime=root/'engine-source/scripts/ci/production-runtime.py'
text=runtime.read_text()
anchor="shutil.copy2(JAR, RUN / 'mods' / JAR.name)"
assert text.count(anchor)==1
injection="\nshutil.copy2(ROOT / 'focused-vegetation-probe.jar', RUN / 'mods' / 'tail-ledger-probe.jar')\n(RUN / 'run-tail-ledger-probe.flag').write_text('read-only diagnostic\\n')\n(RUN / 'run-tail-ledger-fresh.flag').write_text('fresh diagnostic\\n')"
text=text.replace(anchor,anchor+injection)
sys.path.insert(0,str(runtime.parent))
probe_sha=hashlib.sha256((root/'focused-vegetation-probe.jar').read_bytes()).hexdigest()
error=None
try:
 exec(compile(text,str(runtime),'exec'),{'__name__':'__main__','__file__':str(runtime)})
except Exception as failure:
 error=repr(failure)
 print('FRESH_TAIL_DIAGNOSTIC_OBSERVED_RUNTIME_EXIT',error,flush=True)
finally:
 out=root/'production-runtime-evidence';out.mkdir(exist_ok=True)
 (out/'diagnostic-only.json').write_text(json.dumps(dict(releaseVerdict='HOLD',
  certificationEligible=False,scope='fresh-live-ledger-observation-only',engine=identity,
  probeJarSha256=probe_sha,driverSourceCommit=source,driverExit=error),indent=2)+'\n')
 assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
 assert hashlib.sha256((candidate/jar.name).read_bytes()).hexdigest()==digest
 log=(out/'console.log').read_text()
 assert 'TAIL_LEDGER_SNAPSHOT' in log,'Probe did not produce evidence'
 assert 'TAIL_LEDGER_FAILED' not in log,'Probe failed'
 print('FRESH_TAIL_DIAGNOSTIC_COMPLETE HOLD',flush=True)
