import pathlib
import tempfile
import unittest
from offline_region_packer import pack_region
from region_receipts import receipt,verify_receipt

NBT=b"\x0a\x00\x00\x00"
class ReceiptTests(unittest.TestCase):
    def test_physical_tamper_and_missing_file(self):
        with tempfile.TemporaryDirectory() as td:
            p=pathlib.Path(td)/"r.0.0.mca"
            pack_region({(0,0):NBT},p)
            record=receipt(p)
            self.assertTrue(verify_receipt(p,record))
            self.assertFalse(record["minecraft_verified"])
            with p.open("r+b") as f:
                f.seek(8192+6);f.write(b"X")
            self.assertFalse(verify_receipt(p,record))
            p.unlink()
            with self.assertRaises(FileNotFoundError):verify_receipt(p,record)
    def test_no_forged_verification(self):
        with tempfile.TemporaryDirectory() as td:
            p=pathlib.Path(td)/"r.0.0.mca"
            pack_region({(0,0):NBT},p)
            with self.assertRaises(ValueError):receipt(p,minecraft_verified=True)
            forged=receipt(p);forged["minecraft_verified"]=True
            with self.assertRaises(ValueError):verify_receipt(p,forged)
if __name__=="__main__":unittest.main()
