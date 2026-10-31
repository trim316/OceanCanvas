package net.oceancanvas.mod.worldgen;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasCellRepairDataTest {
 static OceanCanvasCellRepairData cold(OceanCanvasCellRepairData d){return OceanCanvasCellRepairData.CODEC.parse(JsonOps.INSTANCE,OceanCanvasCellRepairData.CODEC.encodeStart(JsonOps.INSTANCE,d).getOrThrow()).getOrThrow();}
 @Test void savedPendingAuthoritySurvivesColdDecode(){var d=new OceanCanvasCellRepairData();assertTrue(d.issue(OceanCanvasCellRepairReceiptsTest.receipt(),true,OceanCanvasCellRepairReceiptsTest.GOOD));assertTrue(cold(d).permits(OceanCanvasCellRepairReceiptsTest.W,OceanCanvasCellRepairReceiptsTest.O,42,OceanCanvasCellRepairReceiptsTest.GOOD));}
 @Test void consumedAuthorityStaysConsumedAfterColdDecode(){var d=new OceanCanvasCellRepairData();d.issue(OceanCanvasCellRepairReceiptsTest.receipt(),true,OceanCanvasCellRepairReceiptsTest.GOOD);d.finish(OceanCanvasCellRepairReceiptsTest.W,OceanCanvasCellRepairReceiptsTest.O,42,OceanCanvasCellRepairReceiptsTest.GOOD,true,true);assertFalse(cold(d).permits(OceanCanvasCellRepairReceiptsTest.W,OceanCanvasCellRepairReceiptsTest.O,42,OceanCanvasCellRepairReceiptsTest.GOOD));}
 @Test void queuedOperatorAttestationIsNotAuthorityEvenAfterColdDecode(){var d=new OceanCanvasCellRepairData();assertTrue(d.queueOperatorRequest(OceanCanvasCellRepairReceiptsTest.receipt()));var cold=cold(d);assertNotNull(cold.requestAt(42));assertFalse(cold.permits(OceanCanvasCellRepairReceiptsTest.W,OceanCanvasCellRepairReceiptsTest.O,42,OceanCanvasCellRepairReceiptsTest.GOOD));}
 @Test void queueCannotGrantAuthorityToProtectedCell(){var d=new OceanCanvasCellRepairData();var r=OceanCanvasCellRepairReceiptsTest.receipt();d.queueOperatorRequest(r);var no=new OceanCanvasCellRepairReceipts.Scope(true,true,true,true,true,true,false,true);assertFalse(d.issue(r,true,no));assertFalse(d.permits(r.world(),r.operation(),42,no));}
}
