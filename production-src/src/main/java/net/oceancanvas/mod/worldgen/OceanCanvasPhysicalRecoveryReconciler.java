package net.oceancanvas.mod.worldgen;
import it.unimi.dsi.fastutil.longs.LongArrayList;
/** Original full reconciliation with one captured session pair; no per-key runtime resolution. */
final class OceanCanvasPhysicalRecoveryReconciler {
 static int reconcile(OceanCanvasPregenFlattenerSession pregen, OceanCanvasLightFinalizerSession session) {
		LongArrayList scratch = session.recoveryIterationScratch;
		synchronized (scratch) {
			int repaired = 0;
			scratch.clear();
			session.pendingTicks.forEachKey(scratch::add);
			for (int i = 0; i < scratch.size(); i++) {
				long packed = scratch.getLong(i);
				if (!pregen.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)) continue;
				// Once the exhaustive live physical audit has passed, this recovery entry
				// remains fail-closed lighting debt but must stop occupying the small
				// PHYSICAL admission window. A restart clears this session-only proof.
				if (pregen.PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.contains(packed)) {
					if (pregen.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed)) repaired++;
					continue;
				}
				if (pregen.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.add(packed)) repaired++;
				pregen.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.remove(packed);
				pregen.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.remove(packed);
			}
			scratch.clear();
			pregen.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.forEachLong(scratch::add);
			for (int i = 0; i < scratch.size(); i++) {
				long packed = scratch.getLong(i);
				if (!session.pendingTicks.containsKey(packed)
						|| !pregen.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(packed)
						|| pregen.PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.contains(packed)) {
					if (pregen.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.remove(packed)) repaired++;
				}
			}
			scratch.clear();
			return repaired;
		}
 }
}
