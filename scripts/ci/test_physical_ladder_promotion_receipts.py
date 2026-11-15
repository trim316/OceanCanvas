#!/usr/bin/env python3
"""Focused fail-closed promotion receipt tests; no Minecraft world is opened."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
os.environ.setdefault("EXPECTED_BUILD", "v253.125.142")
os.environ.setdefault("EXPECTED_SOURCE_COMMIT", "7ca560ce5c9d5375c3ce6fb2fb8a637c8c31004c")
os.environ.setdefault("EXPECTED_JAR_SHA256", "8c4bef84914d945978e48ff4da5c593e549c20250ab1beae3eebd35d0a60f524")
import physical_ladder_controller as ladder

RESTART_SCOPE = "completed-checkpoint-cold-save-restart"
STRUCTURE_SCOPE = "completed-checkpoint-structure-rejoin"

class PromotionReceiptTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.identity = {
            "build": ladder.BUILD, "sourceCommit": ladder.SOURCE,
            "jarSha256": ladder.JAR_SHA, "requestedBlocks": 2000,
            "targetChunks": 15625,
        }
        (self.root / "identity.json").write_text(json.dumps(self.identity))
        self.receipt = {
            "passed": True, "build": ladder.BUILD,
            "sourceCommit": ladder.SOURCE, "jarSha256": ladder.JAR_SHA,
            "requestedBlocks": 2000, "targetChunks": 15625,
            "terrainRerun": False, "scope": RESTART_SCOPE,
        }

    def write(self, name, payload):
        (self.root / name).write_text(json.dumps(payload))

    def test_exact_bound_receipts_allow_existing_proof_categories(self):
        self.write("acceptance.json", {"passed": True})
        self.write("restart.json", self.receipt)
        self.write("locate-after-rejoin.json", dict(self.receipt, scope=STRUCTURE_SCOPE))
        self.assertTrue(all(ladder.promotion_requirements(self.root).values()))

    def test_mismatched_or_missing_fields_refuse_promotion(self):
        changes = {
            "passed": False, "build": "other", "sourceCommit": "other",
            "jarSha256": "other", "requestedBlocks": 4000,
            "targetChunks": 1, "terrainRerun": True,
            "scope": STRUCTURE_SCOPE,
        }
        for key, value in changes.items():
            with self.subTest(key=key):
                self.write("restart.json", dict(self.receipt, **{key: value}))
                self.assertFalse(ladder.bound_completed_receipt(self.root / "restart.json", self.identity, RESTART_SCOPE))
                missing = dict(self.receipt)
                del missing[key]
                self.write("restart.json", missing)
                self.assertFalse(ladder.bound_completed_receipt(self.root / "restart.json", self.identity, RESTART_SCOPE))

    def test_missing_corrupt_and_cross_scope_receipts_refused(self):
        path = self.root / "restart.json"
        self.assertFalse(ladder.bound_completed_receipt(path, self.identity, RESTART_SCOPE))
        path.write_text("{bad json")
        self.assertFalse(ladder.bound_completed_receipt(path, self.identity, RESTART_SCOPE))
        self.write("restart.json", dict(self.receipt, scope=STRUCTURE_SCOPE))
        self.assertFalse(ladder.bound_completed_receipt(path, self.identity, RESTART_SCOPE))

    def test_retained_failure_and_stall_block_even_green_receipts(self):
        self.write("acceptance.json", {"passed": True})
        self.write("restart.json", self.receipt)
        self.write("locate-after-rejoin.json", dict(self.receipt, scope=STRUCTURE_SCOPE))
        for filename, requirement in (("first-failure.json", "noFirstFailure"), ("stall.json", "noStall")):
            with self.subTest(filename=filename):
                (self.root / filename).write_text("{}")
                self.assertFalse(ladder.promotion_requirements(self.root)[requirement])
                (self.root / filename).unlink()

if __name__ == "__main__":
    unittest.main()
