"""Exercise durable checkpoint validation without generating another world."""
import json
import ast
import os
from pathlib import Path
import tempfile
from checkpoint_bundle import write_manifest, verify_manifest

def rejected(action):
    try: action()
    except (ValueError, FileNotFoundError): return
    raise AssertionError('Invalid checkpoint accepted')

with tempfile.TemporaryDirectory() as temporary:
    out = Path(temporary)
    world = out / 'server/world'
    world.mkdir(parents=True)
    (world / 'level.dat').write_bytes(b'saved level')
    (world / 'region.mca').write_bytes(b'saved terrain and light')
    identity = dict(build='test', jarSha256='a'*64, sourceCommit='b'*40)
    (out / 'checkpoint.json').write_text(json.dumps(identity))
    (out / 'checkpoint-timing.json').write_text(json.dumps(dict(startedUTC='original start')))
    status = out / 'checkpoint-status.json'
    status.write_text(json.dumps(dict(serverExit=0, completionObserved=False)))
    write_manifest(out, identity)
    verify_manifest(out, identity)
    rejected(lambda: verify_manifest(out, dict(identity, build='different')))
    (world / 'region.mca').write_bytes(b'corrupted')
    rejected(lambda: verify_manifest(out, identity))
    (world / 'region.mca').write_bytes(b'saved terrain and light')
    (world / 'extra').write_bytes(b'unmanifested')
    rejected(lambda: verify_manifest(out, identity))
    (world / 'extra').unlink()
    (world / 'level.dat').unlink()
    rejected(lambda: verify_manifest(out, identity))
    (world / 'level.dat').write_bytes(b'saved level')
    status.write_text(json.dumps(dict(serverExit=-9)))
    rejected(lambda: write_manifest(out, identity))
    assert json.loads((out / 'checkpoint-timing.json').read_text())['startedUTC'] == 'original start'
print('PASS: clean checkpoint roundtrip; wrong binary, tampering, added/missing world files and unclean stop rejected; original timing retained')

# Execute the actual segmented completion wait, including its failure handling.
tree = ast.parse(Path(__file__).with_name('production-scale.py').read_text())
outer = next(n for n in tree.body if isinstance(n, ast.Try))
segment = next(n for n in outer.body if isinstance(n, ast.Try))
class Environment:
    def __init__(self, enabled): self.enabled = enabled
    def get(self, name): return 'true' if self.enabled else None
def timeout(*args, **kwargs): raise TimeoutError('segment ended')
def stall(*args, **kwargs): raise RuntimeError('real engine failure')
def run(enabled, waiter):
    scope = dict(os=type('OS', (), dict(environ=Environment(enabled))), wait_for=waiter,
                 CHUNKS=16384, STAGE_SECONDS=300, segment_saved=False, completion_observed=False)
    try: exec(compile(ast.Module(body=[segment], type_ignores=[]), 'actual-segment', 'exec'), scope)
    except SystemExit as error:
        assert enabled and error.code == 0 and scope['segment_saved'] and not scope['completion_observed']
        return
    except TimeoutError:
        assert not enabled and not scope['segment_saved']
        return
    except RuntimeError:
        assert waiter is stall and not scope['segment_saved']
        return
    raise AssertionError('Expected segment boundary/failure')
run(True, timeout)
run(False, timeout)
run(True, stall)
print('PASS: actual segment boundary saves without claiming completion; ordinary timeout and real failures remain failures')
