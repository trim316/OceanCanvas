package net.oceancanvas.mod.worldgen;
import it.unimi.dsi.fastutil.longs.LongArrayList;
/** Original full reconciliation with one captured session pair; no per-key runtime resolution. */
final class OceanCanvasPhysicalRecoveryReconciler {
 static long[] versions(OceanCanvasPregenFlattenerSession p, OceanCanvasLightFinalizerSession s) {
  return new long[]{s.pendingTicks.membershipVersion(),p.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.membershipVersion(),p.PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.membershipVersion(),p.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.membershipVersion(),p.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.membershipVersion(),p.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.membershipVersion()};
 }
 static int reconcile(OceanCanvasPregenFlattenerSession pregen, OceanCanvasLightFinalizerSession session) {
  synchronized(session.recoveryIterationScratch) {
   long[] before=versions(pregen,session);
   if(session.stablePhysicalReconciliationOwner==pregen && java.util.Arrays.equals(before,session.stablePhysicalReconciliationVersions)) {
    session.physicalReconciliationCacheHits.incrementAndGet();return 0;
   }
   session.stablePhysicalReconciliationVersions=null;session.stablePhysicalReconciliationOwner=null;
   session.physicalReconciliationScans.incrementAndGet();
   int repaired=scan(pregen,session);
   long[] after=versions(pregen,session);
   // Publish only an unchanged full observation. Own repairs and concurrent
   // structural changes force another complete scan before any reuse.
   if(repaired==0 && java.util.Arrays.equals(before,after)) {session.stablePhysicalReconciliationVersions=after;session.stablePhysicalReconciliationOwner=pregen;}
   return repaired;
  }
 }
 static int scan(OceanCanvasPregenFlattenerSession pregen, OceanCanvasLightFinalizerSession session) {
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
