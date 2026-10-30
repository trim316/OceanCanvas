package net.oceancanvas.mod.worldgen;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasCellRepairDataTest {
 static OceanCanvasCellRepairData cold(OceanCanvasCellRepairData d){return OceanCanvasCellRepairData.CODEC.parse(JsonOps.INSTANCE,OceanCanvasCellRepairData.CODEC.encodeStart(JsonOps.INSTANCE,d).getOrThrow()).getOrThrow();}
 @Test void savedPendingAuthoritySurvivesColdDecode(){var d=new OceanCanvasCellRepairData();assertTrue(d.issue(OceanCanvasCellRepairReceiptsTest.receipt(),true,OceanCanvasCellRepairReceiptsTest.GOOD));assertTrue(cold(d).permits(OceanCanvasCellRepairReceiptsTest.W,OceanCanvasCellRepairReceiptsTest.O,42,OceanCanvasCellRepairReceiptsTest.GOOD));}
 @Test void consumedAuthorityStaysConsumedAfterColdDecode(){var d=new OceanCanvasCellRepairData();d.issue(OceanCanvasCellRepairReceiptsTest.receipt(),true,OceanCanvasCellRepairReceiptsTest.GOOD);d.finish(OceanCanvasCellRepairReceiptsTest.W,OceanCanvasCellRepairReceiptsTest.O,42,OceanCanvasCellRepairReceiptsTest.GOOD,true,true);assertFalse(cold(d).permits(OceanCanvasCellRepairReceiptsTest.W,OceanCanvasCellRepairReceiptsTest.O,42,OceanCanvasCellRepairReceiptsTest.GOOD));}
}
