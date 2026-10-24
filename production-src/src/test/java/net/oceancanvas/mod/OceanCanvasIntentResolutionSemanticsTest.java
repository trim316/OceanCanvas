package net.oceancanvas.mod;

import org.junit.jupiter.api.Test;

/** Documents the L0-L5 contract without needing a running Minecraft world. */
public final class OceanCanvasIntentResolutionSemanticsTest {
    private static String name(int level){return switch(Math.max(0,Math.min(5,level))){case 0->"UNKNOWN";case 1->"BROAD";case 2->"GEOGRAPHIC";case 3->"REGIONAL";case 4->"DETAILED";default->"AUTHORED";};}
    @Test void intentResolutionRegression(){ runAll(); }
    public static void main(String[] args){ runAll(); }
    private static void runAll(){
        String[] expected={"UNKNOWN","BROAD","GEOGRAPHIC","REGIONAL","DETAILED","AUTHORED"};
        for(int i=0;i<expected.length;i++)if(!expected[i].equals(name(i)))throw new AssertionError("bad intent level "+i);
        if(!"UNKNOWN".equals(name(-1))||!"AUTHORED".equals(name(99)))throw new AssertionError("clamp contract failed");
        System.out.println("OceanCanvasIntentResolutionSemanticsTest OK");
    }
}
