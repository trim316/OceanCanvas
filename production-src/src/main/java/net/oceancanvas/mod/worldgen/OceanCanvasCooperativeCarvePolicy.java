package net.oceancanvas.mod.worldgen;

/**
 * Destructive chunk authoring is atomic with respect to process restart.
 *
 * <p>The former wall-budget yield returned after an arbitrary completed column and
 * relied on an in-memory {@code cooperativeCarvePending} marker to force a rescan.
 * The preserved 1k campaign disproved that assumption: chunk -58,-45 yielded after
 * 118 columns, the process checkpointed, and the transient marker did not survive.
 * The untouched tail still contained lava above the canonical surface; on a later
 * restart it flowed into already-authored water and produced a stone cell that the
 * LIGHT_ONLY finalizer correctly refused to rewrite. A partial destructive carve
 * therefore cannot be a scheduler-only state unless its exact cursor/authority is
 * durably journaled before mutation.
 *
 * <p>Until such a durable per-chunk cursor exists, never yield between columns.
 * Existing outer admission/backpressure and native-drain fences still bound when a
 * new chunk may begin; once it begins, it reaches the existing full-chunk finalize
 * boundary before restart can mistake a transient partial carve for completed
 * terrain. This is intentionally a correctness-first release fix, not a throughput
 * optimization.
 */
final class OceanCanvasCooperativeCarvePolicy {
    private OceanCanvasCooperativeCarvePolicy() {}

    static boolean shouldYield(boolean unstructuredWholeOwned, int completedColumns,
            long elapsedNs, long existingBudgetNs) {
        return false;
    }
}
