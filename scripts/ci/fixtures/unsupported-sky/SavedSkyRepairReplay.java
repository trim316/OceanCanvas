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
        int[] zs={-482,-478}; int[] counts={36,12}; int[] ends={-480,-478};
        for(int i=0;i<zs.length;i++) {
            var root=new UnsupportedSkyRepairPolicy.Position(-1010,23,zs[i]);
            var result=UnsupportedSkyRepairPolicy.discover(samples::get,root,samples.get(root).sky(),256,Long.MAX_VALUE);
            UnsupportedSkyPolicyRegression.require(result.outcome()==UnsupportedSkyRepairPolicy.Outcome.UNSUPPORTED);
            UnsupportedSkyPolicyRegression.require(result.cells().size()==counts[i]);
            UnsupportedSkyPolicyRegression.require(result.cells().contains(new UnsupportedSkyRepairPolicy.Position(-1008,14,ends[i])));
            System.out.println("SAVED_SKY_REPAIR_REPLAY_PASS root="+root+" cells="+result.cells().size()+" includesY14=true");
        }
    }
}
