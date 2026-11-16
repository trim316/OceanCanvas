import unittest
from structural_compiler_benchmark import run

class StructuralCompilerBenchmarkTests(unittest.TestCase):
    def test_one_region_equivalence_and_receipts(self):
        result=run(1,1)
        self.assertEqual(result["chunks"],1024)
        self.assertEqual(result["cache_misses"],2)
        self.assertEqual(result["cache_hits"],1022)
        self.assertFalse(result["minecraft_verified"])
        self.assertGreater(result["output_bytes"],8192)
    def test_parallel_regions(self):
        result=run(2,2)
        self.assertEqual(result["chunks"],2048)
        self.assertEqual(result["cache_hits"],2046)
    def test_reject_invalid_workers(self):
        with self.assertRaises(ValueError):run(1,0)
if __name__=="__main__":unittest.main()
