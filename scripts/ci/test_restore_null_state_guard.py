#!/usr/bin/env python3
"""Fail-closed source regression for the exact restore null-state dereference.

This is a narrow, no-Minecraft guard check, not proof of recovery of an already
FAILED journal or of runtime registry compatibility.
"""
from pathlib import Path
import re
import unittest

SOURCE = Path("runtime-src/src/main/java/net/oceancanvas/mod/server/SingleChunkWorldPorts.java")

class RestoreNullStateGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text(encoding="utf-8")
        cls.restore = cls.source.split("public StageActionResult restore(ChunkRecord record)", 1)[1].split(
            "public StageActionResult ", 1
        )[0]

    def test_preflight_checks_null_before_id_dereference(self):
        pattern = (
            r"BlockState target = Block\.stateById\(id\);\s*"
            r"if \(id < 0 \|\| target == null \|\| Block\.getId\(target\) != id\)"
        )
        self.assertRegex(self.restore, pattern)
        self.assertIn("no restore writes started", self.restore)

    def test_write_loop_checks_null_before_id_dereference(self):
        pattern = (
            r"BlockState target = Block\.stateById\(stateId\);\s*"
            r"if \(target == null \|\| Block\.getId\(target\) != stateId\)"
        )
        self.assertRegex(self.restore, pattern)

    def test_guard_mutations_are_detected(self):
        for original in (
            "id < 0 || target == null || Block.getId(target) != id",
            "target == null || Block.getId(target) != stateId",
        ):
            self.assertIn(original, self.restore)
            mutated = self.restore.replace(original, original.replace("target == null || ", ""), 1)
            self.assertNotEqual(mutated, self.restore)
            if "stateId" in original:
                self.assertNotRegex(mutated, r"if \(target == null \|\| Block\.getId\(target\) != stateId\)")
            else:
                self.assertNotRegex(mutated, r"if \(id < 0 \|\| target == null \|\| Block\.getId\(target\) != id\)")

if __name__ == "__main__":
    unittest.main()
