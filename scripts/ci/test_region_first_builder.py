import tempfile
import unittest
from pathlib import Path
from region_first_canvas import Canvas
from region_first_builder import build_regions
from anvil_region_probe import inspect_region
from direct_section_nbt import named,string,compound,nbt_list
import struct

def fixture_chunk(x,z):
    fields=[named(3,'DataVersion',struct.pack('>i',4000)),named(3,'xPos',struct.pack('>i',x)),named(3,'zPos',struct.pack('>i',z)),named(8,'Status',string('minecraft:full'))]
    fields += [named(9,'sections',nbt_list(10,[])),named(10,'Heightmaps',compound([])),named(10,'structures',compound([])),named(9,'block_entities',nbt_list(10,[])),named(9,'block_ticks',nbt_list(10,[])),named(9,'fluid_ticks',nbt_list(10,[]))]
    return named(10,'',compound(fields))
class RegionBuilderTests(unittest.TestCase):
    def test_disposable_structural_pack(self):
        # Deliberately NOT a Minecraft playable chunk; only packer integration.
        with tempfile.TemporaryDirectory() as td:
            seen=[]
            def fixture(x,z):
                seen.append((x,z))
                return fixture_chunk(x,z)
            manifest=build_regions(Canvas(0,0,64,64),fixture,Path(td)/"output")
            self.assertEqual(sum(v["chunks"] for v in manifest.values()),16)
            self.assertEqual(len(seen),16)
            self.assertEqual(inspect_region(Path(td)/"output/r.0.0.mca",decompress=True)["chunk_count"],16)
    def test_rejects_protected_before_provider(self):
        with tempfile.TemporaryDirectory() as td:
            calls=[]
            def fixture(x,z):
                calls.append((x,z))
                return b"\x0a\x00\x00\x00"
            with self.assertRaises(ValueError):
                build_regions(Canvas(0,0,32,32),fixture,Path(td)/"output",protected={(0,0)})
            self.assertFalse(calls)
    def test_rejects_section_only_and_wrong_coordinates(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaisesRegex(ValueError,"chunk field"):
                build_regions(Canvas(0,0,16,16),lambda x,z:b"\\x0a\\x00\\x00\\x00",Path(td)/"empty")
            with self.assertRaisesRegex(ValueError,"xPos mismatch"):
                build_regions(Canvas(0,0,16,16),lambda x,z:fixture_chunk(x+1,z),Path(td)/"wrong")
    def test_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td);(p/"existing").write_text("safe")
            with self.assertRaises(FileExistsError):
                build_regions(Canvas(0,0,16,16),lambda x,z:b"",p)
if __name__=="__main__":unittest.main()
