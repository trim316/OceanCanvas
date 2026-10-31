package net.oceancanvas.mod.worldgen;
import net.minecraft.world.level.levelgen.structure.BoundingBox;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.util.*;
final class OceanCanvasStructureContextFenceTest {
 @Test void delayedNeighborWithholdsAndLaterRetriesWithoutOwnerCornerAssumption(){
  var f=new OceanCanvasStructureContextFence(new BoundingBox(1320,-21,1771,1347,-13,1779));
  assertEquals(OceanCanvasStructureContextFence.Result.WAITING,f.advance(1,64,(x,z)->x==83?OceanCanvasStructureContextFence.ChunkState.OWNED_UNPROVED:OceanCanvasStructureContextFence.ChunkState.STABLE));
  assertEquals(OceanCanvasStructureContextFence.Result.READY,f.advance(1,64,(x,z)->OceanCanvasStructureContextFence.ChunkState.STABLE));
 }
 @Test void exactOneBlockContextIncludesNegativeBoundaryButNotUnownedWholeRing(){
  var f=new OceanCanvasStructureContextFence(new BoundingBox(-16,0,-16,-1,2,-1));Set<String> visited=new HashSet<>();
  assertEquals(OceanCanvasStructureContextFence.Result.READY,f.advance(1,64,(x,z)->{visited.add(x+","+z);return OceanCanvasStructureContextFence.ChunkState.STABLE;}));
  assertEquals(9,visited.size());assertTrue(visited.contains("-2,-2"));assertTrue(visited.contains("0,0"));
  var interior=new OceanCanvasStructureContextFence(new BoundingBox(2,0,2,13,2,13));int[] count={0};interior.advance(1,64,(x,z)->{count[0]++;assertEquals(0,x);assertEquals(0,z);return OceanCanvasStructureContextFence.ChunkState.STABLE;});assertEquals(1,count[0]);
 }
 @Test void epochMutationRevokesEarlierPartialPassAndBudgetBoundsEachCall(){
  var f=new OceanCanvasStructureContextFence(new BoundingBox(1,0,1,63,2,1));int[] count={0};
  assertEquals(OceanCanvasStructureContextFence.Result.YIELDED,f.advance(1,1,(x,z)->{count[0]++;return OceanCanvasStructureContextFence.ChunkState.STABLE;}));assertEquals(1,count[0]);
  assertEquals(OceanCanvasStructureContextFence.Result.WAITING,f.advance(2,1,(x,z)->{assertEquals(0,x);return OceanCanvasStructureContextFence.ChunkState.OWNED_UNPROVED;}));
 }
 @Test void coldAndUnloadedNeverBorrowPriorReadinessOrInventWholeSeal(){
  var warm=new OceanCanvasStructureContextFence(new BoundingBox(2,0,2,13,2,13));assertEquals(OceanCanvasStructureContextFence.Result.READY,warm.advance(1,64,(x,z)->OceanCanvasStructureContextFence.ChunkState.STABLE));
  var cold=new OceanCanvasStructureContextFence(new BoundingBox(2,0,2,13,2,13));assertEquals(OceanCanvasStructureContextFence.Result.WAITING,cold.advance(1,64,(x,z)->OceanCanvasStructureContextFence.ChunkState.UNLOADED));
  warm.invalidate();assertEquals(OceanCanvasStructureContextFence.Result.WAITING,warm.advance(1,64,(x,z)->OceanCanvasStructureContextFence.ChunkState.UNLOADED));
 }
}
