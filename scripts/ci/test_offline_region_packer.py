import pathlib
import tempfile
import unittest
from offline_region_packer import pack_region, pack_regions_parallel
from anvil_region_probe import inspect_region
from nbt_validator import validate_nbt, NBTError

# Syntactically valid empty compound only: structural fixture, NOT a playable chunk.
NBT=b"\x0a\x00\x00\x00"

class OfflineRegionPackerTests(unittest.TestCase):
    def test_full_1024_slots_and_integrity(self):
        with tempfile.TemporaryDirectory() as td:
            path=pathlib.Path(td)/"r.0.0.mca"
            result=pack_region({(x,z):NBT for z in range(32) for x in range(32)},path)
            probe=inspect_region(path,decompress=True)
            self.assertEqual(result["chunks"],1024)
            self.assertEqual(probe["chunk_count"],1024)
            self.assertEqual(len({c["sector_offset"] for c in probe["chunks"]}),1024)
            with self.assertRaises(FileExistsError): pack_region({(0,0):NBT},path)
    def test_parallel_deterministic_and_isolated(self):
        regions={(x,z):{(i,j):NBT for j in range(32) for i in range(32)}
                 for x,z in ((0,0),(-1,0),(0,-1),(1,1))}
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            serial=pack_regions_parallel(regions,a,workers=1)
            parallel=pack_regions_parallel(regions,b,workers=4)
            self.assertEqual(serial,parallel)
            for x,z in regions:
                self.assertEqual(inspect_region(pathlib.Path(b)/f"r.{x}.{z}.mca",decompress=True)["chunk_count"],1024)
    def test_nbt_parser_rejects_truncation_trailing_unknown_and_negative_lengths(self):
        self.assertTrue(validate_nbt(NBT))
        malformed=[
            NBT[:-1], NBT+b"garbage", b"\\x0a\\x00\\x00\\x63\\x00",
            b"\\x0a\\x00\\x00\\x07\\x00\\x01x\\xff\\xff\\xff\\xff\\x00",
            b"\\x0a\\x00\\x00\\x09\\x00\\x01x\\x01\\xff\\xff\\xff\\xff\\x00"
        ]
        for item in malformed:
            with self.subTest(item=item),self.assertRaises(NBTError):
                validate_nbt(item)

    def test_rejects_invalid_nbt_and_coords(self):
        with tempfile.TemporaryDirectory() as td:
            p=pathlib.Path(td)/"r.0.0.mca"
            with self.assertRaises(ValueError): pack_region({(32,0):NBT},p)
            with self.assertRaises(ValueError): pack_region({(0,0):b"invalid"},p)
            self.assertFalse(p.exists())

if __name__=="__main__": unittest.main()
