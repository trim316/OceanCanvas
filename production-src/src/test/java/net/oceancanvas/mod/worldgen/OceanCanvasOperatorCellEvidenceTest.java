package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasOperatorCellEvidenceTest {
 @TempDir Path root;
 static byte[] valid(){return ("{\"world\":\""+OceanCanvasCellRepairReceiptsTest.W+"\",\"operation\":\""+OceanCanvasCellRepairReceiptsTest.O+"\",\"position\":42,\"previous\":\"minecraft:water[level=0]\",\"expected\":\"minecraft:stone\",\"replacement\":\"minecraft:water[level=0]\",\"parentArchive\":\""+"a".repeat(64)+"\",\"failedArchive\":\""+"b".repeat(64)+"\"}").getBytes(StandardCharsets.UTF_8);}
 static OceanCanvasOperatorCellEvidence decode(byte[] bytes){return OceanCanvasOperatorCellEvidence.decode(bytes,OceanCanvasReceiptFileBarrier.sha(bytes));}
 @Test void exactDigestAndLineageDecode(){var e=decode(valid());assertEquals(42,e.position());assertTrue(e.matches(e.receipt(OceanCanvasReceiptFileBarrier.sha(valid()))));}
 @Test void modifiedBytesDoNotMatchConfirmedDigest(){var b=valid();var digest=OceanCanvasReceiptFileBarrier.sha(b);b[0]=' ';assertThrows(IllegalArgumentException.class,()->OceanCanvasOperatorCellEvidence.decode(b,digest));}
 @Test void naturalStoneOrFlowingWaterBaselineCannotAuthorize(){for(String old:new String[]{"minecraft:stone","minecraft:water[level=8]"}){var text=new String(valid(),StandardCharsets.UTF_8).replace("\"previous\":\"minecraft:water[level=0]\"","\"previous\":\""+old+"\"");assertThrows(IllegalArgumentException.class,()->decode(text.getBytes(StandardCharsets.UTF_8)));}}
 @Test void oversizedEvidenceRefused(){assertThrows(IllegalArgumentException.class,()->decode(new byte[8193]));}
 @Test void sameBeforeAndFailedArchiveRefused(){var b=new String(valid(),StandardCharsets.UTF_8).replace("b".repeat(64),"a".repeat(64)).getBytes(StandardCharsets.UTF_8);assertThrows(IllegalArgumentException.class,()->decode(b));}
 @Test void fractionalCellCannotTruncateToAnotherAuthorizedPosition(){var b=new String(valid(),StandardCharsets.UTF_8).replace(":42,",":42.5,").getBytes(StandardCharsets.UTF_8);assertThrows(IllegalArgumentException.class,()->decode(b));}
 @Test void pathTraversalDigestRefused(){assertThrows(IllegalArgumentException.class,()->OceanCanvasOperatorCellEvidence.read(root,"../escape"));}
 @Test void operatorReceiptNeedsForcedExactEvidenceBeforeAuthority() throws Exception {var b=valid();var digest=OceanCanvasReceiptFileBarrier.sha(b);var e=decode(b);var r=e.receipt(digest);assertEquals(OceanCanvasCellRepairTransactions.State.FAILED,new OceanCanvasCellRepairTransactions(Runnable::run).authority(root,r));new OceanCanvasReceiptFileBarrier(Runnable::run).publish(root,e.world(),e.operation(),b).get();assertEquals(OceanCanvasCellRepairTransactions.State.AUTHORIZED,new OceanCanvasCellRepairTransactions(Runnable::run).authority(root,r));}
}
