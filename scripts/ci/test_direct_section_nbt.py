import pathlib
import tempfile
import unittest
from direct_section_nbt import section_root_nbt
from nbt_validator import validate_nbt
from offline_region_packer import pack_region
from anvil_region_probe import inspect_region

class DirectSectionNBTTests(unittest.TestCase):
    def test_uniform_sections_are_compact_and_valid(self):
        for block in ("minecraft:stone","minecraft:water","minecraft:air"):
            payload=section_root_nbt(-2,[block]*4096)
            self.assertTrue(validate_nbt(payload))
            self.assertLess(len(payload),220)
            self.assertIn(block.encode(),payload)
    def test_mixed_section_is_valid_and_packable(self):
        blocks=["minecraft:stone" if (i>>8)<8 else "minecraft:water" for i in range(4096)]
        payload=section_root_nbt(0,blocks)
        self.assertTrue(validate_nbt(payload))
        self.assertIn(b"data",payload)
        with tempfile.TemporaryDirectory() as td:
            p=pathlib.Path(td)/"r.0.0.mca"
            pack_region({(0,0):payload},p)
            self.assertEqual(inspect_region(p,decompress=True)["chunk_count"],1)
    def test_invalid_height_and_size(self):
        with self.assertRaises(ValueError):section_root_nbt(0,["minecraft:air"])
        with self.assertRaises(Exception):section_root_nbt(128,["minecraft:air"]*4096)
if __name__=="__main__":unittest.main()
