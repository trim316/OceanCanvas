package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.diagnostic.OceanCanvasStallWatchdog;

import java.util.ArrayList;
import java.util.List;

/** OC-F244 shared fail-closed workflow gate evaluation. Never mutates the world. */
public final class OceanCanvasWorkflowGateService {
    private OceanCanvasWorkflowGateService() {}

    public record Gate(String id,boolean pass,String detail) {}
    public record Result(String purpose,boolean pass,List<Gate> gates){
        public Result{purpose=purpose==null?"":purpose;gates=gates==null?List.of():List.copyOf(gates);}
        public String firstFailure(){return gates.stream().filter(g->!g.pass()).map(Gate::detail).findFirst().orElse("");}
        public String summary(){long failed=gates.stream().filter(g->!g.pass()).count();return failed==0?"All "+gates.size()+" conditional workflow gates pass.":failed+" of "+gates.size()+" workflow gate(s) block continuation: "+firstFailure();}
    }

    public static Result evaluate(ServerLevel world,String purpose){
        List<Gate> out=new ArrayList<>();
        String persistence=OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
        out.add(new Gate("persistence",persistence.isBlank(),persistence.isBlank()?"Persistence/schema contract writable.":persistence));

        var watchdog=OceanCanvasStallWatchdog.matrixSnapshot();
        boolean watchdogPass=!"ATTENTION".equals(watchdog.state());
        out.add(new Gate("watchdog",watchdogPass,watchdogPass?"No cause-owned watchdog row currently blocks continuation.":watchdog.detail()));

        var score=OceanCanvasWorldHealthScorecard.snapshot(world);
        long attention=score.attentionCount();
        out.add(new Gate("health",attention==0,attention==0?"No authoritative stewardship component is in ATTENTION.":attention+" stewardship component(s) require attention."));

        var canary=OceanCanvasBoundaryCanaryData.get(world).latestResult();
        boolean canaryPass=canary==null || !"FAIL".equals(canary.state());
        out.add(new Gate("boundary-canary",canaryPass,canaryPass?(canary==null?"No prior boundary-canary failure recorded.":canary.detail()):"Last boundary canary failed: "+canary.detail()));

        return new Result(purpose,out.stream().allMatch(Gate::pass),out);
    }
}
