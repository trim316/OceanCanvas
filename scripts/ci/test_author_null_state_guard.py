#!/usr/bin/env python3
"""Source-level negative regression for author preimage null-state refusal.

This is not a live Minecraft proof and cannot certify rollback of an existing
FAILED journal or validate registry compatibility.
"""
from pathlib import Path
import re
import unittest

SOURCE = Path("runtime-src/src/main/java/net/oceancanvas/mod/server/SingleChunkWorldPorts.java")

class AuthorNullStateGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text(encoding="utf-8")
        cls.author = source.split("public StageActionResult authorPhysical(ChunkRecord record)", 1)[1].split(
            "public StageActionResult settlePhysical(", 1)[0]

    def test_preimage_capture_rejects_unresolved_registry_state_before_dereference(self):
        source = SOURCE.read_text(encoding="utf-8")
        capture = source.split("public StageActionResult capturePreimage(ChunkRecord record)", 1)[1].split(
            "private String durableSourcePreimageSha()", 1)[0]
        self.assertIn("BlockState recovered = Block.stateById(sourceId);", capture)
        self.assertIn("if (recovered == null || PreimageAdmissionPolicy.refusesStateId(sourceId,", capture)
        self.assertLess(capture.index("recovered == null ||"), capture.index("Block.getId(recovered)"))
        self.assertIn("no terrain mutation authorized", capture)

    def test_preflight_rejects_unmapped_state_before_any_authoring(self):
        self.assertRegex(self.author,
            r"BlockState expectedState = Block\.stateById\(expectedId\);\s*"
            r"if \(expectedId < 0 \|\| expectedState == null \|\| Block\.getId\(expectedState\) != expectedId\)")
        self.assertLess(self.author.index("expectedState == null"), self.author.index("world.setBlock("))
        self.assertIn("no world mutation authorized", self.author)

    def test_author_write_loop_refuses_null_before_verification_or_write(self):
        self.assertRegex(self.author,
            r"if \(expectedSource == null\) \{\s*"
            r'return StageActionResult.failure\("physical authoring cannot resolve')
        self.assertLess(self.author.index("if (expectedSource == null)"),
                        self.author.index("verifyCapturedCellUnchanged(index, cursor"))
        self.assertLess(self.author.index("if (expectedSource == null)"),
                        self.author.index("world.setBlock("))
        self.assertIn("no further world mutation authorized", self.author)

    def test_author_cursor_only_advances_after_verified_cell(self):
        # A failed state lookup or captured-cell comparison cannot skip the
        # failed cell, even when earlier cells were already authored.
        self.assertIn("int index = authorCursor;", self.author)
        self.assertNotIn("int index = authorCursor++;", self.author)
        self.assertLess(self.author.index("verifyCapturedCellUnchanged(index, cursor"),
                        self.author.index("authorCursor++;"))
        self.assertLess(self.author.index("world.setBlock(cursor, target"),
                        self.author.index("authorCursor++;"))

    def test_author_write_requires_acknowledgement_and_readback_before_cursor(self):
        self.assertRegex(self.author,
            r"boolean accepted = world\.setBlock\(cursor, target,")
        self.assertIn("!accepted || !chunk.getBlockState(cursor).equals(target)", self.author)
        self.assertLess(self.author.index("!accepted || !chunk.getBlockState(cursor).equals(target)"),
                        self.author.index("authorCursor++;"))

    def test_restore_write_requires_acknowledgement_and_readback_before_cursor(self):
        source = SOURCE.read_text(encoding="utf-8")
        restore = source.split("public StageActionResult restore(ChunkRecord record)", 1)[1].split(
            "public StageActionResult verifyRestore(", 1)[0]
        self.assertIn("boolean accepted = world.setBlock(cursor, target, restoreFlags);", restore)
        self.assertIn("!accepted || !chunk.getBlockState(cursor).equals(target)", restore)
        self.assertLess(restore.index("!accepted || !chunk.getBlockState(cursor).equals(target)"),
                        restore.index("restoreCursor++;"))

    def test_removing_either_guard_fails_regression(self):
        for guard in ("expectedState == null || ", "if (expectedSource == null)"):
            self.assertIn(guard, self.author)
            mutated = self.author.replace(guard, "", 1)
            self.assertNotEqual(mutated, self.author)
            if guard.startswith("expectedState"):
                self.assertNotRegex(mutated, r"expectedId < 0 \|\| expectedState == null \|\|")
            else:
                self.assertNotIn("if (expectedSource == null)", mutated)

    def test_restore_cursor_only_advances_after_nbt_replay(self):
        source = SOURCE.read_text(encoding="utf-8")
        restore = source.split("public StageActionResult restore(ChunkRecord record)", 1)[1].split(
            "public StageActionResult verifyRestore(", 1)[0]
        self.assertIn("int index = restoreCursor;", restore)
        self.assertNotIn("int index = restoreCursor++;", restore)
        self.assertLess(restore.index("MinecraftBlockEntityNbtCodec.apply(restoredEntity,"),
                        restore.index("restoreCursor++;"))
        self.assertLess(restore.index("immediate block-entity NBT replay verification failed"),
                        restore.index("restoreCursor++;"))
        self.assertLess(restore.index("world.setBlock(cursor, target, restoreFlags);"),
                        restore.index("restoreCursor++;"))

if __name__ == "__main__":
    unittest.main()
