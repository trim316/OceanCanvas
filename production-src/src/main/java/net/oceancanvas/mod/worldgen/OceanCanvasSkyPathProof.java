package net.oceancanvas.mod.worldgen;

import java.util.PriorityQueue;
import java.util.HashSet;

/** Bounded constructive proof of lateral skylight; brightness alone is never a source. */
final class OceanCanvasSkyPathProof {
    record Position(int x, int y, int z) {}
    record Sample(int sky, boolean plainAirOrWater, boolean recomputedDirectSkySource) {}
    @FunctionalInterface interface Probe { Sample sample(int x, int y, int z); }
    private record Step(Position position, Sample sample) {}
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    static boolean supports(Probe probe, int x, int y, int z, int actual, int maxNodes, long deadlineNanos) {
        if (actual <= 1 || actual > 15 || maxNodes <= 0) return false;
        var root = new Position(x,y,z);
        Sample first = probe.sample(x,y,z);
        if (first == null || !first.plainAirOrWater() || first.sky() != actual) return false;
        var queue = new PriorityQueue<Step>((a,b) -> Integer.compare(b.sample.sky(),a.sample.sky()));
        var visited = new HashSet<Position>();
        queue.add(new Step(root,first)); visited.add(root);
        int inspected = 0;
        while (!queue.isEmpty()) {
            if (++inspected > maxNodes || System.nanoTime() >= deadlineNanos) return false;
            Step step = queue.remove();
            if (step.sample.sky() == 15 && step.sample.recomputedDirectSkySource()) return true;
            for (int[] direction : DIRECTIONS) {
                Position next = new Position(step.position.x()+direction[0], step.position.y()+direction[1], step.position.z()+direction[2]);
                if (visited.contains(next)) continue;
                Sample sample = probe.sample(next.x(),next.y(),next.z());
                // Each hop costs at least one level through plain air/water. Strictly
                // increasing values prevent cyclic/plateau fields from proving themselves.
                if (sample != null && sample.plainAirOrWater() && sample.sky() > step.sample.sky() && sample.sky() <= 15) {
                    visited.add(next);
                    queue.add(new Step(next,sample));
                }
            }
        }
        return false;
    }
}
