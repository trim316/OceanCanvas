package net.oceancanvas.mod.planning;

import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.project.OceanCanvasP4PlanningData;
import net.oceancanvas.mod.project.OceanCanvasPlanningData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Deterministic, advisory-only P4 analysis over canonical Plan geometry. */
public final class OceanCanvasP4PlanningService {
    private OceanCanvasP4PlanningService() {}

    public record Finding(String feature, String targetId, int severity, int x, int z, String message) {
        public Finding {
            severity = Math.max(0, Math.min(100, severity));
            message = message == null ? "" : message;
        }
    }

    public static List<Finding> analyze(ServerLevel world) {
        var planning = OceanCanvasPlanningData.get(world);
        var p4 = OceanCanvasP4PlanningData.get(world);
        var out = new ArrayList<Finding>();
        coastline(planning, out);
        repetition(planning, out);
        hydrology(planning, out);
        settlement(planning, out);
        harbor(planning, out);
        walkability(planning, out);
        designTension(p4, out);
        journeyRhythm(p4, out);
        sightlines(planning, p4, out);
        playableBudget(planning, p4, out);
        out.sort(Comparator.comparingInt(Finding::severity).reversed().thenComparing(Finding::feature).thenComparing(Finding::targetId));
        return List.copyOf(out.stream().limit(256).toList());
    }

    private static void coastline(OceanCanvasPlanningData data, List<Finding> out) {
        for (var o : data.objects()) {
            if (o.parsedType() != OceanCanvasPlanningData.ObjectType.COASTLINE || o.points().size() < 3) continue;
            var pts = o.points();
            double total = 0D;
            double chord = dist(pts.get(0), pts.get(pts.size() - 1));
            int longSeg = 0;
            for (int i = 1; i < pts.size(); i++) {
                double d = dist(pts.get(i - 1), pts.get(i));
                total += d;
                if (d > 1200) longSeg++;
            }
            double straight = total <= 1 ? 0 : chord / total;
            int sev = (int)Math.round(Math.max(straight * 90, Math.min(100, longSeg * 24)));
            if (sev >= 45) {
                var c = centroid(pts);
                out.add(new Finding("COASTLINE_CONSTRAINT", o.id(), sev, c[0], c[1],
                        String.format(Locale.ROOT,
                                "Coastline is %.0f%% chord-efficient with %d very long segment(s); add bays, capes, or intermediate controls.",
                                straight * 100, longSeg)));
            }
        }
    }

    private static void repetition(OceanCanvasPlanningData data, List<Finding> out) {
        for (var o : data.objects()) {
            if (o.points().size() < 5) continue;
            var lens = new ArrayList<Double>();
            for (int i = 1; i < o.points().size(); i++) lens.add(dist(o.points().get(i - 1), o.points().get(i)));
            double mean = lens.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (mean < 1) continue;
            double variance = lens.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
            double cv = Math.sqrt(variance) / mean;
            if (cv < 0.16) {
                var c = centroid(o.points());
                int sev = (int)Math.round((0.16 - cv) / 0.16 * 85);
                out.add(new Finding("SYMMETRY_REPETITION", o.id(), sev, c[0], c[1],
                        String.format(Locale.ROOT, "Segment rhythm is highly regular (CV %.2f); vary spacing/turn cadence for a more organic silhouette.", cv)));
            }
        }
    }

    private static void hydrology(OceanCanvasPlanningData data, List<Finding> out) {
        var sinks = data.objects().stream().filter(o -> switch (o.parsedType()) {
            case LAKE, BASIN, CATCHMENT, COASTLINE -> true;
            default -> false;
        }).toList();
        for (var river : data.objects()) {
            if (river.parsedType() != OceanCanvasPlanningData.ObjectType.RIVER || river.points().size() < 2) continue;
            var end = river.points().get(river.points().size() - 1);
            double best = Double.POSITIVE_INFINITY;
            OceanCanvasPlanningData.PlanningObject sink = null;
            for (var s : sinks) {
                if (s.id().equals(river.id())) continue;
                double d = minDistance(end, s.points());
                if (d < best) { best = d; sink = s; }
            }
            int sev = (int)Math.min(100, Math.max(0, (best - 96) / 8));
            if (best > 96) {
                out.add(new Finding("WATERSHED_CONFLICT", river.id(), sev, end.x(), end.z(),
                        "River endpoint is " + Math.round(best) + " blocks from the nearest modeled sink/coast; hydrology dependency is unresolved."));
            } else if (sink != null) {
                out.add(new Finding("HYDROLOGY_GRAPH", river.id(), 10, end.x(), end.z(),
                        "Flows toward " + sink.name() + " (" + Math.round(best) + " blocks from endpoint)."));
            }
        }
    }

