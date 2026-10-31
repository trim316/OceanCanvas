package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.*;import net.minecraft.core.registries.*;import net.minecraft.resources.*;
import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.entity.*;import net.minecraft.world.level.storage.loot.*;
final class OceanCanvasCapturedLootSeedsTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 private static ResourceKey<LootTable> table(String name){return ResourceKey.create(Registries.LOOT_TABLE,Identifier.fromNamespaceAndPath("minecraft","chests/"+name));}
 private static ChestBlockEntity chest(){return new ChestBlockEntity(BlockPos.ZERO,Blocks.CHEST.defaultBlockState());}
 @Test void actualNativeChestSeedsArePreservedWithoutOpeningLoot(){
  for(long seed:new long[]{0,-1,Long.MIN_VALUE,Long.MAX_VALUE,6420894526436002392L}){
   var source=chest();source.setLootTable(table("shipwreck_treasure"),seed);var captured=OceanCanvasCapturedLootSeeds.Seed.capture(source).orElseThrow();
   var target=chest();target.setLootTable(table("shipwreck_treasure"),123);assertTrue(captured.restore(target));assertEquals(seed,target.getLootTableSeed());assertEquals(source.getLootTable(),target.getLootTable());
  }
 }
 @Test void nativeTemplateSeedMutationCannotChangePrimitiveCapture(){
  var source=chest();source.setLootTable(table("shipwreck_map"),1063383049580332383L);var captured=OceanCanvasCapturedLootSeeds.Seed.capture(source).orElseThrow();source.setLootTableSeed(4286262120820663436L);
  var target=chest();target.setLootTable(table("shipwreck_map"),5);assertTrue(captured.restore(target));assertEquals(1063383049580332383L,target.getLootTableSeed());
 }
 @Test void wrongOrAlreadyOpenedLootTableIsRefusedWithoutMutation(){
  var source=chest();source.setLootTable(table("shipwreck_supply"),2556144650565442159L);var captured=OceanCanvasCapturedLootSeeds.Seed.capture(source).orElseThrow();
  var target=chest();target.setLootTable(table("shipwreck_map"),7);assertFalse(captured.restore(target));assertEquals(7,target.getLootTableSeed());assertTrue(OceanCanvasCapturedLootSeeds.Seed.capture(chest()).isEmpty());
 }
}
