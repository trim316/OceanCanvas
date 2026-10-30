package net.oceancanvas.mod.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasLightCertificateMigrationTest {
    @SuppressWarnings("unchecked")
    private static MapCodec<OceanCanvasProtectedData> codec() throws Exception {
        var field = OceanCanvasProtectedData.class.getDeclaredField("CODEC");
        field.setAccessible(true);
        return (MapCodec<OceanCanvasProtectedData>) field.get(null);
    }
    private static JsonObject input(int lightingFormat, boolean compact) {
        var json = JsonParser.parseString("""
                {"protectedRegions":[{"minX":1312,"minY":-16,"minZ":1744,"maxX":1344,"maxY":16,"maxZ":1776}],
                 "relocatedShipwreckOrigins":[],"guaranteedShipwreckPlaced":false,
                 "completionSealFormatVersion":2,"physicalProfileVersion":2}
                """).getAsJsonObject();
        json.addProperty("lightingCompletionFormatVersion", lightingFormat);
        if (compact) json.add("sealRuns", JsonParser.parseString("[{\"z\":17,\"minX\":-24,\"maxX\":-24,\"mask\":15}]"));
        else {
            long key = ChunkPos.pack(-24,17);
            for (var name : new String[]{"processedChunks","verifiedProcessedChunks","physicallyVerifiedProcessedChunks","lightingVerifiedProcessedChunks"})
                json.add(name, JsonParser.parseString("["+key+"]"));
        }
        return json;
    }
    @Test void compactOldCertificateLosesOnlyLightingTrustAndSurvivesRoundTrip() throws Exception {
        assertMigration(true);
    }
    @Test void legacyListOldCertificateAlsoCannotBypassNewProof() throws Exception {
        assertMigration(false);
    }
    private void assertMigration(boolean compact) throws Exception {
        var codec = codec().codec();
        var data = codec.parse(JsonOps.INSTANCE, input(3, compact)).getOrThrow();
        var chunk = new ChunkPos(-24,17);
        assertTrue(data.isChunkProcessedPhysicallyVerified(chunk));
        assertFalse(data.isChunkLightingVerified(chunk));
        assertTrue(data.isProtected(1312,0,1744));
        var encoded = codec.encodeStart(JsonOps.INSTANCE,data).getOrThrow();
        assertEquals(4,encoded.getAsJsonObject().get("lightingCompletionFormatVersion").getAsInt());
        var cold = codec.parse(JsonOps.INSTANCE,encoded).getOrThrow();
        assertTrue(cold.isChunkProcessedPhysicallyVerified(chunk));
        assertFalse(cold.isChunkLightingVerified(chunk));
        assertTrue(cold.isProtected(1312,0,1744));
    }
    @Test void newFormatDoesNotDiscardAnAlreadyProvenCurrentCertificate() throws Exception {
        var data = codec().codec().parse(JsonOps.INSTANCE,input(4,true)).getOrThrow();
        assertTrue(data.isChunkLightingVerified(new ChunkPos(-24,17)));
    }
}
