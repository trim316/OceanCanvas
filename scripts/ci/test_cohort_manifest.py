#!/usr/bin/env python3
import pathlib, tempfile, unittest
from cohort_manifest import Cohort

def members(n=4):
    return {f"{i},0": {"protected": False, "preimage_fsynced": True,
                       "preimage_hash": f"pre{i}", "target_hash": f"post{i}"}
            for i in range(n)}

class CohortCrashTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.c = Cohort(pathlib.Path(self.temp.name), "cohort-0")
    def tearDown(self): self.temp.cleanup()
    def test_all_crash_boundaries(self):
        self.c.prepare(members())
        self.assertEqual(self.c.replay_action(), "RESTORE_OR_REVERIFY")
        self.c.authored()
        self.assertEqual(self.c.replay_action(), "RESTORE_OR_REVERIFY")
        with self.assertRaises(ValueError): self.c.durable(False)
        self.assertEqual(self.c.replay_action(), "RESTORE_OR_REVERIFY")
        self.c.durable(True)
        self.assertEqual(self.c.replay_action(), "RESTART_AND_VERIFY")
        with self.assertRaises(ValueError):
            self.c.verify_after_restart(False, lambda *_: True)
        self.assertEqual(self.c.replay_action(), "RESTART_AND_VERIFY")
        self.assertTrue(self.c.verify_after_restart(True, lambda *_: True))
        self.assertEqual(len(self.c.read()["verified"]), 4)
    def test_mismatch_quarantines_without_false_verified(self):
        self.c.prepare(members())
        self.c.authored()
        self.c.durable(True)
        self.assertFalse(self.c.verify_after_restart(True, lambda key, _: key != "2,0"))
        self.assertEqual(self.c.read()["verified"], [])
        self.assertEqual(self.c.replay_action(), "MANUAL_RECOVERY")
    def test_protected_and_missing_preimage_rejected(self):
        m = members(); m["0,0"]["protected"] = True
        with self.assertRaises(ValueError): self.c.prepare(m)
        m["0,0"]["protected"] = False; m["0,0"]["preimage_fsynced"] = False
        with self.assertRaises(ValueError): self.c.prepare(m)
    def test_no_double_commit(self):
        self.c.prepare(members())
        with self.assertRaises(ValueError): self.c.prepare(members())
        with self.assertRaises(ValueError): self.c.durable(True)

if __name__ == "__main__": unittest.main()
