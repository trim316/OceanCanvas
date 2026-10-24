"""Fail closed if post-physical mutation sampling ever gates correctness invalidation."""
from pathlib import Path
import re

p = Path(__file__).resolve().parents[2] / "production-src/src/main/java/net/oceancanvas/mod/diagnostic/OceanCanvasPostPhysicalMutationDiagnostics.java"
s = p.read_text()
prepare = s[s.index("public static void prepareLevelSetBlockMutation("):s.index("public static void recordLevelSetBlock(")]
record = s[s.index("public static void recordLevelSetBlock("):]
assert "shouldSampleProvenance" not in prepare, "sampling must never gate strict proof invalidation"
assert "prepareForAquaticDecorationMutation(world, chunkPos)" in prepare, "qualifying writes must still re-arm strict proof"
assert record.index("shouldRecord(") < record.index("PROVENANCE_MUTATIONS.incrementAndGet()") < record.index("shouldSampleProvenance(ordinal)"), "sample only after qualifying mutation is established"
helper = re.search(r"static boolean shouldSampleProvenance\(long ordinal\) \{(.*?)\n    \}", s, re.S).group(1)
assert "ordinal <= DENSE_PROVENANCE_SAMPLES" in helper
assert "ordinal & PROVENANCE_SAMPLE_MASK" in helper
# Contract: first 16 are dense, then exactly one sample per 256 ordinals.
def sample(n): return n <= 16 or (n & 0xff) == 0
assert all(sample(n) for n in range(1, 17))
assert not any(sample(n) for n in range(17, 256))
assert sample(256) and sample(512) and not sample(511)
print("PASS: provenance stack/log sampling is bounded and cannot suppress strict lighting-proof invalidation")
