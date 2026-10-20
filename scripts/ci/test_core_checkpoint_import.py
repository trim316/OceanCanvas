"""Recovery-authority and bounded-stop regressions without launching Minecraft."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from unittest.mock import patch
from checkpoint_bundle import write_manifest
from import_core_checkpoint import import_checkpoint

checks = 0
def require(value):
    global checks
    checks += 1
    assert value
def refuses(action):
    global checks
    try: action()
    except (ValueError, FileNotFoundError): checks += 1; return
    raise AssertionError('expected recovery refusal')
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()

with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary); parent = root/'parent'; work = root/'work'
    def write(name, value):
        p=parent/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(value); return p
    world='server/world/'
    write(world+'level.dat','test world')
    write(world+'oceancanvas-core/full-canvas/operation.properties','canvasSize=1024\nnextIndex=2048\n')
    write('server/config/oceancanvas-core.properties','canvasSize=1024\n')
    write('server/server.properties','enable-rcon=true\nrcon.password=fixture-only\n')
    jar=write('candidate/core.jar','immutable candidate')
    paths=['build.gradle','gradle.properties','build/classes/java/main/Core.class','build/resources/main/fabric.mod.json']
    for name in paths: write('runtime-context/'+name,'test '+name)
    context={'schema':1,'sourceCommit':'pinned-source','runtimeMode':'gradle-development-not-installed-jar',
             'javaVersion':'exact-test-java','files':{n:sha(parent/'runtime-context'/n) for n in paths}}
    context_path=write('runtime-context/context.json',json.dumps(context))
    identity={'sourceCommit':'pinned-source','runtimeMode':context['runtimeMode'],
              'candidateJarName':'core.jar','candidateJarSha256':sha(jar),'runtimeContextSha256':sha(context_path)}
    started='2026-10-20T01:54:02.811423+00:00'
    write('checkpoint.json',json.dumps(identity))
    write('checkpoint-timing.json',json.dumps({'identity':identity,'startedUtc':started}))
    write('checkpoint-status.json',json.dumps({'serverExit':0,'phase':'FLATTEN','completionObserved':False,'current':2048,'total':4096}))
    write_manifest(parent,identity)
    for name in ['build.gradle','gradle.properties']:
        target=work/'runtime-src'/name; target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes((parent/'runtime-context'/name).read_bytes())
    before={p.relative_to(parent).as_posix():sha(p) for p in parent.rglob('*') if p.is_file()}
    call=lambda: import_checkpoint(parent,work,identity,started,'exact-test-java')
    refuses(lambda:import_checkpoint(parent,work,identity,started,'different-java'))
    refuses(lambda:import_checkpoint(parent,work,identity,'new-clock','exact-test-java'))
    extra=parent/'server/world/unmanifested';extra.write_text('corrupt inventory')
    refuses(call);extra.unlink()
    jar.write_text('corrupt candidate');refuses(call);jar.write_text('immutable candidate')
    require(not (work/'runtime-src/run').exists())
    require(call()==2048)
    require(json.loads((work/'runtime-src/run/oceancanvas-1k-started.json').read_text())['startedUtc']==started)
    require((work/'runtime-src/build/libs/core.jar').read_bytes()==jar.read_bytes())
    require((work/'hosted-1k-checkpointed-live/runtime-context-evidence/runtime-context/context.json').read_bytes()==context_path.read_bytes())
    require(before=={p.relative_to(parent).as_posix():sha(p) for p in parent.rglob('*') if p.is_file()})
    refuses(call)

spec=importlib.util.spec_from_file_location('live_import_test',Path(__file__).with_name('hosted-1k-checkpointed-live.py'))
live=importlib.util.module_from_spec(spec);spec.loader.exec_module(live)
events=[]
class Process:
    def poll(self):return None
live.initialize=lambda:None
live.launch=lambda label:(Process(),None,'test.log')
live.props=lambda path:{'status':'RUNNING'}
live.count_state=lambda path:({'status':'RUNNING'},2050,4096)
live.heartbeat=lambda *args:events.append(args)
live.clean_stop=lambda *args:events.append('CLEAN_STOP')
live.snapshot=lambda *args:events.append(('SNAPSHOT',args))
with tempfile.TemporaryDirectory() as temporary:
    live.CHECKPOINT=Path(temporary)/'no-prior-checkpoint'
    with patch.dict('os.environ',{'OCEANCANVAS_SEGMENT_SECONDS':'1'}), patch.object(live.time,'monotonic',side_effect=[0,2,2]):
        live.flatten_segment(75,3)
require('CLEAN_STOP' in events)
require(any(isinstance(e,tuple) and 'SEGMENT_PENDING_CLEAN_STOP' in e for e in events))
require(events[-1]==('SNAPSHOT',('FLATTEN',2050,4096,3)))
with tempfile.TemporaryDirectory() as temporary:
    live.full.OUTPUT=Path(temporary)
    with patch.dict('os.environ',{'OCEANCANVAS_RETAINED_RUNTIME':'true'}), \
         patch.object(live.full,'wait_rcon'), patch.object(live.full.subprocess,'Popen') as spawn:
        _,sink,_=live.full.launch('retained.log')
        sink.close()
        require(spawn.call_args.args[0]==['./gradlew','--no-daemon','runServer','-x','compileJava','-x','processResources'])
print('Core checkpoint import / bounded stop: '+str(checks)+' checks PASS')

