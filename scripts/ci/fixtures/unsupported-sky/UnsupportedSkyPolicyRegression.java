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

        // Safety regression: a real source can sit beyond an equal-strength SKY plateau.
        // Strictly-increasing traversal incorrectly classified the root as UNSUPPORTED and
        // would make a future repair integration eligible to mutate supported light.
        Position plateauRoot = new Position(20,20,20);
        var plateau = new HashMap<Position, Sample>();
        plateau.put(plateauRoot, new Sample(6,true,false));
        plateau.put(new Position(21,20,20), new Sample(6,true,false));
        plateau.put(new Position(22,20,20), new Sample(7,true,false));
        plateau.put(new Position(23,20,20), new Sample(15,true,true));
        Probe plateauProbe = p -> plateau.getOrDefault(p, new Sample(0,false,false));
        Result plateauSupported = discover(plateauProbe, plateauRoot, 6, 32, Long.MAX_VALUE);
        require(plateauSupported.outcome() == Outcome.SUPPORTED && plateauSupported.cells().isEmpty());
        // A plateau larger than the traversal budget is ambiguous and must fail closed.
        var widePlateau = new HashMap<Position, Sample>();
        widePlateau.put(plateauRoot, new Sample(6,true,false));
        widePlateau.put(new Position(21,20,20), new Sample(6,true,false));
        widePlateau.put(new Position(22,20,20), new Sample(6,true,false));
        Probe widePlateauProbe = p -> widePlateau.getOrDefault(p, new Sample(0,false,false));
        require(discover(widePlateauProbe, plateauRoot, 6, 2, Long.MAX_VALUE).outcome() == Outcome.INCOMPLETE);
        System.out.println("UNSUPPORTED_SKY_DISCOVERY_POLICY_PASS");
    }
}
