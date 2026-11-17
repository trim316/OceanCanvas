import tempfile
import unittest
from pathlib import Path
from region_first_canvas import Canvas
from region_first_builder import build_regions
from anvil_region_probe import inspect_region
class RegionBuilderTests(unittest.TestCase):
    def test_disposable_structural_pack(self):
        # Deliberately NOT a Minecraft playable chunk; only packer integration.
        with tempfile.TemporaryDirectory() as td:
            seen=[]
            def fixture(x,z):
                seen.append((x,z))
                return b"\x0a\x00\x00\x00"
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
    def test_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td);(p/"existing").write_text("safe")
            with self.assertRaises(FileExistsError):
                build_regions(Canvas(0,0,16,16),lambda x,z:b"",p)
if __name__=="__main__":unittest.main()
