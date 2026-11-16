import pathlib
import tempfile
import unittest
from expansion_region_planner import square
from expansion_registry import ExpansionRegistry

IDENTITY = {"seed": "123", "dimension": "minecraft:overworld", "registry": "pinned"}

class RegistryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.reg = ExpansionRegistry(pathlib.Path(self.tmp.name)/"registry.json")
        self.reg.create(square(0,0,32), IDENTITY)
    def tearDown(self): self.tmp.cleanup()
    def test_persistent_expand_and_verify(self):
        plan = self.reg.propose(square(0,0,96),identity=IDENTITY)
        self.assertEqual(len(plan.pending), 32)
        key = next(iter(plan.pending))
        with self.assertRaises(ValueError):
            self.reg.record(*key,"VERIFIED")
        self.reg.record(*key,"VERIFIED",physical_verified=True)
        reopened = ExpansionRegistry(self.reg.path)
        self.assertEqual(reopened.read()["chunks"][f"{key[0]},{key[1]}"],"VERIFIED")
    def test_identity_drift(self):
        with self.assertRaises(ValueError):
            self.reg.propose(square(0,0,96),identity={"seed":"other"})
    def test_pending_blocks_second_expansion(self):
        self.reg.propose(square(0,0,96),identity=IDENTITY)
        with self.assertRaises(ValueError):
            self.reg.propose(square(0,0,128),identity=IDENTITY)
    def test_protected_cannot_downgrade(self):
        self.reg.record(2,2,"PROTECTED")
        with self.assertRaises(ValueError):
            self.reg.record(2,2,"VERIFIED",physical_verified=True)
    def test_corrupt_checksum(self):
        self.reg.path.write_text(self.reg.path.read_text().replace("pinned","changed"))
        with self.assertRaises(ValueError): self.reg.read()
    def test_existing_generated_requires_opt_in(self):
        p=self.reg.propose(square(0,0,96),identity=IDENTITY,already_generated={(2,2)})
        self.assertIn((2,2),p.existing_generated_pending)
        self.assertNotIn((2,2),p.pending)

if __name__=="__main__": unittest.main()
