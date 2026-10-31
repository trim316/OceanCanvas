package net.oceancanvas.mod.worldgen;
import net.minecraft.world.level.ChunkPos;import net.minecraft.world.level.levelgen.structure.BoundingBox;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
final class OceanCanvasStructureChangedContextTest {
 @Test void laterNeighborChunkCanReachOldOwnerStructureWithoutWaitingForOwner(){
  var ship=new BoundingBox(1320,-21,1771,1347,-13,1779);
  var part=OceanCanvasStructureEnvironmentService.changedContextIntersection(ship,new ChunkPos(83,110));
  assertNotNull(part);assertTrue(part.isInside(new net.minecraft.core.BlockPos(1330,-18,1771)));assertEquals(1327,part.minX());assertEquals(1344,part.maxX());assertEquals(ship.minY(),part.minY());assertEquals(ship.maxY(),part.maxY());
 }
 @Test void contextIsExactlyOneBlockAcrossNegativeBoundariesAndDistantChunksDoNothing(){
  var b=new BoundingBox(-40,-4,-40,20,9,20);var p=OceanCanvasStructureEnvironmentService.changedContextIntersection(b,new ChunkPos(-1,-1));
  assertEquals(-17,p.minX());assertEquals(0,p.maxX());assertEquals(-17,p.minZ());assertEquals(0,p.maxZ());
  assertNull(OceanCanvasStructureEnvironmentService.changedContextIntersection(b,new ChunkPos(5,5)));
 }
 @Test void absoluteProtectionCustomAndPartialFootprintNeverAcquireMutationAuthority(){
  for(boolean owns:new boolean[]{false,true})for(boolean player:new boolean[]{false,true})for(boolean custom:new boolean[]{false,true})
   assertEquals(owns&&!player&&!custom,OceanCanvasStructureEnvironmentService.contextMutationPermitted(owns,player,custom));
 }
}
