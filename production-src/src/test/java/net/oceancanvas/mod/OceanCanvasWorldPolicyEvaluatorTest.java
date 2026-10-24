package net.oceancanvas.mod;

import net.oceancanvas.mod.project.OceanCanvasWorldPolicyEvaluator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline inheritance contract: no Minecraft world required. */
public final class OceanCanvasWorldPolicyEvaluatorTest {
    private record P(String id,String scopeType,String scopeId,String key,String value,boolean enabled,String rationale,long updatedAt)
            implements OceanCanvasWorldPolicyEvaluator.PolicyLike {}
    @Test void worldPolicyInheritanceRegression(){ runAll(); }
    public static void main(String[] args){ runAll(); }
    private static void runAll(){
        var policies=List.of(
                new P("w","WORLD","","HISTORIC_PRESERVATION","false",true,"default",1),
                new P("r","REGION","old_aster","HISTORIC_PRESERVATION","true",true,"heritage",2),
                new P("p","PROJECT","castle","HISTORIC_PRESERVATION","false",false,"disabled experiment",3),
                new P("u","WORLD","","UNKNOWN_MODIFICATIONS","STOP_REVIEW",true,"safe default",4));
        var c=OceanCanvasWorldPolicyEvaluator.Context.of("Old Aster","castle","");
        var h=OceanCanvasWorldPolicyEvaluator.evaluateKey(policies,c,"historic_preservation");
        if(h==null||!"true".equals(h.value())||!"REGION".equals(h.sourceScope()))throw new AssertionError("region override failed: "+h);
        var u=OceanCanvasWorldPolicyEvaluator.evaluateKey(policies,c,"unknown modifications");
        if(u==null||!"STOP_REVIEW".equals(u.value())||!"WORLD".equals(u.sourceScope()))throw new AssertionError("world default failed: "+u);
        var none=OceanCanvasWorldPolicyEvaluator.evaluateKey(policies,OceanCanvasWorldPolicyEvaluator.Context.of("elsewhere","",""),"missing");
        if(none!=null)throw new AssertionError("missing policy should be null");
        System.out.println("OceanCanvasWorldPolicyEvaluatorTest OK");
    }
}