    private static void settlement(OceanCanvasPlanningData data, List<Finding> out) {
        var water = data.objects().stream().filter(o -> switch (o.parsedType()) {
            case RIVER, LAKE, COASTLINE, HARBOR, PORT -> true;
            default -> false;
        }).toList();
        for (var s : data.objects()) {
            if ((s.parsedType() != OceanCanvasPlanningData.ObjectType.SETTLEMENT && s.parsedType() != OceanCanvasPlanningData.ObjectType.CITY) || s.points().isEmpty()) continue;
            var p = s.points().get(0);
            double near = water.stream().mapToDouble(w -> minDistance(p, w.points())).min().orElse(2000);
            int score = (int)Math.round(100 - Math.min(80, near / 16));
            score = Math.max(0, Math.min(100, score));
            out.add(new Finding("SETTLEMENT_SUITABILITY", s.id(), 100 - score, p.x(), p.z(),
                    "Suitability " + score + "/100; nearest planned water/harbor feature " + Math.round(near) + " blocks."));
        }
    }

    private static void harbor(OceanCanvasPlanningData data, List<Finding> out) {
        var coasts = data.objects().stream().filter(o -> o.parsedType() == OceanCanvasPlanningData.ObjectType.COASTLINE).toList();
        for (var h : data.objects()) {
            if ((h.parsedType() != OceanCanvasPlanningData.ObjectType.HARBOR && h.parsedType() != OceanCanvasPlanningData.ObjectType.PORT) || h.points().isEmpty()) continue;
            var p = h.points().get(0);
            double near = coasts.stream().mapToDouble(c -> minDistance(p, c.points())).min().orElse(5000);
            int score = (int)Math.round(100 - Math.min(100, near / 6));
            out.add(new Finding("HARBOR_QUALITY", h.id(), 100 - score, p.x(), p.z(),
                    "Harbor quality " + Math.max(0, score) + "/100 from coastline access; nearest coast " + Math.round(near) + " blocks."));
        }
    }

    private static void walkability(OceanCanvasPlanningData data, List<Finding> out) {
        var networks = data.objects().stream().filter(o -> switch (o.parsedType()) {
            case ROAD, PATH, BRIDGE, TRANSPORT_ROUTE -> true;
            default -> false;
        }).toList();
        for (var o : data.objects()) {
            if ((o.parsedType() != OceanCanvasPlanningData.ObjectType.LANDMARK && o.parsedType() != OceanCanvasPlanningData.ObjectType.SETTLEMENT && o.parsedType() != OceanCanvasPlanningData.ObjectType.CITY) || o.points().isEmpty()) continue;
            var p = o.points().get(0);
            double near = networks.stream().mapToDouble(n -> minDistance(p, n.points())).min().orElse(3000);
            int score = (int)Math.round(100 - Math.min(100, near / 12));
            out.add(new Finding("WALKABILITY", o.id(), 100 - score, p.x(), p.z(),
                    "Walkability " + Math.max(0, score) + "/100; nearest planned walking/transport network " + Math.round(near) + " blocks."));
        }
    }

    private static void designTension(OceanCanvasP4PlanningData p4, List<Finding> out) {
        var intents = p4.artifacts(OceanCanvasP4PlanningData.Kind.TERRAIN_INTENT);
        for (int i = 0; i < intents.size(); i++) {
            for (int j = i + 1; j < intents.size(); j++) {
                var a = intents.get(i);
                var b = intents.get(j);
                if (a.points().isEmpty() || b.points().isEmpty()) continue;
                var ca = centroid(a.points());
                var cb = centroid(b.points());
                double d = Math.hypot(ca[0] - cb[0], ca[1] - cb[1]);
                if (d < 256 && !a.text().equalsIgnoreCase(b.text())) {
                    out.add(new Finding("DESIGN_TENSION", a.targetId(), (int)Math.min(100, 100 - d / 3),
                            (ca[0] + cb[0]) / 2, (ca[1] + cb[1]) / 2,
                            "Conflicting terrain intents '" + a.text() + "' and '" + b.text() + "' overlap within " + Math.round(d) + " blocks."));
                }
            }
        }
    }

    private static void journeyRhythm(OceanCanvasP4PlanningData p4, List<Finding> out) {
        var beats = p4.artifacts(OceanCanvasP4PlanningData.Kind.TERRAIN_STORY_BEAT);
        if (beats.size() < 2) return;
        var sorted = new ArrayList<>(beats);
        sorted.sort(Comparator.comparingLong(OceanCanvasP4PlanningData.Artifact::createdAt));
        var gaps = new ArrayList<Double>();
        for (int i = 1; i < sorted.size(); i++) {
            var a = first(sorted.get(i - 1));
            var b = first(sorted.get(i));
            if (a != null && b != null) gaps.add(dist(a, b));
        }
        if (gaps.isEmpty()) return;
        double mean = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = gaps.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        double cv = mean <= 0 ? 0 : Math.sqrt(variance) / mean;
        var p = first(sorted.get(sorted.size() - 1));
        out.add(new Finding("JOURNEY_RHYTHM", "world", (int)Math.min(100, cv * 70), p == null ? 0 : p.x(), p == null ? 0 : p.z(),
                String.format(Locale.ROOT, "%d story beats average %.0f blocks apart; spacing variation CV %.2f.", beats.size(), mean, cv)));
    }

