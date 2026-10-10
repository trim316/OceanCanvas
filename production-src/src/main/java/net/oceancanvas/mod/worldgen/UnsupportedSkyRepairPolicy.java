package net.oceancanvas.mod.worldgen;
import java.util.*;

/** Bounded discovery of unsupported rising skylight; never grants certification. */
class UnsupportedSkyRepairPolicy {
    record Position(int x, int y, int z) {}
    record Sample(int sky, boolean plain, boolean directSource) {}
    interface Probe { Sample sample(Position position); }
    enum Outcome { SUPPORTED, UNSUPPORTED, INCOMPLETE }
    record Result(Outcome outcome, List<Position> cells) {}
    static Result discover(Probe probe, Position root, int actual, int limit, long deadline) {
        if (actual <= 1 || actual > 15 || limit <= 0) return new Result(Outcome.INCOMPLETE, List.of());
        Sample first = probe.sample(root);
        if (first == null || !first.plain || first.sky != actual) return new Result(Outcome.INCOMPLETE, List.of());
        var cells = new LinkedHashMap<Position, Sample>();
        var queue = new ArrayDeque<Position>();
        cells.put(root, first); queue.add(root);
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (!queue.isEmpty()) {
            if (System.nanoTime() >= deadline) return new Result(Outcome.INCOMPLETE, List.of());
            Position p = queue.remove(); Sample s = cells.get(p);
            if (s.sky == 15 && s.directSource) return new Result(Outcome.SUPPORTED, List.of());
            for (int[] d : directions) {
                if (System.nanoTime() >= deadline) return new Result(Outcome.INCOMPLETE, List.of());
                Position n = new Position(p.x+d[0], p.y+d[1], p.z+d[2]);
                if (cells.containsKey(n)) continue;
                Sample next = probe.sample(n);
                // Missing samples are unknown, not evidence of an unsupported field.
                if (next == null) return new Result(Outcome.INCOMPLETE, List.of());
                // Follow a non-decreasing SKY path, not only a strictly increasing one.
                // Vanilla can propagate equal-strength skylight through open cells before a
                // path rises again. Declaring that plateau unsupported would make a future
                // repair integration destructive: a genuine direct SKY15 source could be
                // reachable beyond the plateau. The traversal is still bounded by `limit`;
                // a large/ambiguous plateau therefore fails closed as INCOMPLETE.
                if (!next.plain || next.sky < s.sky || next.sky > 15) continue;
                if (cells.size() >= limit) return new Result(Outcome.INCOMPLETE, List.of());
                cells.put(n, next); queue.add(n);
            }
        }
        return new Result(Outcome.UNSUPPORTED, List.copyOf(cells.keySet()));
    }
}
