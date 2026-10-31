package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static net.oceancanvas.mod.worldgen.OceanCanvasCellRepairTransactions.State.*;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasCellRepairTransactionsTest {
 @TempDir Path root;
 static OceanCanvasCellRepairReceipts.Receipt receipt(){var r=OceanCanvasCellRepairReceiptsTest.receipt();return new OceanCanvasCellRepairReceipts.Receipt(r.world(),r.operation(),r.position(),r.evidence(),"CONFIRMED_NATIVE_LAVA_MIX",false);}
 @Test void coldConsumeMarkerPreventsPendingSavedDataReplay(){var r=receipt();var t=new OceanCanvasCellRepairTransactions(Runnable::run);assertEquals(AUTHORIZED,t.authority(root,r));assertEquals(CONSUMED,t.consume(root,r));var cold=new OceanCanvasCellRepairTransactions(Runnable::run);assertEquals(CONSUMED,cold.authority(root,r));}
 @Test void neitherPendingAuthorityNorPendingConsumeIsPermission(){List<Runnable> q=new ArrayList<>();var t=new OceanCanvasCellRepairTransactions(q::add);var r=receipt();assertEquals(PENDING,t.authority(root,r));assertEquals(1,q.size());q.removeFirst().run();assertEquals(AUTHORIZED,t.authority(root,r));assertEquals(PENDING,t.consume(root,r));assertEquals(PENDING,t.authority(root,r));q.removeFirst().run();assertEquals(CONSUMED,t.authority(root,r));}
 @Test void fileFailureRemainsFailedAcrossRepeatedPolls() throws Exception {Path file=root.resolve("not-directory");Files.writeString(file,"x");var t=new OceanCanvasCellRepairTransactions(Runnable::run);var r=receipt();assertEquals(FAILED,t.authority(file,r));assertEquals(FAILED,t.authority(file,r));}
 @Test void differentJobCannotBorrowOldConsumeMarker(){var r=receipt();var t=new OceanCanvasCellRepairTransactions(Runnable::run);t.authority(root,r);t.consume(root,r);var other=new OceanCanvasCellRepairReceipts.Receipt(r.world(),UUID.randomUUID().toString(),r.position(),r.evidence(),r.origin(),false);assertEquals(AUTHORIZED,t.authority(root,other));}
 @Test void consumedSavedDataWithoutDurableMarkerFailsClosed(){var r=receipt();var consumed=new OceanCanvasCellRepairReceipts.Receipt(r.world(),r.operation(),r.position(),r.evidence(),r.origin(),true);assertEquals(FAILED,new OceanCanvasCellRepairTransactions(Runnable::run).authority(root,consumed));}
}
