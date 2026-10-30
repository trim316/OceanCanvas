package net.oceancanvas.mod.worldgen;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasCellRepairReceiptsTest {
    static final String W="514cea34-7311-4a1e-bd2a-a0884a3fa686", O="ff43fd55-e271-47ae-94ea-53b21c137d27";
    static final long P=42;
    static final OceanCanvasCellRepairReceipts.Scope GOOD=new OceanCanvasCellRepairReceipts.Scope(true,true,true,true,true,false,false,true);
    static OceanCanvasCellRepairReceipts.Receipt receipt(){return new OceanCanvasCellRepairReceipts.Receipt(W,O,P,"a".repeat(64),"OPERATOR_PREVIOUS_SOURCE_WATER",false);}
    static OceanCanvasCellRepairReceipts pending(){var x=new OceanCanvasCellRepairReceipts();assertTrue(x.issue(receipt(),true,GOOD));return x;}
    @Test void historicalSealWithoutExplicitAuthorityCannotIssue(){var x=new OceanCanvasCellRepairReceipts();assertFalse(x.issue(receipt(),false,GOOD));assertTrue(x.snapshot().isEmpty());}
    @Test void everyCurrentScopePrerequisiteIsRequired(){for(int n=0;n<8;n++){boolean[] a={true,true,true,true,true,false,false,true};a[n]=!a[n];var s=new OceanCanvasCellRepairReceipts.Scope(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7]);assertFalse(pending().permits(W,O,P,s),"scope "+n);assertFalse(new OceanCanvasCellRepairReceipts().issue(receipt(),true,s));}}
    @Test void worldOperationAndExactCellAreBound(){var x=pending();assertFalse(x.permits(UUID.randomUUID().toString(),O,P,GOOD));assertFalse(x.permits(W,UUID.randomUUID().toString(),P,GOOD));assertFalse(x.permits(W,O,P+1,GOOD));}
    @Test void failedNativeWriteOrMissingReproofRetainsDebt(){var x=pending();assertFalse(x.finish(W,O,P,GOOD,false,true));assertFalse(x.finish(W,O,P,GOOD,true,false));assertTrue(x.permits(W,O,P,GOOD));}
    @Test void successfulWriteConsumesOnceAndCannotReissue(){var x=pending();assertTrue(x.finish(W,O,P,GOOD,true,true));assertFalse(x.permits(W,O,P,GOOD));assertFalse(x.finish(W,O,P,GOOD,true,true));assertFalse(x.issue(receipt(),true,GOOD));}
    @Test void pendingSurvivesActualCodecColdRoundTrip(){var x=pending();var json=OceanCanvasCellRepairReceipts.CODEC.encodeStart(JsonOps.INSTANCE,x.snapshot()).getOrThrow();var cold=new OceanCanvasCellRepairReceipts(OceanCanvasCellRepairReceipts.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow());assertTrue(cold.permits(W,O,P,GOOD));assertFalse(cold.permits(W,UUID.randomUUID().toString(),P,GOOD));}
    @Test void consumedSurvivesActualCodecColdRoundTrip(){var x=pending();x.finish(W,O,P,GOOD,true,true);var json=OceanCanvasCellRepairReceipts.CODEC.encodeStart(JsonOps.INSTANCE,x.snapshot()).getOrThrow();var cold=new OceanCanvasCellRepairReceipts(OceanCanvasCellRepairReceipts.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow());assertFalse(cold.permits(W,O,P,GOOD));assertFalse(cold.issue(receipt(),true,GOOD));}
    @Test void duplicateColdRecordsFailClosed(){assertThrows(IllegalArgumentException.class,()->new OceanCanvasCellRepairReceipts(List.of(receipt(),receipt())));}
    @Test void malformedEvidenceAndUnknownOriginCannotConstruct(){assertThrows(IllegalArgumentException.class,()->new OceanCanvasCellRepairReceipts.Receipt(W,O,P,"none","OPERATOR_PREVIOUS_SOURCE_WATER",false));assertThrows(IllegalArgumentException.class,()->new OceanCanvasCellRepairReceipts.Receipt(W,O,P,"a".repeat(64),"SEAL_ONLY",false));}
    @Test void crashAfterWaterWriteNeedsReproofThenConsumesWithoutAnotherWrite(){var x=pending();var water=new OceanCanvasCellRepairReceipts.Scope(true,true,true,true,true,false,false,false);assertFalse(x.recoverCompletedWrite(W,O,P,water,true,false));assertTrue(x.recoverCompletedWrite(W,O,P,water,true,true));assertFalse(x.recoverCompletedWrite(W,O,P,water,true,true));}
    @Test void crashRecoveryCannotBlessOtherStateOrProtectedWater(){var x=pending();var water=new OceanCanvasCellRepairReceipts.Scope(true,true,true,true,true,false,false,false);assertFalse(x.recoverCompletedWrite(W,O,P,water,false,true));var protectedWater=new OceanCanvasCellRepairReceipts.Scope(true,true,true,true,true,true,false,false);assertFalse(x.recoverCompletedWrite(W,O,P,protectedWater,true,true));assertTrue(x.permits(W,O,P,GOOD));}
}
