package net.oceancanvas.mod.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static net.oceancanvas.mod.worldgen.OceanCanvasHistoricalLightAdmission.Decision.*;

final class OceanCanvasHistoricalLightAdmissionTest {
    @Test void coldPublicationAndLoadBurstShareExistingCapacity() {
        for (int cap : new int[]{8,16,32,48}) {
            int active=0, parked=0;
            for (int i=0;i<5389;i++) {
                var decision=OceanCanvasHistoricalLightAdmission.decide(false,false,false,active,cap);
                if(decision==ARM) active++; else {assertEquals(PARK,decision);parked++;}
            }
            assertEquals(cap,active);assertEquals(5389-cap,parked);
        }
    }
    @Test void admittedAndExplicitVisibleWorkCannotBeDisplacedByBackgroundPressure() {
        assertEquals(ARM,OceanCanvasHistoricalLightAdmission.decide(true,false,false,5000,8));
        assertEquals(ARM,OceanCanvasHistoricalLightAdmission.decide(false,true,true,5000,8));
        assertEquals(ARM,OceanCanvasHistoricalLightAdmission.decide(true,false,false,48,8));
    }
    @Test void repeatedPublicationRetainsDormantFaultAndDeadlineRatherThanRearming() {
        for(int active:new int[]{0,8,48,5389})
            assertEquals(RETAIN_DORMANT,OceanCanvasHistoricalLightAdmission.decide(false,false,true,active,48));
    }
    @Test void pressureWakeUsesSameBoundaryWithoutDiscardingDownshiftedActiveCredit() {
        assertFalse(OceanCanvasHistoricalLightAdmission.canWakeBackground(48,8));
        assertFalse(OceanCanvasHistoricalLightAdmission.canWakeBackground(8,8));
        assertTrue(OceanCanvasHistoricalLightAdmission.canWakeBackground(7,8));
        assertFalse(OceanCanvasHistoricalLightAdmission.canWakeBackground(0,0));
    }
    @Test @SuppressWarnings("unchecked") void parkedDirtyCertificateRemainsUntrustedAcrossRealCodecColdRestart() throws Exception {
        var field=OceanCanvasProtectedData.class.getDeclaredField("CODEC");field.setAccessible(true);
        var codec=((com.mojang.serialization.MapCodec<OceanCanvasProtectedData>)field.get(null)).codec();
        var input=com.google.gson.JsonParser.parseString("""
            {"protectedRegions":[{"minX":0,"minY":-16,"minZ":0,"maxX":15,"maxY":16,"maxZ":15}],
             "relocatedShipwreckOrigins":[],"guaranteedShipwreckPlaced":false,
             "completionSealFormatVersion":2,"physicalProfileVersion":2,"lightingCompletionFormatVersion":7,
             "sealRuns":[{"z":0,"minX":0,"maxX":0,"mask":15}]}
            """);
        var data=codec.parse(com.mojang.serialization.JsonOps.INSTANCE,input).getOrThrow();
        var pos=new net.minecraft.world.level.ChunkPos(0,0);
        assertTrue(data.isChunkLightingVerified(pos));
        data.markChunkLightingDirty(pos);
        assertEquals(PARK,OceanCanvasHistoricalLightAdmission.decide(false,false,false,48,48));
        var cold=codec.parse(com.mojang.serialization.JsonOps.INSTANCE,
            codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,data).getOrThrow()).getOrThrow();
        assertFalse(cold.isChunkLightingVerified(pos));
        assertTrue(cold.isChunkProcessedPhysicallyVerified(pos));
        assertTrue(cold.isProtected(1,0,1));
    }
}
