"""Execute actual setup code against delayed-load command fixtures."""
import ast
from pathlib import Path
import re
from types import SimpleNamespace

tree = ast.parse(Path(__file__).with_name('production-runtime.py').read_text())
fixture = next(node for node in ast.walk(tree) if isinstance(node, ast.If)
               and "OC_WATERFALL_FIXTURE" in ast.unparse(node.test)
               and any(isinstance(n, ast.For) for n in node.body))
commands, lines = [], []
polls = 0
def command(value):
    global polls
    commands.append(value)
    if value.startswith('execute if loaded'):
        polls += 1
        if polls == 3: lines.append('OC-WATERFALL-AREA-LOADED')
    elif value.startswith('setblock'):
        assert polls >= 3, 'Placed source before chunk became resident'
    elif 'OC-WATERFALL-FIXTURE-READY' in value:
        lines.append('OC-WATERFALL-FIXTURE-READY')
def wait_for(pattern, seconds):
    assert any(re.search(pattern,line) for line in lines), 'Fixture was not verified'
scope = dict(os=SimpleNamespace(environ={'OC_WATERFALL_FIXTURE':'true'}),
             WIDTH=128, CENTER_X=-464, CENTER_Z=436, command=command,
             wait_for=wait_for, time=SimpleNamespace(sleep=lambda n:None), lines=lines)
code = compile(ast.Module(body=[fixture],type_ignores=[]),'fixture','exec')
exec(code,scope)
assert polls == 3
assert commands[0].startswith('forceload add')
# A chunk that never becomes loaded must fail without placing any source.
scope['lines'] = []
scope['command'] = lambda value: None
try: exec(code,scope)
except TimeoutError: pass
else: raise AssertionError('Unloaded fixture was incorrectly accepted')
print('PASS: delayed chunk load is awaited; unloaded setup fails before placement')
