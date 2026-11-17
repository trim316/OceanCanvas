import unittest
from adaptive_section_encoder import AdaptiveSectionEncoder
from optimization_tournament import workload
from direct_section_nbt import section_root_nbt
class AdaptiveTests(unittest.TestCase):
    def test_equivalence(self):
        for mode in ("uniform","mixed","unique"):
            encoder=AdaptiveSectionEncoder(max_templates=3,probe=4)
            for y,blocks,biome in workload(mode,48):
                self.assertEqual(encoder.encode(y,blocks,biome),section_root_nbt(y,blocks,biome))
                self.assertLessEqual(len(encoder.templates),3)
    def test_identity(self):
        encoder=AdaptiveSectionEncoder()
        blocks=("minecraft:water",)*4096
        self.assertEqual(encoder.encode(0,blocks),encoder.encode(0,blocks))
        self.assertEqual(encoder.stats["identity"],1)
    def test_different_biomes_and_heights(self):
        encoder=AdaptiveSectionEncoder()
        blocks=("minecraft:water",)*4096
        for y in (-4,0,15):
            for biome in ("minecraft:ocean","minecraft:deep_ocean"):
                self.assertEqual(encoder.encode(y,blocks,biome),section_root_nbt(y,blocks,biome))
    def test_reject_bad_length(self):
        with self.assertRaises(ValueError):AdaptiveSectionEncoder().encode(0,["minecraft:water"])
if __name__=="__main__":unittest.main()
