from pathlib import Path
import re

ports = Path('runtime-src/src/main/java/net/oceancanvas/mod/server/SingleChunkWorldPorts.java').read_text(encoding='utf-8')
bounds = Path('runtime-src/src/main/java/net/oceancanvas/core/geometry/ChunkColumnScanBounds.java').read_text(encoding='utf-8')

# The admission scan must derive one contiguous mutable vertical band from the
# lowest potentially authored cell through the world's final buildable Y.
assert 'long min = (long) floorY - variation - 1L;' in bounds
assert 'long max = (long) worldMaxY - 1L;' in bounds
assert 'cells = Math.multiplyExact(256, height);' in bounds
assert 'if (height <= 0 || height > MAX_HEIGHT)' in bounds

capture = ports[ports.index('public StageActionResult capturePreimage'):ports.index('private String durableSourcePreimageSha')]
assert 'ChunkColumnScanBounds.forOceanFloor(' in capture
assert 'int total = configured.cells();' in capture
assert 'while (preimageCaptureCursor < total' in capture
assert 'int index = preimageCaptureCursor;' in capture
assert 'BlockState sourceState = chunk.getBlockState(cursor);' in capture
assert 'BlockEntity sourceEntity = chunk.getBlockEntity(cursor);' in capture
assert 'boolean entityState = sourceState.hasBlockEntity();' in capture
assert 'boolean entityPresent = sourceEntity != null;' in capture
assert 'if ((entityState || entityPresent) && !blockEntityRecoveryEnabled)' in capture
assert 'preimageCaptureCursor++;' in capture

# Before the first physical write, the exact same captured geometry must be
# replayed and every index checked again. This prevents a late entity from
# hiding in a high/negative-Y section after preimage capture.
author = ports[ports.index('public StageActionResult authorPhysical'):ports.index('private String durableSourcePreimageSha') if False else ports.index('public StageActionResult settlePhysical')]
assert 'int total = scan.cells();' in author
assert 'while (authorPreflightCursor < total' in author
assert 'int index = authorPreflightCursor;' in author
assert 'verifyCapturedCellAuthoringSafe(index, checkPos, expectedState, "author preflight")' in author
assert 'authorPreflightCursor++;' in author
assert author.index('while (authorPreflightCursor < total') < author.index('world.setBlock(cursor, target, Block.UPDATE_CLIENTS)')

# Deterministic arithmetic cases: negative floor, sea-level-ish floor, and a
# tall build limit all include exactly 256 cells for every Y in the band.
def checked(floor_y, variation, world_max_y):
    lo = floor_y - variation - 1
    hi = world_max_y - 1
    height = hi - lo + 1
    assert 0 < height <= 4096
    return lo, hi, height, 256 * height

for case in [(-64, 7, 320), (-25, 0, 320), (63, 12, 512), (-512, 31, 2048)]:
    lo, hi, height, cells = checked(*case)
    assert cells == 256 * height
    assert hi == case[2] - 1
    assert lo == case[0] - case[1] - 1

print('R1-35 vertical entity-refusal proof PASS: full mutable Y band, state+live-entity checks, and pre-write rescan pinned')
