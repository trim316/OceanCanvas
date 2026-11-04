package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
class OceanCanvasRelocationProtectionTest {
 private final BoundingBox source=new BoundingBox(0,20,0,2,22,2),target=new BoundingBox(0,-21,0,2,-19,2);
 @Test void actualDestinationSentinelFailsBeforeCallerCanMutate(){
  var e=assertThrows(OceanCanvasRelocationProtection.Collision.class,()->OceanCanvasRelocationProtection.requireUnprotected(source,target,(x,y,z)->x==1&&y==-21&&z==1));
  assertTrue(e.getMessage().contains("destination"));
 }
 @Test void sourceClearAlsoHonorsAbsoluteAuthority(){
  var e=assertThrows(OceanCanvasRelocationProtection.Collision.class,()->OceanCanvasRelocationProtection.requireUnprotected(source,target,(x,y,z)->x==2&&y==22&&z==2));
  assertTrue(e.getMessage().contains("source-clear"));
 }
 @Test void disabledOrOutsideProtectionDoesNotWidenMaterialFilteredPolicy(){
  assertDoesNotThrow(()->OceanCanvasRelocationProtection.requireUnprotected(source,target,(x,y,z)->false));
  assertDoesNotThrow(()->OceanCanvasRelocationProtection.requireUnprotected(source,target,(x,y,z)->x==3));
 }
 @Test void boundaryAndPartialShapeUseExactCellPredicate(){
  assertThrows(OceanCanvasRelocationProtection.Collision.class,()->OceanCanvasRelocationProtection.requireUnprotected(source,target,(x,y,z)->x==2&&z==2&&y==-19));
  assertDoesNotThrow(()->OceanCanvasRelocationProtection.requireUnprotected(source,target,(x,y,z)->x==2&&z==3));
 }
}
