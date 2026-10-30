package net.oceancanvas.mod.pregen;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OceanCanvasCellRepairOperationIdentityTest {
 static OceanCanvasJobState.Snapshot snapshot(){return new OceanCanvasJobState.Snapshot("pregen",0,0,0,0,1,0,1,0,0,0,List.of(),List.of(),List.of(),"","CARVE");}
 static OceanCanvasJobState cold(OceanCanvasJobState d){return OceanCanvasJobState.CODEC.parse(JsonOps.INSTANCE,OceanCanvasJobState.CODEC.encodeStart(JsonOps.INSTANCE,d).getOrThrow()).getOrThrow();}
 @Test void noJobCannotIssueOperationIdentity(){assertEquals("",new OceanCanvasJobState().cellRepairOperationId());}
 @Test void coldSnapshotPreservesExactGeneration(){var d=new OceanCanvasJobState();d.save(snapshot());assertEquals(d.cellRepairOperationId(),cold(d).cellRepairOperationId());}
 @Test void cancelThenSameGeometryNeverReusesGeneration(){var d=new OceanCanvasJobState();d.save(snapshot());var before=d.cellRepairOperationId();d.clear();assertEquals("",cold(d).cellRepairOperationId());d.save(snapshot());assertNotEquals(before,d.cellRepairOperationId());}
 @Test void legacyJobGetsNewIdentityAndThenPersists(){var d=new OceanCanvasJobState();d.save(snapshot());var json=OceanCanvasJobState.CODEC.encodeStart(JsonOps.INSTANCE,d).getOrThrow().getAsJsonObject();json.remove("cellRepairOperationId");var old=OceanCanvasJobState.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();var id=old.cellRepairOperationId();assertFalse(id.isBlank());assertEquals(id,cold(old).cellRepairOperationId());}
}
