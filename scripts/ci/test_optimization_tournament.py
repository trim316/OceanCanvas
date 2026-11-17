import unittest
from optimization_tournament import tournament,workload,serialize
class TournamentTests(unittest.TestCase):
    def test_all_modes(self):
        for mode in ("uniform","mixed","unique"):
            with self.subTest(mode=mode):
                result=tournament(mode,16,1)
                self.assertFalse(result["minecraft_verified"])
                self.assertEqual(len(result["serialization"]),5)
                self.assertEqual(len(result["compression"]),4)
                self.assertGreater(result["combined"]["speedup"],0)
    def test_all_serializers_byte_identical(self):
        for mode in ("uniform","mixed","unique"):
            samples=workload(mode,17)
            direct=serialize(samples,"direct")
            for strategy in ("cache","identity","uniform","adaptive"):
                self.assertEqual(direct,serialize(samples,strategy))
    def test_invalid(self):
        with self.assertRaises(ValueError):tournament("uniform",0,1)
        with self.assertRaises(ValueError):tournament("uniform",1,0)
if __name__=="__main__":unittest.main()
