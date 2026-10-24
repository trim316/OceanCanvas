package net.oceancanvas.mod.project;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.ChunkPos;
class BoundaryColumnPersistenceTest {
 @Test void persistsExactDirectionAndClearsEvenUnchangedUnknown() throws Exception {
  var data=new OceanCanvasTerrainStateData();
  data.recordBoundaryColumn(-23,-250,4,62);
  assertTrue(data.blocksBoundaryInflow(-23,63,-250,-23,-251));
  assertFalse(data.blocksBoundaryInflow(-23,62,-250,-23,-251));
  assertFalse(data.blocksBoundaryInflow(-23,63,-250,-22,-250));
  assertFalse(data.blocksBoundaryInflow(-24,63,-250,-24,-251));
  var field=OceanCanvasTerrainStateData.class.getDeclaredField("CODEC");field.setAccessible(true);
  @SuppressWarnings("unchecked") Codec<OceanCanvasTerrainStateData> codec=(Codec<OceanCanvasTerrainStateData>)field.get(null);
  var encoded=codec.encodeStart(JsonOps.INSTANCE,data).getOrThrow();
  var restarted=codec.parse(JsonOps.INSTANCE,encoded).getOrThrow();
  assertTrue(restarted.blocksBoundaryInflow(-23,63,-250,-23,-251));
  restarted.clear(new ChunkPos(-2,-16));
  assertFalse(restarted.blocksBoundaryInflow(-23,63,-250,-23,-251));
  data.set(new ChunkPos(-2,-16),OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED);
  assertFalse(data.blocksBoundaryInflow(-23,63,-250,-23,-251));
 }
 @Test void futureSchemaAndMalformedDirectionsNeverGrantAuthority() throws Exception {
  var field=OceanCanvasTerrainStateData.class.getDeclaredField("CODEC");field.setAccessible(true);
  @SuppressWarnings("unchecked") Codec<OceanCanvasTerrainStateData> codec=(Codec<OceanCanvasTerrainStateData>)field.get(null);
  var data=new OceanCanvasTerrainStateData();data.recordBoundaryColumn(-23,-250,4,62);
  var json=codec.encodeStart(JsonOps.INSTANCE,data).getOrThrow().getAsJsonObject();
  json.addProperty("schema",999);
  assertFalse(codec.parse(JsonOps.INSTANCE,json).getOrThrow().blocksBoundaryInflow(-23,63,-250,-23,-251));
  json.addProperty("schema",3);json.getAsJsonArray("boundaryColumns").get(0).getAsJsonObject().addProperty("directions",31);
  assertFalse(codec.parse(JsonOps.INSTANCE,json).getOrThrow().blocksBoundaryInflow(-23,63,-250,-23,-251));
  var legacy=codec.parse(JsonOps.INSTANCE,com.google.gson.JsonParser.parseString("{\"schema\":2}")).getOrThrow();
  assertFalse(legacy.blocksBoundaryInflow(-23,63,-250,-23,-251));
  data.clearBoundaryColumns(ChunkPos.pack(-2,-16));
  data.recordBoundaryColumn(-23,-250,2,62);
  assertFalse(data.blocksBoundaryInflow(-23,63,-250,-23,-251));
  assertTrue(data.blocksBoundaryInflow(-23,63,-250,-22,-250));
 }
}