    private static void sightlines(OceanCanvasPlanningData data, OceanCanvasP4PlanningData p4, List<Finding> out) {
        for (var a : p4.artifacts()) {
            if ((a.parsedKind() != OceanCanvasP4PlanningData.Kind.LANDMARK_SIGHTLINE && a.parsedKind() != OceanCanvasP4PlanningData.Kind.VIEW_CORRIDOR) || a.points().size() < 2) continue;
            var p = a.points().get(0);
            var q = a.points().get(a.points().size() - 1);
            double d = dist(p, q);
            int crossings = 0;
            for (var o : data.objects()) {
                if (o.points().size() < 3 || o.id().equals(a.targetId())) continue;
                if (segmentHitsBounds(p, q, o.points())) crossings++;
            }
            int sev = Math.min(100, crossings * 25);
            out.add(new Finding(a.parsedKind().name(), a.targetId(), sev, (p.x() + q.x()) / 2, (p.z() + q.z()) / 2,
                    "Corridor spans " + Math.round(d) + " blocks and crosses " + crossings + " planned area envelope(s)."));
        }
    }

    private static void playableBudget(OceanCanvasPlanningData data, OceanCanvasP4PlanningData p4, List<Finding> out) {
        double total = 0;
        for (var o : data.objects()) if (isClosedType(o.parsedType()) && o.points().size() >= 3) total += Math.abs(area(o.points()));
        double negative = 0;
        for (var a : p4.artifacts(OceanCanvasP4PlanningData.Kind.NEGATIVE_SPACE)) if (a.points().size() >= 3) negative += Math.abs(area(a.points()));
        if (total <= 0) return;
        double pct = Math.max(0, Math.min(100, (total - negative) / total * 100));
        out.add(new Finding("PLAYABLE_SPACE_BUDGET", "world", (int)Math.round(Math.max(0, 50 - pct) / 50 * 100), 0, 0,
                String.format(Locale.ROOT, "Modeled playable-space budget %.1f%% after %.0f blocks² of negative space (%.0f blocks² gross planned area).", pct, negative, total)));
    }

    private static boolean isClosedType(OceanCanvasPlanningData.ObjectType t) {
        return switch (t) {
            case CONTINENT, PLATEAU, BASIN, TERRAIN_ZONE, CATCHMENT, BIOME_AREA, FOREST, DESERT, SETTLEMENT, DISTRICT, BUILD, HARBOR, REGION, CITY, FREEFORM_AREA -> true;
            default -> false;
        };
    }
    private static OceanCanvasPlanningData.Point first(OceanCanvasP4PlanningData.Artifact a) { return a.points().isEmpty() ? null : a.points().get(0); }
    private static double dist(OceanCanvasPlanningData.Point a, OceanCanvasPlanningData.Point b) { return Math.hypot(a.x() - b.x(), a.z() - b.z()); }
    private static double minDistance(OceanCanvasPlanningData.Point p, List<OceanCanvasPlanningData.Point> pts) { double best = Double.POSITIVE_INFINITY; for (var q : pts) best = Math.min(best, dist(p, q)); return best; }
    private static int[] centroid(List<OceanCanvasPlanningData.Point> pts) { long x = 0, z = 0; for (var p : pts) { x += p.x(); z += p.z(); } return pts.isEmpty() ? new int[]{0,0} : new int[]{(int)(x / pts.size()), (int)(z / pts.size())}; }
    private static double area(List<OceanCanvasPlanningData.Point> pts) { double s = 0; for (int i = 0; i < pts.size(); i++) { var a = pts.get(i); var b = pts.get((i + 1) % pts.size()); s += (double)a.x() * b.z() - (double)b.x() * a.z(); } return s * 0.5; }
    private static boolean segmentHitsBounds(OceanCanvasPlanningData.Point a, OceanCanvasPlanningData.Point b, List<OceanCanvasPlanningData.Point> pts) { int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE; for(var p:pts){minX=Math.min(minX,p.x());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxZ=Math.max(maxZ,p.z());} int sx=Math.min(a.x(),b.x()),sz=Math.min(a.z(),b.z()),ex=Math.max(a.x(),b.x()),ez=Math.max(a.z(),b.z()); return ex>=minX&&sx<=maxX&&ez>=minZ&&sz<=maxZ; }
}
