package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasOpaqueSkyInvalidationTest {
 @Test void admitsOnlyOpaquePositiveNativeLevelsOnce(){
  for(int d=0;d<=15;d++)for(int s=0;s<=15;s++){
   assertEquals(d==15&&s>0,OceanCanvasOpaqueSkyInvalidation.shouldQueue(d,s,false));
   assertFalse(OceanCanvasOpaqueSkyInvalidation.shouldQueue(d,s,true));
  }
  assertFalse(OceanCanvasOpaqueSkyInvalidation.shouldQueue(16,15,false));
  assertFalse(OceanCanvasOpaqueSkyInvalidation.shouldQueue(15,16,false));
 }
}
