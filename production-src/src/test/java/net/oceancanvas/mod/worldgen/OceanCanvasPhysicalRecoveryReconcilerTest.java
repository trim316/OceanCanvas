package net.oceancanvas.mod.worldgen;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasPhysicalRecoveryReconcilerTest {
 @Test void largePendingWindowMatchesOracleAndRetainsDebt(){
  var p=new OceanCanvasPregenFlattenerSession(2,0);var l=new OceanCanvasLightFinalizerSession();var old=new HashSet<Long>();var expected=new HashSet<Long>();
  for(long k=0;k<12000;k++){if(k%5!=0)l.pendingTicks.put(k,(int)(k%17));if(k%3!=0)p.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.add(k);if(k%7==0)p.PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.add(k);if(k%11==0){p.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.add(k);old.add(k);}p.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.add(k);p.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_ACTIVE.add(k);if(k%5!=0&&k%3!=0&&k%7!=0)expected.add(k);}
  var diff=new HashSet<>(old);for(long k:expected)if(!diff.add(k))diff.remove(k);
  assertEquals(diff.size(),OceanCanvasPhysicalRecoveryReconciler.reconcile(p,l));
  for(long k=0;k<12000;k++){assertEquals(expected.contains(k),p.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.contains(k));assertEquals(k%5!=0,l.pendingTicks.containsKey(k));if(k%5!=0)assertEquals((int)(k%17),l.pendingTicks.get(k));assertEquals(k%3!=0,p.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(k));assertEquals(k%7==0,p.PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.contains(k));assertEquals(!expected.contains(k),p.PREGEN_CRASH_RECOVERY_LIGHT_ONLY_TRACKED.contains(k));}
  assertEquals(0,OceanCanvasPhysicalRecoveryReconciler.reconcile(p,l));
 }
 @Test void proofInvalidationAndPendingRemovalReconcileNextCall(){var p=new OceanCanvasPregenFlattenerSession(2,0);var l=new OceanCanvasLightFinalizerSession();p.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.add(42);p.PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.add(42);l.pendingTicks.put(42,1);assertEquals(0,OceanCanvasPhysicalRecoveryReconciler.reconcile(p,l));p.PREGEN_CRASH_RECOVERY_PHYSICAL_TERRAIN_SAFE.remove(42);assertEquals(1,OceanCanvasPhysicalRecoveryReconciler.reconcile(p,l));l.pendingTicks.remove(42);assertEquals(1,OceanCanvasPhysicalRecoveryReconciler.reconcile(p,l));assertTrue(p.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.contains(42));}
 @Test void capturedSessionsAreIndependent(){var a=new OceanCanvasPregenFlattenerSession(2,0);var b=new OceanCanvasPregenFlattenerSession(2,0);var l=new OceanCanvasLightFinalizerSession();a.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.add(7);b.PREGEN_CRASH_RECOVERY_PHYSICAL_TRACKED.add(9);l.pendingTicks.put(7,1);OceanCanvasPhysicalRecoveryReconciler.reconcile(a,l);assertTrue(a.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.contains(7));assertFalse(b.PREGEN_CRASH_RECOVERY_PHYSICAL_ACTIVE.contains(7));}
}
