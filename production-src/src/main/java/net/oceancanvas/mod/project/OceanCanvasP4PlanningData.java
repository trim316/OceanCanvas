package net.oceancanvas.mod.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * P4 Creative Planning metadata. This references canonical Plan geometry instead of duplicating
 * it. Artifacts are advisory planning annotations only: they never paint blocks and never become
 * world-generation authority.
 */
public final class OceanCanvasP4PlanningData extends SavedData {
    public static final int CURRENT_SCHEMA = 1;
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "p4_planning_data");

    public enum Kind {
        NEGATIVE_SPACE, DESIGN_RATIONALE, TERRAIN_STORY_BEAT, SCALE_STAMP, REFERENCE_BOARD,
        TERRAIN_INTENT, REFERENCE_ALIGNMENT, BIOME_TRANSITION, LANDMARK_SIGHTLINE,
        PLAYABLE_SPACE_BUDGET, MOTIF, ECOLOGICAL_CORRIDOR, VIEW_CORRIDOR, ORGANIC_SUBDIVISION;

        public static Kind parse(String raw) {
            if (raw == null) return DESIGN_RATIONALE;
            try { return valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return DESIGN_RATIONALE; }
        }
    }

    public record Artifact(String id, String kind, String name, String targetId,
                           List<OceanCanvasPlanningData.Point> points,
                           double valueA, double valueB, String text, long createdAt) {
        public Artifact {
            id = norm(id);
            kind = Kind.parse(kind).name();
            name = clean(name, kind.replace('_', ' '));
            targetId = opt(targetId);
            points = points == null ? List.of() : List.copyOf(points.stream().limit(512).toList());
            if (!Double.isFinite(valueA)) valueA = 0D;
            if (!Double.isFinite(valueB)) valueB = 0D;
            text = text == null ? "" : text.substring(0, Math.min(text.length(), 4096));
            createdAt = Math.max(0L, createdAt);
        }
        public Kind parsedKind() { return Kind.parse(kind); }
    }

    private static final Codec<OceanCanvasPlanningData.Point> POINT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("x").forGetter(OceanCanvasPlanningData.Point::x),
            Codec.INT.fieldOf("z").forGetter(OceanCanvasPlanningData.Point::z)
    ).apply(i, OceanCanvasPlanningData.Point::new));

    private static final Codec<Artifact> ARTIFACT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(Artifact::id),
            Codec.STRING.fieldOf("kind").forGetter(Artifact::kind),
            Codec.STRING.fieldOf("name").forGetter(Artifact::name),
            Codec.STRING.optionalFieldOf("targetId", "").forGetter(Artifact::targetId),
            POINT_CODEC.listOf().optionalFieldOf("points", List.of()).forGetter(Artifact::points),
            Codec.DOUBLE.optionalFieldOf("valueA", 0D).forGetter(Artifact::valueA),
            Codec.DOUBLE.optionalFieldOf("valueB", 0D).forGetter(Artifact::valueB),
            Codec.STRING.optionalFieldOf("text", "").forGetter(Artifact::text),
            Codec.LONG.optionalFieldOf("createdAt", 0L).forGetter(Artifact::createdAt)
    ).apply(i, Artifact::new));

    private static final Codec<OceanCanvasP4PlanningData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("schema", CURRENT_SCHEMA).forGetter(d -> d.schema),
            ARTIFACT_CODEC.listOf().optionalFieldOf("artifacts", List.of()).forGetter(d -> new ArrayList<>(d.artifacts.values()))
    ).apply(i, OceanCanvasP4PlanningData::new));

    public static final SavedDataType<OceanCanvasP4PlanningData> TYPE =
            new SavedDataType<>(DATA_ID, OceanCanvasP4PlanningData::new, CODEC, null);

    private int schema;
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();

    public OceanCanvasP4PlanningData() { this(CURRENT_SCHEMA, List.of()); }
    private OceanCanvasP4PlanningData(int schema, List<Artifact> artifacts) {
        this.schema = Math.max(1, schema);
        if (artifacts != null) for (Artifact a : artifacts) this.artifacts.put(a.id(), a);
    }

    public static OceanCanvasP4PlanningData get(ServerLevel world) { return world.getDataStorage().computeIfAbsent(TYPE); }
    public int schema() { return schema; }
    public List<Artifact> artifacts() { return List.copyOf(artifacts.values()); }
    public Artifact artifact(String id) { return artifacts.get(opt(id)); }
    public List<Artifact> artifacts(Kind kind) { return artifacts.values().stream().filter(v -> v.parsedKind() == kind).toList(); }
    public String newId(String prefix) { return norm(prefix + "_" + UUID.randomUUID().toString().substring(0, 8)); }
    public void put(Artifact artifact) { artifacts.put(artifact.id(), artifact); setDirty(); }
    public boolean remove(String id) { boolean changed = artifacts.remove(opt(id)) != null; if (changed) setDirty(); return changed; }

    private static String opt(String raw) { return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_'); }
    private static String norm(String raw) { String s = opt(raw); return s.isBlank() ? "p4_item" : s; }
    private static String clean(String raw, String fallback) { return raw == null || raw.isBlank() ? fallback : raw.trim(); }
}
