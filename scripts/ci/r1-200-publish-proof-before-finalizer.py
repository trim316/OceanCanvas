from pathlib import Path

p = Path('production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java')
s = p.read_text()
old = '''\t\t\tchunk.markUnsaved();
\t\t\tscheduleLightSync(world, chunk.getPos(), true);
\t\t}

\t\tif (wholeChunkCompletionAllowed(chunk)) {'''
new = '''\t\t\tchunk.markUnsaved();
\t\t\tscheduleLightSync(world, chunk.getPos(), true);
\t\t}

\t\t// R1-200: flattenChunk has just completed the exhaustive synchronous
\t\t// canonical physical-profile proof above. For a currently-owned fresh
\t\t// Pregen target, publish that proof before the asynchronously scheduled
\t\t// lighting finalizer can begin stage 0. This is current-epoch memory only:
\t\t// restart still re-proves, partial-edge operations are excluded, and every
\t\t// later real physical mutation retains the existing invalidation path.
\t\tlong completionPacked = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
\t\tif (wholeChunkCompletionAllowed(chunk)
\t\t\t\t&& pregenSession().PREGEN_TARGET_CHUNKS.contains(completionPacked)) {
\t\t\tlightFinalizerSession().preLightPhysicalAuditComplete.add(completionPacked);
\t\t}

\t\tif (wholeChunkCompletionAllowed(chunk)) {'''
if s.count(old) != 1:
    raise SystemExit(f'expected one publication anchor, found {s.count(old)}')
s = s.replace(old, new)
p.write_text(s)

# Deterministic source assertions: exact placement and unchanged safety budgets.
s = p.read_text()
assert s.index('scheduleLightSync(world, chunk.getPos(), true);') < s.index('R1-200: flattenChunk has just completed')
assert s.index('R1-200: flattenChunk has just completed') < s.index('markChunkProcessedPhysicallyVerified(chunk.getPos());', s.index('R1-200: flattenChunk has just completed'))
assert 'PREGEN_TARGET_CHUNKS.contains(completionPacked)' in s
assert 'preLightPhysicalAuditComplete.add(completionPacked)' in s
assert 'LIGHT_FINALIZER_TICK_TIME_BUDGET_NS = 8_000_000L' in s
assert 'LIGHT_RELIGHT_RESIDENCY_TICKET_MAX = 48' in s
print('R1-200 source transform/regression PASS')
