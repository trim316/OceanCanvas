package net.oceancanvas.mod.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class OceanCanvasSkyPathProofTest {
    private static OceanCanvasSkyPathProof.Sample sample(int sky, boolean source) {
        return new OceanCanvasSkyPathProof.Sample(sky,true,source);
    }
    @Test void lateralOpeningCanExplainAnOtherwiseOverbrightWaterShaft() {
        assertTrue(OceanCanvasSkyPathProof.supports((x,y,z) -> y==0&&z==0&&x>=0&&x<=12 ? sample(3+x,x==12) : null,
            0,0,0,3,256,Long.MAX_VALUE));
    }
    @Test void stalePlateauCannotCertifyItself() {
        assertFalse(OceanCanvasSkyPathProof.supports((x,y,z) -> sample(3,false),0,0,0,3,256,Long.MAX_VALUE));
    }
    @Test void brightStaleGradientMustActuallyReachAGeometricallyProvenSource() {
        assertFalse(OceanCanvasSkyPathProof.supports((x,y,z) -> y==0&&z==0&&x>=0&&x<=12 ? sample(3+x,false) : null,
            0,0,0,3,256,Long.MAX_VALUE));
    }
    @Test void opaqueOrUnloadedContextAndBudgetsFailClosed() {
        OceanCanvasSkyPathProof.Probe blocked = (x,y,z) -> x==0&&y==0&&z==0 ? sample(3,false)
            : x==1&&y==0&&z==0 ? new OceanCanvasSkyPathProof.Sample(15,false,true) : null;
        assertFalse(OceanCanvasSkyPathProof.supports(blocked,0,0,0,3,256,Long.MAX_VALUE));
        assertFalse(OceanCanvasSkyPathProof.supports((x,y,z)->null,0,0,0,3,256,Long.MAX_VALUE));
        assertFalse(OceanCanvasSkyPathProof.supports((x,y,z)->sample(15,true),0,0,0,15,0,Long.MAX_VALUE));
        assertFalse(OceanCanvasSkyPathProof.supports((x,y,z)->sample(15,true),0,0,0,15,256,0));
    }
}
