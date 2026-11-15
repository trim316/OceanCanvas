#!/usr/bin/env python3
"""Focused, no-Minecraft regression for the exact 1k controller pause gate."""
import ast
import json
import pathlib
import types
import unittest

CONTROLLER = pathlib.Path(__file__).with_name("current-1k-radius-controller.py")

class Clock:
    def __init__(self):
        self.now = 0
    def monotonic(self):
        return self.now
    def sleep(self, seconds):
        self.now += seconds

class FakeProcess:
    def poll(self):
        return None

class Harness:
    FLATTEN = "flatten"
    HEARTBEAT = 30
    def __init__(self, states):
        self.states = list(states)
        self.index = 0
        self.stops = 0
        self.snapshots = []
        self.commands = []
        self.full = types.SimpleNamespace(
            rcon=lambda cmd: self.commands.append(cmd) or "ok",
            assert_command_ok=lambda response, label: None,
        )
    def launch(self, name):
        return FakeProcess(), object(), name
    def props(self, path):
        return {"status": self.states[min(self.index, len(self.states)-1)][0]}
    def count_state(self, path):
        status, count = self.states[min(self.index, len(self.states)-1)]
        self.index += 1
        return {"status": status}, count, 100
    def heartbeat(self, *args):
        pass
    def clean_stop(self, *args):
        self.stops += 1

def load_flatten(harness, clock):
    tree = ast.parse(CONTROLLER.read_text())
    fn = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "flatten")
    module = ast.fix_missing_locations(ast.Module(body=[fn], type_ignores=[]))
    env = {"h": harness, "time": clock, "json": json, "snapshot": lambda *args: harness.snapshots.append(args)}
    exec(compile(module, str(CONTROLLER), "exec"), env)
    return env["flatten"]

class PauseGateTests(unittest.TestCase):
    def test_running_to_persistent_paused_fails_without_checkpoint(self):
        clock = Clock()
        h = Harness([("RUNNING", 1279)] + [("PAUSED", 1279)] * 60)
        with self.assertRaisesRegex(RuntimeError, 'persistedState=.*"status": "PAUSED".*refusing unchanged checkpoint churn'):
            load_flatten(h, clock)(300, 33)
        self.assertEqual(h.stops, 1)
        self.assertEqual(h.snapshots, [])
    def test_transient_pause_then_completion_preserves_success(self):
        clock = Clock()
        h = Harness([("RUNNING", 10)] + [("PAUSED", 10)] * 10 + [("RUNNING", 11), ("COMPLETE", 100)])
        load_flatten(h, clock)(300, 33)
        self.assertEqual(h.stops, 1)
        self.assertEqual(h.snapshots[0][0], "STRUCTURES")
    def test_initial_paused_resume_then_stall_fails(self):
        clock = Clock()
        h = Harness([("PAUSED", 10)] * 60)
        with self.assertRaisesRegex(RuntimeError, "refusing unchanged checkpoint churn"):
            load_flatten(h, clock)(300, 33)
        self.assertEqual(h.commands, ["oceancanvas flatten resume"])
        self.assertEqual(h.snapshots, [])

if __name__ == "__main__":
    unittest.main()
