package net.oceancanvas.mod.worldgen;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OceanCanvasNaturalIceAuditPolicyTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void vanillaIceIsAcceptedOnlyAtCanvasWaterSurface() {
        int top = 62;
        assertTrue(OceanCanvasSurfaceFlattener.isCanonicalCanvasWaterAuditState(Blocks.WATER.defaultBlockState(), top, top));
        assertTrue(OceanCanvasSurfaceFlattener.isCanonicalCanvasWaterAuditState(Blocks.ICE.defaultBlockState(), top, top));
        assertFalse(OceanCanvasSurfaceFlattener.isCanonicalCanvasWaterAuditState(Blocks.ICE.defaultBlockState(), top - 1, top));
        assertFalse(OceanCanvasSurfaceFlattener.isCanonicalCanvasWaterAuditState(Blocks.STONE.defaultBlockState(), top, top));
    }

    @Test
    void rawIceHashDiffersFromCanonicalSurfaceIdentitySoVerifierMustNormalize() {
        int top = 62;
        int rawIce = Blocks.ICE.defaultBlockState().hashCode();
        int canonicalIce = OceanCanvasSurfaceFlattener.physicalAuditFingerprintStateHash(Blocks.ICE.defaultBlockState(), top, top);
        int canonicalWater = OceanCanvasSurfaceFlattener.physicalAuditFingerprintStateHash(Blocks.WATER.defaultBlockState(), top, top);
        assertEquals(canonicalWater, canonicalIce);
        assertNotEquals(rawIce, canonicalIce, "raw ICE identity would recreate the deterministic stage-0/stage-1 mismatch");
    }

    @Test
    void surfaceWaterAndIceHaveSamePhysicalFingerprintIdentity() {
        int top = 62;
        int water = OceanCanvasSurfaceFlattener.physicalAuditFingerprintStateHash(Blocks.WATER.defaultBlockState(), top, top);
        int ice = OceanCanvasSurfaceFlattener.physicalAuditFingerprintStateHash(Blocks.ICE.defaultBlockState(), top, top);
        assertEquals(water, ice);
        assertNotEquals(water, OceanCanvasSurfaceFlattener.physicalAuditFingerprintStateHash(Blocks.ICE.defaultBlockState(), top - 1, top));
    }
}
