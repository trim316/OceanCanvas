import pathlib
import struct
import tempfile
import unittest
import zlib
from anvil_region_probe import inspect_region, RegionError, SECTOR

def region(payload=b"hello", offset=2, count=1, codec=2):
    blob = bytearray(SECTOR * 3)
    blob[0:4] = ((offset << 8) | count).to_bytes(4, "big")
    encoded = zlib.compress(payload) if codec == 2 else payload
    struct.pack_into(">I", blob, offset*SECTOR, len(encoded)+1)
    blob[offset*SECTOR+4] = codec
    blob[offset*SECTOR+5:offset*SECTOR+5+len(encoded)] = encoded
    return blob

class RegionProbeTests(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory()
        self.path = pathlib.Path(self.t.name) / "r.0.0.mca"
    def tearDown(self): self.t.cleanup()
    def inspect(self, data, **kwargs):
        self.path.write_bytes(data)
        before = self.path.read_bytes()
        result = inspect_region(self.path, **kwargs)
        self.assertEqual(before, self.path.read_bytes())
        return result
    def test_valid(self):
        self.assertEqual(self.inspect(region(), decompress=True)["chunk_count"], 1)
    def test_truncated_file(self):
        with self.assertRaises(RegionError): self.inspect(region()[:-1])
    def test_invalid_sector(self):
        with self.assertRaises(RegionError): self.inspect(region(offset=1))
    def test_overlapping_slots(self):
        data = region()
        data[4:8] = data[0:4]
        with self.assertRaises(RegionError): self.inspect(data)
    def test_invalid_length(self):
        data = region()
        struct.pack_into(">I", data, 2*SECTOR, 5000)
        with self.assertRaises(RegionError): self.inspect(data)
    def test_bad_compression(self):
        with self.assertRaises(RegionError): self.inspect(region(codec=7))
    def test_corrupt_zlib(self):
        data = region()
        data[2*SECTOR+5] ^= 0xff
        with self.assertRaises(RegionError): self.inspect(data, decompress=True)
    def test_external_header(self):
        data = region()
        data[2*SECTOR+4] = 0x82
        struct.pack_into(">I", data, 2*SECTOR, 1)
        self.assertTrue(self.inspect(data)["chunks"][0]["external"])

if __name__ == "__main__": unittest.main()
