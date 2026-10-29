package net.oceancanvas.mod.worldgen;
import java.util.*;import net.minecraft.world.level.levelgen.structure.BoundingBox;
import static org.junit.jupiter.api.Assertions.*;import org.junit.jupiter.api.Test;
class OceanCanvasRevalidationScopeTest {
 @Test void exactDistantCheckpointDebtPermitsYieldWithoutConsumption(){
  var debt=new LinkedHashMap<Integer,BoundingBox>();debt.put(1,new BoundingBox(2133,-25,667,2148,-17,675));debt.put(2,new BoundingBox(5512,-25,667,5539,-17,675));debt.put(3,new BoundingBox(6552,-25,667,6579,-17,675));var before=new LinkedHashMap<>(debt);
  assertFalse(OceanCanvasRevalidationScope.affectsChunk(debt.values(),-424,41));
  assertTrue(OceanCanvasCooperativeCarvePolicy.shouldYield(true,1,20,20));assertEquals(before,debt);
  assertFalse(OceanCanvasRevalidationScope.affectsChunk(new ArrayList<>(debt.values()),-424,41));assertEquals(before,debt);
 }
 @Test void ownerOverlapAndWholeOneChunkLightRingStayAtomic(){var b=List.of(new BoundingBox(31,-25,31,48,-17,48));assertTrue(OceanCanvasRevalidationScope.affectsChunk(b,1,1));assertTrue(OceanCanvasRevalidationScope.affectsChunk(b,3,3));assertTrue(OceanCanvasRevalidationScope.affectsChunk(b,0,4));assertFalse(OceanCanvasRevalidationScope.affectsChunk(b,-1,1));assertFalse(OceanCanvasRevalidationScope.affectsChunk(b,5,1));}
 @Test void negativeCoordinatesUseFloorDivision(){var b=List.of(new BoundingBox(-17,-25,-1,-16,-17,0));assertTrue(OceanCanvasRevalidationScope.affectsChunk(b,-3,-2));assertTrue(OceanCanvasRevalidationScope.affectsChunk(b,0,1));assertFalse(OceanCanvasRevalidationScope.affectsChunk(b,1,0));}
 @Test void emptyAndExtremeBoundsRemainSafe(){assertFalse(OceanCanvasRevalidationScope.affectsChunk(List.of(),0,0));var b=List.of(new BoundingBox(Integer.MIN_VALUE,0,Integer.MIN_VALUE,Integer.MAX_VALUE,1,Integer.MAX_VALUE));assertTrue(OceanCanvasRevalidationScope.affectsChunk(b,0,0));assertTrue(OceanCanvasRevalidationScope.affectsChunk(b,-134217729,-134217729));}
}
