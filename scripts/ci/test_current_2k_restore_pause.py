#!/usr/bin/env python3
"""Focused no-Minecraft regression for the 2k restore PAUSED fail-closed boundary."""
import ast
import json
import pathlib
import types
import unittest
from unittest.mock import Mock

SOURCE = pathlib.Path(__file__).with_name("current-2k-radius-controller.py")
tree = ast.parse(SOURCE.read_text())
restore_node = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "restore")
restore_code = compile(ast.Module(body=[restore_node], type_ignores=[]), str(SOURCE), "exec")

class Clock:
    def __init__(self):
        self.now = 0
    def monotonic(self):
        return self.now
    def sleep(self, seconds):
        self.now += seconds

class RestorePauseTest(unittest.TestCase):
    def exercise(self, states, expected_error=False):
        clock = Clock()
        index = [0]
        def count_state(_):
            i = min(index[0], len(states)-1)
            index[0] += 1
            return {"status":states[i], "reason":"restore safety hold" if states[i]=="PAUSED" else ""}, 9800, 62500
        process = Mock()
        process.poll.return_value = None
        helper = types.SimpleNamespace(
            launch=lambda _: (process, object(), "disposable-restore.log"),
            props=lambda _: {"status":"RUNNING"},
            RESTORE="restore", HEARTBEAT=30,
            count_state=count_state, heartbeat=Mock(),
            clean_stop=Mock(), full=types.SimpleNamespace(rcon=Mock(), assert_command_ok=Mock())
        )
        snapshot = Mock()
        namespace = {"h":helper, "time":clock, "snapshot":snapshot, "json":json}
        exec(restore_code, namespace)
        if expected_error:
            with self.assertRaisesRegex(RuntimeError, "remained PAUSED for 30 seconds.*restore safety hold"):
                namespace["restore"](90, 389)
            snapshot.assert_not_called()
        else:
            namespace["restore"](90, 389)
            snapshot.assert_called_once()
        helper.clean_stop.assert_called_once()
        return helper, snapshot

    def test_persistent_running_to_paused_refuses_checkpoint(self):
        self.exercise(["RUNNING"]+["PAUSED"]*50, expected_error=True)

    def test_transient_pause_then_complete_preserves_normal_checkpoint(self):
        self.exercise(["RUNNING"]+["PAUSED"]*5+["RUNNING","COMPLETE"])

    def test_running_segment_remains_resumable(self):
        self.exercise(["RUNNING"]*60)

if __name__ == "__main__":
    unittest.main()
