import unittest
from verified_throughput_gate import evaluate,REQUIRED
class GateTests(unittest.TestCase):
    def good(self,seconds=120):
        return dict(scope="minecraft_end_to_end",verified_chunks=1024,expected_chunks=1024,
                    elapsed_seconds=seconds,**{key:True for key in REQUIRED})
    def test_pass_at_or_above_500(self):
        self.assertTrue(evaluate(self.good())["pass"])
    def test_fail_below_500(self):
        self.assertFalse(evaluate(self.good(130))["pass"])
    def test_reject_structural_fixture(self):
        d=self.good();d["scope"]="structural_only_not_playable_chunks"
        with self.assertRaises(ValueError):evaluate(d)
    def test_reject_missing_restart(self):
        d=self.good();d["restart_verified"]=False
        with self.assertRaises(ValueError):evaluate(d)
    def test_reject_partial(self):
        d=self.good();d["expected_chunks"]=1025
        with self.assertRaises(ValueError):evaluate(d)
    def test_reject_boolean_chunk_count(self):
        d=self.good();d["verified_chunks"]=True
        with self.assertRaises(ValueError):evaluate(d)
if __name__=="__main__":unittest.main()
