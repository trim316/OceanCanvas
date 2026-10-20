import importlib.util,tempfile,json,hashlib,sys
from pathlib import Path
p=Path(__file__).with_name('hosted-1k-checkpointed-live.py').resolve();sys.path.insert(0,str(p.parent));spec=importlib.util.spec_from_file_location('binary_fixture',p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
assert Path(m.write_manifest.__code__.co_filename).resolve().parent == p.parent
from checkpoint_bundle import verify_manifest
with tempfile.TemporaryDirectory(dir=Path.cwd(),prefix='.binary-retention-test-') as tmp:
 root=Path(tmp);m.PROJECT=root/'project';m.RUN=m.PROJECT/'run';m.WORLD=m.RUN/'world';m.OUTPUT=root/'output';m.LOGDIR=m.OUTPUT/'logs';m.CHECKPOINT=root/'checkpoint';m.STARTED=m.RUN/'started.json';m.BINARY_EVIDENCE=m.OUTPUT/'binary';m.SOURCE='fixture-source';m.IDENTITY['sourceCommit']=m.SOURCE
 files={'gradle.properties':'archives_base_name=fixture\nmod_version=1\n','build.gradle':'fixture build','build/libs/fixture-1.jar':'exact jar','build/classes/java/main/Foo.class':'actual loaded class','build/resources/main/fabric.mod.json':'actual resources'}
 for name,data in files.items():
  q=m.PROJECT/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(data)
 m.initialize();clock=m.STARTED.read_bytes();m.initialize();assert m.STARTED.read_bytes()==clock
 m.WORLD.mkdir();(m.WORLD/'level.dat').write_bytes(b'disposable fixture world')
 m.snapshot('FLATTEN',1,4096,1);manifest=verify_manifest(m.CHECKPOINT,m.IDENTITY)
 required={'candidate/fixture-1.jar','runtime-context/context.json','runtime-context/build/classes/java/main/Foo.class','runtime-context/build/resources/main/fabric.mod.json','server/config/oceancanvas-core.properties','server/server.properties','server/world/level.dat'};assert required<=manifest['files'].keys()
 archive=m.CHECKPOINT/'saved-checkpoint.zip';archive_hash=hashlib.sha256(archive.read_bytes()).hexdigest()
 candidate=m.CHECKPOINT/'candidate/fixture-1.jar';candidate.write_bytes(b'changed candidate')
 try:verify_manifest(m.CHECKPOINT,m.IDENTITY)
 except ValueError:pass
 else:raise AssertionError('candidate corruption accepted')
 candidate.write_bytes(b'exact jar');extra=m.CHECKPOINT/'runtime-context/extra.class';extra.write_bytes(b'unknown')
 try:verify_manifest(m.CHECKPOINT,m.IDENTITY)
 except ValueError:pass
 else:raise AssertionError('unmanifested runtime file accepted')
 extra.unlink()
 config=m.RUN/'config/oceancanvas-core.properties';saved_config=config.read_bytes();config.write_bytes(b'')
 assert m.elapsed() >= 0, 'progress clock must not read a live config file'
 config.write_bytes(saved_config)
 m.FLATTEN=m.WORLD/'oceancanvas-core/full-canvas/operation.properties';m.FLATTEN.parent.mkdir(parents=True);m.FLATTEN.write_text('nextIndex=258\ntotalChunks=4096\nstatus=PAUSED\n')
 def fail(*args):raise RuntimeError('injected segment failure')
 m.LAST_STOP_CLEAN=True
 try:m.run_with_failure_checkpoint('flatten',fail,25,1)
 except RuntimeError as e:assert str(e)=='injected segment failure'
 else:raise AssertionError('failure was certified')
 status=json.loads((m.CHECKPOINT/'checkpoint-status.json').read_text());assert status['current']==258 and status['completionObserved'] is False and status['failure']=='injected segment failure'
 verify_manifest(m.CHECKPOINT,m.IDENTITY)
 failure_hash=hashlib.sha256(archive.read_bytes()).hexdigest();m.LAST_STOP_CLEAN=False
 try:m.run_with_failure_checkpoint('flatten',fail,25,1)
 except RuntimeError:pass
 assert hashlib.sha256(archive.read_bytes()).hexdigest()==failure_hash, 'unclean failure overwrote checkpoint'
 archive_hash=failure_hash
 (m.PROJECT/'build/classes/java/main/Foo.class').write_bytes(b'changed loaded code')
 try:m.bind_runtime_context()
 except RuntimeError:pass
 else:raise AssertionError('implicit runtime code upgrade accepted')
 assert m.STARTED.read_bytes()==clock and hashlib.sha256(archive.read_bytes()).hexdigest()==archive_hash
print('PASS complete world/JAR/runtime/config inventory, exact bindings, corruption/unmanifested-file rejection, code-upgrade rejection, immutable archive, original clock, live-config read isolation and clean-only failure recovery')
