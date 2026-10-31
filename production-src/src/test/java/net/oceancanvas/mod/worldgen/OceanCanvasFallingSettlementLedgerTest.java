package net.oceancanvas.mod.worldgen;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
final class OceanCanvasFallingSettlementLedgerTest {
 @Test void fullResidencyAndRepeatedCallsCannotCertifyDeferredEntities(){
  var l=new OceanCanvasFallingSettlementLedger();
  for(long t=0;t<1000;t++)assertFalse(l.observe(1,t,false,false));
  for(int i=0;i<10000;i++)assertFalse(l.observe(1,1000,true,false));
  assertFalse(l.observe(1,1039,true,false));assertTrue(l.observe(1,1040,true,false));
  assertFalse(l.observe(1,1041,true,true));assertFalse(l.observe(1,1042,true,false));
  assertFalse(l.observe(1,1081,true,false));assertTrue(l.observe(1,1082,true,false));
 }
 @Test void unloadRetirementAndRestartNeverInheritAQuietWindow(){
  var l=new OceanCanvasFallingSettlementLedger();assertFalse(l.observe(2,10,true,false));assertTrue(l.observe(2,50,true,false));
  assertFalse(l.observe(2,51,false,false));assertFalse(l.observe(2,52,true,false));
  l.remove(2);assertFalse(l.observe(2,100,true,false));l.clear();assertFalse(l.observe(2,200,true,false));
  assertFalse(l.observe(2,1,true,false));assertFalse(new OceanCanvasFallingSettlementLedger().observe(2,500,true,false));
 }
 @Test void nativeLoadAndQuietWaitKeepRetryResidencyUntilActualSettlement(){
  var l=new OceanCanvasFallingSettlementLedger();assertFalse(l.waitingForSettlement(3));
  assertFalse(l.observe(3,10,false,true));assertTrue(l.waitingForSettlement(3));
  assertFalse(l.observe(3,11,true,true));assertTrue(l.waitingForSettlement(3));
  assertFalse(l.observe(3,12,true,false));assertTrue(l.waitingForSettlement(3));
  assertFalse(l.observe(3,51,true,false));assertTrue(l.waitingForSettlement(3));
  assertTrue(l.observe(3,52,true,false));assertFalse(l.waitingForSettlement(3));
 }
 @Test void unloadingRetiringAndRestartCannotKeepResidencyOrQuietCredit(){
  var l=new OceanCanvasFallingSettlementLedger();l.observe(4,20,true,false);l.remove(4);
  assertFalse(l.waitingForSettlement(4));assertFalse(l.observe(4,100,true,false));
  l.clear();assertFalse(l.waitingForSettlement(4));assertFalse(new OceanCanvasFallingSettlementLedger().waitingForSettlement(4));
 }
}
