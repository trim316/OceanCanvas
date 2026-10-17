package net.oceancanvas.mod.worldgen;
import java.nio.file.*;
import java.util.*;
class SavedSkyRepairReplay {
    public static void main(String[] args) throws Exception {
        var samples=new HashMap<UnsupportedSkyRepairPolicy.Position,UnsupportedSkyRepairPolicy.Sample>();
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            String[] f=line.split(",");
            samples.put(new UnsupportedSkyRepairPolicy.Position(Integer.parseInt(f[0]),Integer.parseInt(f[1]),Integer.parseInt(f[2])),new UnsupportedSkyRepairPolicy.Sample(Integer.parseInt(f[3]),Boolean.parseBoolean(f[4]),Boolean.parseBoolean(f[5])));
        }
        int[] zs={-482,-478};
        for(int z:zs) {
            var root=new UnsupportedSkyRepairPolicy.Position(-1010,23,z);
            var result=UnsupportedSkyRepairPolicy.discover(samples::get,root,samples.get(root).sky(),256,Long.MAX_VALUE);
            // The retained diagnostic capture was collected for the old strictly-increasing
            // traversal and does not contain every equal-SKY neighbor now required to prove
            // that no direct source exists beyond a plateau. Missing boundary samples are
            // therefore ambiguity, not evidence of unsupported light. Preserve this replay as
            // a fail-closed regression: these historical graphs must never grant mutation
            // authority under the safer non-decreasing traversal.
            UnsupportedSkyPolicyRegression.require(result.outcome()==UnsupportedSkyRepairPolicy.Outcome.INCOMPLETE);
            UnsupportedSkyPolicyRegression.require(result.cells().isEmpty());
            System.out.println("SAVED_SKY_REPAIR_REPLAY_FAIL_CLOSED root="+root+" outcome=INCOMPLETE cells=0");
        }
    }
}
