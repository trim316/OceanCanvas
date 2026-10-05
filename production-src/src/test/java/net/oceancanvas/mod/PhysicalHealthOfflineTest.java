package net.oceancanvas.mod;

import net.oceancanvas.mod.project.OceanCanvasPhysicalHealth;
import net.oceancanvas.mod.project.OceanCanvasPhysicalHealth.Cell;
import net.oceancanvas.mod.project.OceanCanvasPhysicalHealth.Report;
import net.oceancanvas.mod.project.OceanCanvasPhysicalHealth.Status;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Runs without Minecraft, Gradle or JUnit; the same assertions also run under the JUnit adapter. */
public final class PhysicalHealthOfflineTest {
    private static int checks;
    public static void main(String[] args) { runAll(); }
    public static void runAll() {
        checks=0;
        equal(1L,OceanCanvasPhysicalHealth.blocks(0,0,15,15).count(),"one chunk");
        equal(4L,OceanCanvasPhysicalHealth.blocks(-1,-1,0,0).count(),"negative edge rounds outward");
        equal(1L,OceanCanvasPhysicalHealth.blocks(-16,-16,-1,-1).count(),"negative whole chunk");
        equal(4L,OceanCanvasPhysicalHealth.blocks(16,16,0,0).count(),"reversed coordinates normalize");
        equal(1024,OceanCanvasPhysicalHealth.blocks(0,0,511,511).keys().size(),"maximum area accepted");
        rejects(()->OceanCanvasPhysicalHealth.blocks(0,0,512,511).keys(),"oversized area rejected before allocation");
        rejects(()->OceanCanvasPhysicalHealth.blocks(Integer.MIN_VALUE,0,0,0),"negative overflow input");
        rejects(()->OceanCanvasPhysicalHealth.blocks(0,0,Integer.MAX_VALUE,0),"positive overflow input");
        rejects(()->new OceanCanvasPhysicalHealth.Bounds(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0),"unsafe chunk range");
        rejects(()->new OceanCanvasPhysicalHealth.Bounds(1,0,0,0).keys(),"reversed chunk range");
        equal(1L,OceanCanvasPhysicalHealth.blocks(30_000_000,30_000_000,30_000_000,30_000_000).count(),"world boundary inclusive");
        for(int x:new int[]{-1875000,-1,0,1,1875000})for(int z:new int[]{-1875000,-1,0,1,1875000}){
            long key=OceanCanvasPhysicalHealth.pack(x,z);
            equal(x,OceanCanvasPhysicalHealth.x(key),"x key round trip");
            equal(z,OceanCanvasPhysicalHealth.z(key),"z key round trip");
        }
        equal(Status.SAMPLE_MATCH,OceanCanvasPhysicalHealth.classify("CANVAS",112,0,0),"matching samples");
        equal(Status.DIFFERENCE,OceanCanvasPhysicalHealth.classify("CANVAS",112,0,1),"one difference needs review");
        equal(Status.DIFFERENCE,OceanCanvasPhysicalHealth.classify("CANVAS",1,111,1),"difference not hidden by skipped checks");
        equal(Status.PARTIAL,OceanCanvasPhysicalHealth.classify("CANVAS",0,112,0),"protected/structure-only chunk not verified");
        equal(Status.PARTIAL,OceanCanvasPhysicalHealth.classify("CANVAS",111,1,0),"partial checks not green");
        equal(Status.PARTIAL,OceanCanvasPhysicalHealth.classify("CANVAS",0,0,0),"no samples not green");
        equal(Status.UNKNOWN,OceanCanvasPhysicalHealth.classify("UNKNOWN",112,0,30),"legacy never guessed");
        equal(Status.OBSERVED_ONLY,OceanCanvasPhysicalHealth.classify("VANILLA",112,0,30),"restore not compared to flat canvas");
        equal(Status.OBSERVED_ONLY,OceanCanvasPhysicalHealth.classify("CUSTOM_OR_MODIFIED",112,0,30),"custom not labeled broken");

        List<Cell> cells=new ArrayList<>();
        for(int i=0;i<20;i++)cells.add(new Cell(i-10,-2,Status.values()[i%Status.values().length],"evidence "+i+"\nwith tabs\tand Unicode 山"));
        Report source=new Report("RUNNING",30,1,"Region Mixed CASE / 山","read-only",List.copyOf(cells));
        Report parsed=OceanCanvasPhysicalHealth.decode(OceanCanvasPhysicalHealth.encode(source));
        equal(source.label(),parsed.label(),"case and Unicode label preserved");
        equal(source.cells().size(),parsed.cells().size(),"all map cells survive");
        equal(1,parsed.page(),"page number preserved");
        equal(cells.get(8).detail(),parsed.cells().get(8).detail(),"evidence round trip");
        equal("",parsed.cells().get(7).detail(),"previous page evidence omitted");
        equal("",parsed.cells().get(16).detail(),"next page evidence omitted");
        equal(true,parsed.running(),"running state");
        equal(3L,parsed.count(Status.SAMPLE_MATCH),"status counts from map");
        equal("IDLE",OceanCanvasPhysicalHealth.decode("").state(),"empty response");
        equal("IDLE",OceanCanvasPhysicalHealth.decode("S\t0\t0").state(),"metadata not mistaken for physical");
        equal("ERROR",OceanCanvasPhysicalHealth.decode("P\tbroken").state(),"malformed header handled");
        equal("ERROR",OceanCanvasPhysicalHealth.decode("P\tRUNNING\t1\t0\t\t\nM\t0\t0\t999").state(),"malformed status handled");
        Report clamped=OceanCanvasPhysicalHealth.decode(OceanCanvasPhysicalHealth.encode(new Report("COMPLETE",20,Integer.MAX_VALUE,"","",cells)));
        equal(2,clamped.page(),"large page clamps without overflow");
        equal(cells.get(19).detail(),clamped.cells().get(19).detail(),"last page evidence");

        List<Cell> maximum=new ArrayList<>();
        for(int i=0;i<1024;i++)maximum.add(new Cell(-1875000+i,-1875000,Status.DIFFERENCE,"山".repeat(500)));
        String wire=OceanCanvasPhysicalHealth.encode(new Report("COMPLETE",1024,0,"山".repeat(500),"山".repeat(500),maximum));
        check(wire.getBytes(StandardCharsets.UTF_8).length<32767,"worst-size UTF-8 packet below STRING_UTF8 limit");
        equal(1024,OceanCanvasPhysicalHealth.decode(wire).cells().size(),"maximum map decodes");
        equal(180,OceanCanvasPhysicalHealth.decode(wire).cells().get(0).detail().length(),"bounded evidence");
        String diagnostic=OceanCanvasPhysicalHealth.diagnostic(new Report("PAUSED",20,0,"Test selection","kept",cells));
        check(diagnostic.contains("State: PAUSED")&&diagnostic.contains("SAMPLE_MATCH: 3"),"diagnostic summary");
        check(diagnostic.contains("-10,-2 SAMPLE_MATCH | evidence 0 with tabs"),"diagnostic evidence");
        List<Cell> manyEvidence=new ArrayList<>();
        for(int i=0;i<1024;i++)manyEvidence.add(new Cell(i,0,Status.DIFFERENCE,"x".repeat(512)));
        check(OceanCanvasPhysicalHealth.diagnostic(new Report("COMPLETE",1024,0,"","",manyEvidence)).length()<=263_000,"diagnostic cap");
        System.out.println("Physical Health offline checks passed: "+checks+"; maximum test packet "+wire.getBytes(StandardCharsets.UTF_8).length+" bytes.");
    }
    private static void rejects(Runnable action,String message){
        try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}
    }
    private static void equal(Object expected,Object actual,String message){check(expected.equals(actual),message+" expected="+expected+" actual="+actual);}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
