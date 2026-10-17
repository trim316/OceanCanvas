"""Regression for the pure fail-only runtime physical-mutation guard."""
import re
import sys
from pathlib import Path
from runtime_stall import PhysicalMutationStall

mutation = 'LIGHT-ROOT-CAUSE chunk=-30,31 classification=POST_CERT_PHYSICAL_MUTATION stage=0'
guard = PhysicalMutationStall()
for now in (0,60,120,180,240,300):
    assert guard.observe(mutation,now) is None
verdict = guard.observe(mutation,360)
assert verdict.chunk == '-30,31' and verdict.repeats == 7
assert guard.observe('ordinary silence marker',800) is None
# Any terrain advancement resets the repetition history.
guard = PhysicalMutationStall()
for now in (0,60,120,180,240,300): guard.observe(mutation,now)
assert guard.observe('progress: 2/10 chunks terrain-confirmed',350) is None
assert guard.observe(mutation,360) is None
# Continued lighting finalization is progress even after terrain completes.
assert guard.observe('LIGHT-DIAG SUMMARY build=test finalized=1',700) is None
assert guard.observe(mutation,800) is None
# Different isolated repaired chunks do not combine into one false stall.
guard = PhysicalMutationStall()
for i in range(10):
    assert guard.observe(mutation.replace('-30,31',f'{i},31'), i*100) is None
# Completion ends detection; later stale repair logs are irrelevant.
guard.observe('PREGEN-ACCEPTANCE-DONE chunks=10',1000)
for i in range(10): assert guard.observe(mutation,1100+i*100) is None
# Only the expected physical-mutation classification counts.
guard = PhysicalMutationStall()
for i in range(10):
    assert guard.observe(mutation.replace('POST_CERT_PHYSICAL_MUTATION','OTHER'),i*100) is None
# Optional real successful log replay, with rollover-safe log-relative times.
if len(sys.argv) > 1:
    guard = PhysicalMutationStall()
    previous = None
    offset = 0
    for line in Path(sys.argv[1]).read_text(errors='replace').splitlines():
        clock = re.match(r'\[(\d+):(\d+):(\d+)\]',line)
        if not clock: continue
        seconds = int(clock[1])*3600+int(clock[2])*60+int(clock[3])
        if previous is not None and seconds < previous: offset += 86400
        previous = seconds
        assert guard.observe(line,seconds+offset) is None, line
    print('PASS: successful real runtime log has no stall verdict')
print('PASS: fail-only repeated same-chunk guard, progress resets, finalizer progress, isolated repairs, completion')

# Execute the actual runtime wait function with timestamped buffered events;
# it must use collector timestamps, not the time the waiting loop sees a batch.
import ast
import json
import tempfile
from types import SimpleNamespace
runtime = ast.parse(Path(__file__).with_name('production-runtime.py').read_text())
wait_node = next(node for node in runtime.body if isinstance(node,ast.FunctionDef) and node.name == 'wait_for')
with tempfile.TemporaryDirectory() as temporary:
    class FakeClock:
        now = 0
        def monotonic(self): return self.now
        def sleep(self,seconds): self.now += seconds
    def actual_wait(events, guarded=True):
        scope = dict(PhysicalMutationStall=PhysicalMutationStall, re=re,
                     time=FakeClock(), line_events=events,
                     lines=[line for stamp,line in events],
                     process=SimpleNamespace(poll=lambda:None),
                     OUT=Path(temporary), json=json,
                     BUILD='build', SOURCE='source', actual='checksum')
        exec(compile(ast.Module(body=[wait_node],type_ignores=[]),'actual-wait','exec'),scope)
        scope['wait_for']('PREGEN-ACCEPTANCE-DONE',2,completion_guard=guarded)
    events = [(i*60,mutation) for i in range(7)]
    try: actual_wait(events)
    except RuntimeError as error: assert 'Physical mutation stalled' in str(error)
    else: raise AssertionError('True repeated mutation did not fail actual wait')
    evidence = json.loads((Path(temporary)/'stall.json').read_text())
    assert evidence['passed'] is False and evidence['stalledSeconds'] == 360
    assert evidence['sourceCommit'] == 'source' and evidence['jarSha256'] == 'checksum'
    assert evidence['chunk'] == '-30,31'
    actual_wait([(0,mutation),(360,'progress: 1/10 chunks terrain-confirmed'),
                 (361,mutation),(362,'PREGEN-ACCEPTANCE-DONE chunks=10')])
    actual_wait([(i*60,mutation) for i in range(6)]+[(301,'PREGEN-ACCEPTANCE-DONE chunks=10')])
    # Guard disabled for other waits: mutation logs cannot abort them.
    actual_wait(events+[(361,'PREGEN-ACCEPTANCE-DONE chunks=10')],guarded=False)
    # Quiet logging alone cannot produce a stall verdict.
    try: actual_wait([(0,mutation)])
    except TimeoutError: pass
    else: raise AssertionError('Missing completion should timeout')
collect = next(node for node in runtime.body if isinstance(node,ast.FunctionDef) and node.name == 'collect')
assert 'line_events.append((time.monotonic(), line))' in ast.unparse(collect)
outer_try = next(node for node in runtime.body if isinstance(node,ast.Try))
finally_source = '\n'.join(ast.unparse(node) for node in outer_try.finalbody)
assert finally_source.index('failed-world.zip') < finally_source.index('profile-summary.txt')
print('PASS: actual completion wait emits source-bound stall failure; healthy/done/unguarded waits preserved; failed world archived before JFR summary')

# Stage-marker-only change: production code is unchanged; this exact source commit is
# intentionally eligible for the bounded disposable 2k gate after the preceding
# exact-head throughput profile passed.
