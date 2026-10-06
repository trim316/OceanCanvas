package net.oceancanvas.mod.worldgen;
import java.util.*;
import static net.oceancanvas.mod.worldgen.UnsupportedSkyRepairPolicy.*;
class UnsupportedSkyPolicyRegression {
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        Position root = new Position(-1010,23,-482);
        // A descending underground path reaches water SKY15 below the floor repair band.
        var field = new HashMap<Position, Sample>();
        for (int i=0;i<=9;i++) field.put(new Position(root.x(),23-i,root.z()),new Sample(6+i,true,false));
        Probe probe = p -> field.getOrDefault(p,new Sample(0,false,false));
        Result bad = discover(probe,root,6,256,Long.MAX_VALUE);
        require(bad.outcome() == Outcome.UNSUPPORTED && bad.cells().contains(new Position(root.x(),14,root.z())));
        require(discover(probe,root,6,4,Long.MAX_VALUE).outcome() == Outcome.INCOMPLETE);
        require(discover(probe,root,6,256,0).outcome() == Outcome.INCOMPLETE);
        require(discover(p -> null,root,6,256,Long.MAX_VALUE).cells().isEmpty());
        field.put(new Position(root.x(),14,root.z()),new Sample(15,true,true));
        Result supported = discover(probe,root,6,256,Long.MAX_VALUE);
        require(supported.outcome() == Outcome.SUPPORTED && supported.cells().isEmpty());
        field.put(root,new Sample(6,true,false));
        require(discover(p -> p.equals(root)?field.get(p):null,root,6,256,Long.MAX_VALUE).outcome() == Outcome.INCOMPLETE);
        System.out.println("UNSUPPORTED_SKY_DISCOVERY_POLICY_PASS");
    }
}
