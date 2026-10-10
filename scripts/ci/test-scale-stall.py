"""Execute the actual scale wait/collector and fail-only saved checkpoint path."""
import ast
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
from runtime_stall import PhysicalMutationStall

source = Path(__file__).with_name('production-scale.py').read_text()
tree = ast.parse(source)
wait_node = next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name == 'wait_for')
collect_node = next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name == 'collect')
mutation = 'LIGHT-ROOT-CAUSE chunk=-30,31 classification=POST_CERT_PHYSICAL_MUTATION stage=0'
class Clock:
    def __init__(self): self.now = 0
    def monotonic(self): return self.now
    def sleep(self,seconds): self.now += seconds

def wait_scope(out,events,lines=None):
    scope = dict(PhysicalMutationStall=PhysicalMutationStall, time=Clock(), re=re,
                 line_events=events, lines=lines if lines is not None else [line for stamp,line in events],
                 process=SimpleNamespace(poll=lambda:None),
                 shutil=SimpleNamespace(disk_usage=lambda path:SimpleNamespace(free=10*1024**3)),
                 RUN=out/'server', OUT=out, json=json, BUILD='v253.125.70', SOURCE='a'*40,
                 actual='b'*64, RESUME=True, AUTHORED_WIDTH=2032, CHUNKS=16384,
                 command=lambda command:None)
    exec(compile(ast.Module(body=[wait_node],type_ignores=[]),'actual-scale-wait','exec'),scope)
    return scope

with tempfile.TemporaryDirectory() as temporary:
    out = Path(temporary)
    historical = [(i*60,mutation) for i in range(8)]
    # An appended old log may contain prior failure/completion; the live collector
    # emits only currentprocess events and does not read that historical file.
    log = out/'console.log'
    log.write_text('old evidence\nPREGEN-ACCEPTANCE-DONE chunks=16384\n')
    collector_scope = dict(LOG=log, RESUME=True, process=SimpleNamespace(stdout=['current process line\n']),
                           lines=[], line_events=[], re=re, time=Clock(), datetime=datetime, timezone=timezone)
    exec(compile(ast.Module(body=[collect_node],type_ignores=[]),'actual-scale-collector','exec'),collector_scope)
    collector_scope['collect']()
    assert 'old evidence' in log.read_text()
    assert len(collector_scope['line_events']) == 1
    assert 'current process line' in collector_scope['line_events'][0][1]
    assert all('old evidence' not in line and 'PREGEN-ACCEPTANCE-DONE' not in line for _,line in collector_scope['line_events'])
    # A batch of events must retain sixminutes of collectiontime even though
    # the actual wait sees that batch at once with its own clock still at zero.
    scope = wait_scope(out,historical)
    try: scope['wait_for']('PREGEN-ACCEPTANCE-DONE',2,completion_guard=True)
    except RuntimeError as error: assert 'Physical mutation stalled' in str(error)
    else: raise AssertionError('Repeated mutation was accepted')
    evidence = json.loads((out/'stall.json').read_text())
    assert evidence['passed'] is False and evidence['stalledSeconds'] == 360
    assert evidence['sourceCommit'] == 'a'*40 and evidence['jarSha256'] == 'b'*64
    assert evidence['resumed'] is True and evidence['chunk'] == '-30,31'
    scope = wait_scope(out,[(0,mutation),(360,'progress: 1/10 chunks terrain-confirmed'),
                            (361,mutation),(362,'PREGEN-ACCEPTANCE-DONE chunks=16384')])
    scope['wait_for']('PREGEN-ACCEPTANCE-DONE',2,completion_guard=True)
    scope = wait_scope(out,[(0,mutation),(360,'LIGHT-DIAG SUMMARY build=test finalized=2'),
                            (361,mutation),(362,'PREGEN-ACCEPTANCE-DONE chunks=16384')])
    scope['wait_for']('PREGEN-ACCEPTANCE-DONE',2,completion_guard=True)
    scope = wait_scope(out,[(i*60,mutation) for i in range(6)]+[(301,'PREGEN-ACCEPTANCE-DONE chunks=16384')])
    scope['wait_for']('PREGEN-ACCEPTANCE-DONE',2,completion_guard=True)
    # Silence is a normal bounded timeout, not a mutationstall failure.
    scope = wait_scope(out,[(0,mutation)])
    try: scope['wait_for']('PREGEN-ACCEPTANCE-DONE',2,completion_guard=True)
    except TimeoutError: pass
    else: raise AssertionError('Missing completion failed to timeout')
    scope = wait_scope(out,historical+[(500,'PREGEN-ACCEPTANCE-DONE chunks=16384')])
    scope['wait_for']('PREGEN-ACCEPTANCE-DONE',2)
    # An old DONE present only in historicaltext must never satisfy completion.
    scope = wait_scope(out,[],lines=['PREGEN-ACCEPTANCE-DONE chunks=16384'])
    try: scope['wait_for']('PREGEN-ACCEPTANCE-DONE',2,completion_guard=True)
    except TimeoutError: pass
    else: raise AssertionError('Historical completion was accepted')
outer_try = next(n for n in tree.body if isinstance(n,ast.Try))
finally_source = '\n'.join(ast.unparse(n) for n in outer_try.finalbody)
assert finally_source.index("command('save-all flush')") < finally_source.index("command('stop')")
assert 'checkpoint-status.json' in finally_source and 'rmtree' not in finally_source
calls = [n for n in ast.walk(outer_try) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id == 'wait_for']
guarded = [n for n in calls if any(k.arg == 'completion_guard' and ast.literal_eval(k.value) for k in n.keywords)]
assert len(guarded) == 1 and 'PREGEN-ACCEPTANCE-DONE' in ast.unparse(guarded[0])
print('PASS: actual scale completion guard fails stalledfreshoutput only; historical resume log preserved, healthyprogress/completion retained, failure saves checkpoint')
