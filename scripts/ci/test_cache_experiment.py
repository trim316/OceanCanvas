import unittest
from cache_experiment import experiment
class CacheExperimentTests(unittest.TestCase):
    def test_uniform(self):
        x=experiment(16,"uniform")
        self.assertEqual(x["optimized_hits"],14)
        self.assertEqual(x["legacy_hits"],14)
    def test_mixed(self):
        x=experiment(16,"mixed")
        self.assertEqual(x["optimized_hits"],14)
    def test_unique(self):
        x=experiment(16,"unique")
        self.assertEqual(x["optimized_misses"],16)
    def test_invalid(self):
        with self.assertRaises(ValueError):experiment(0)
if __name__=="__main__":unittest.main()
