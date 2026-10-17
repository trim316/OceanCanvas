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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Forward-compatible project metadata for the features that sit ABOVE the core
 * terrain/rule engine: region templates, planning notes/stages, milestones and
 * machine-performance learning. Keeping this separate from PlayerZones means
 * none of those organizational features can make an old region undecodable.
 */
public final class OceanCanvasProjectData extends SavedData {
    public static final int CURRENT_SCHEMA = 3;
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "project_data");

    public enum RegionStage {
        RESERVED, TERRAIN_CONSTRUCTION, DETAILING, COMPLETE, ARCHIVED;

        public static RegionStage parse(String raw) {
            if (raw == null) return RESERVED;
            try { return valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return RESERVED; }
        }
    }

    public enum PregenProfile {
        QUIET, BALANCED, OVERNIGHT, CUSTOM;
        public static PregenProfile parse(String raw){
            if(raw==null)return BALANCED;
            try{return valueOf(raw.trim().toUpperCase(Locale.ROOT));}catch(Exception ignored){return BALANCED;}
        }
    }

    public record RegionMeta(String regionName, String stage, String notes, String templateId) {
        public RegionMeta {
            regionName = regionName == null ? "" : regionName;
            stage = RegionStage.parse(stage).name();
            notes = notes == null ? "" : notes;
            templateId = templateId == null ? "" : templateId;
        }
        public RegionStage parsedStage() { return RegionStage.parse(stage); }
    }

    /**
     * Template rule payload intentionally stores structure rules in the same
     * compact format used by ZoneSyncPayload. This avoids coupling SavedData to
     * the current structure enum and lets future structure kinds round-trip.
     */
    public record RegionTemplate(String id, String displayName, boolean protectedByDefault,
                                 String structureRules, String biomeOverride,
                                 boolean suppressHostileMobs, String color) {
        public RegionTemplate {
            id = normalizeId(id);
            displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
            structureRules = structureRules == null ? "" : structureRules;
            biomeOverride = biomeOverride == null ? "" : biomeOverride;
            color = color == null ? "" : color;
        }
    }

    /** Last stable, provenance-tagged machine/world sample. Version zero is historical display-only data. */
    public record BenchmarkProfile(double sustainableChunksPerSecond, double healthyTickMs,
                                   int preferredOutstanding, long updatedEpochMillis,
                                   int calibrationVersion, int successfulRuns, int samples, String observedProfile) {
        public BenchmarkProfile(double sustainableChunksPerSecond,double healthyTickMs,int preferredOutstanding,long updatedEpochMillis){
            this(sustainableChunksPerSecond,healthyTickMs,preferredOutstanding,updatedEpochMillis,0,0,0,"");
        }
        public BenchmarkProfile {
            observedProfile=observedProfile==null?"":observedProfile;
        }
    }

    private static final Codec<RegionMeta> REGION_META_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("regionName").forGetter(RegionMeta::regionName),
            Codec.STRING.optionalFieldOf("stage", RegionStage.RESERVED.name()).forGetter(RegionMeta::stage),
            Codec.STRING.optionalFieldOf("notes", "").forGetter(RegionMeta::notes),
            Codec.STRING.optionalFieldOf("templateId", "").forGetter(RegionMeta::templateId)
    ).apply(instance, RegionMeta::new));

    private static final Codec<RegionTemplate> TEMPLATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(RegionTemplate::id),
            Codec.STRING.fieldOf("displayName").forGetter(RegionTemplate::displayName),
            Codec.BOOL.optionalFieldOf("protectedByDefault", true).forGetter(RegionTemplate::protectedByDefault),
            Codec.STRING.optionalFieldOf("structureRules", "").forGetter(RegionTemplate::structureRules),
            Codec.STRING.optionalFieldOf("biomeOverride", "").forGetter(RegionTemplate::biomeOverride),
            Codec.BOOL.optionalFieldOf("suppressHostileMobs", false).forGetter(RegionTemplate::suppressHostileMobs),
            Codec.STRING.optionalFieldOf("color", "").forGetter(RegionTemplate::color)
    ).apply(instance, RegionTemplate::new));

    private static final Codec<BenchmarkProfile> BENCHMARK_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("sustainableChunksPerSecond", 0.0D).forGetter(BenchmarkProfile::sustainableChunksPerSecond),
            Codec.DOUBLE.optionalFieldOf("healthyTickMs", 50.0D).forGetter(BenchmarkProfile::healthyTickMs),
            Codec.INT.optionalFieldOf("preferredOutstanding", 0).forGetter(BenchmarkProfile::preferredOutstanding),
            Codec.LONG.optionalFieldOf("updatedEpochMillis", 0L).forGetter(BenchmarkProfile::updatedEpochMillis),
            Codec.INT.optionalFieldOf("calibrationVersion", 0).forGetter(BenchmarkProfile::calibrationVersion),
            Codec.INT.optionalFieldOf("successfulRuns", 0).forGetter(BenchmarkProfile::successfulRuns),
            Codec.INT.optionalFieldOf("samples", 0).forGetter(BenchmarkProfile::samples),
            Codec.STRING.optionalFieldOf("observedProfile", "").forGetter(BenchmarkProfile::observedProfile)
    ).apply(instance, BenchmarkProfile::new));

    private static final Codec<OceanCanvasProjectData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema", CURRENT_SCHEMA).forGetter(data -> data.schema),
            REGION_META_CODEC.listOf().optionalFieldOf("regions", List.of()).forGetter(data -> new ArrayList<>(data.regionMeta.values())),
            TEMPLATE_CODEC.listOf().optionalFieldOf("templates", List.of()).forGetter(data -> new ArrayList<>(data.templates.values())),
            Codec.STRING.listOf().optionalFieldOf("milestones", List.of()).forGetter(data -> new ArrayList<>(data.milestones)),
            BENCHMARK_CODEC.listOf().optionalFieldOf("benchmark", List.of()).forGetter(data -> data.benchmark == null ? List.of() : List.of(data.benchmark)),
            Codec.STRING.optionalFieldOf("currentProject", "").forGetter(data -> data.currentProject),
            Codec.STRING.optionalFieldOf("pregenProfile", PregenProfile.BALANCED.name()).forGetter(data -> data.pregenProfile)
    ).apply(instance, OceanCanvasProjectData::new));

    public static final SavedDataType<OceanCanvasProjectData> TYPE =
            new SavedDataType<>(DATA_ID, OceanCanvasProjectData::new, CODEC, null);

    private int schema;
    private final Map<String, RegionMeta> regionMeta = new LinkedHashMap<>();
    private final Map<String, RegionTemplate> templates = new LinkedHashMap<>();
    private final Set<String> milestones = new LinkedHashSet<>();
    private BenchmarkProfile benchmark;
    private String currentProject = "";
    private String pregenProfile = PregenProfile.BALANCED.name();

    public OceanCanvasProjectData() {
        this(CURRENT_SCHEMA, List.of(), List.of(), List.of(), List.of(), "", PregenProfile.BALANCED.name());
        installBuiltInTemplates();
    }

    private OceanCanvasProjectData(int schema, List<RegionMeta> regions, List<RegionTemplate> templates,
                                   List<String> milestones, List<BenchmarkProfile> benchmark, String currentProject, String pregenProfile) {
        this.schema = Math.max(CURRENT_SCHEMA, schema);
        for (RegionMeta meta : regions) this.regionMeta.put(key(meta.regionName()), meta);
        for (RegionTemplate template : templates) this.templates.put(template.id(), template);
        this.milestones.addAll(milestones);
        this.benchmark = benchmark.isEmpty() ? null : benchmark.get(0);
        this.currentProject = currentProject == null ? "" : currentProject;
        this.pregenProfile = PregenProfile.parse(pregenProfile).name();
        installBuiltInTemplates();
    }

    public static OceanCanvasProjectData get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }

    private void installBuiltInTemplates() {
        putBuiltIn(new RegionTemplate("major_continent", "Major Continent", true, "", "", false, "BLUE"));
        putBuiltIn(new RegionTemplate("wild_ocean_island", "Wild Ocean Island", false, "", "", false, "CYAN"));
        putBuiltIn(new RegionTemplate("future_build_area", "Future Build Area", true, "", "", true, "YELLOW"));
    }

    private void putBuiltIn(RegionTemplate template) { templates.putIfAbsent(template.id(), template); }
    private static String key(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }
    private static String normalizeId(String id) {
        String value = key(id).replace(' ', '_');
        return value.isBlank() ? "template" : value;
    }

    public RegionMeta regionMeta(String regionName) { return regionMeta.get(key(regionName)); }
    public List<RegionTemplate> templates() { return List.copyOf(templates.values()); }
    public Set<String> milestones() { return Set.copyOf(milestones); }
    public BenchmarkProfile benchmark() { return benchmark; }
    public int schema() { return schema; }
    /** Unknown future project schemas are loadable but not safe for destructive mutation. */
    public boolean schemaSupportedForMutation() { return schema <= CURRENT_SCHEMA; }
    public String currentProject() { return currentProject; }
    public PregenProfile pregenProfile(){ return PregenProfile.parse(pregenProfile); }
    public void setPregenProfile(PregenProfile profile){
        String next=(profile==null?PregenProfile.BALANCED:profile).name();
        if(!next.equals(this.pregenProfile)){this.pregenProfile=next;setDirty();}
    }

    public void setRegionMeta(String regionName, RegionStage stage, String notes, String templateId) {
        regionMeta.put(key(regionName), new RegionMeta(regionName, stage.name(), notes, templateId));
        setDirty();
    }

    public RegionMeta ensureRegionMeta(String regionName) {
        RegionMeta existing = regionMeta(regionName);
        if (existing != null) return existing;
        RegionMeta created = new RegionMeta(regionName, RegionStage.RESERVED.name(), "", "");
        regionMeta.put(key(regionName), created);
        setDirty();
        return created;
    }

    public void setRegionStage(String regionName, RegionStage stage) {
        RegionMeta old = ensureRegionMeta(regionName);
        setRegionMeta(regionName, stage, old.notes(), old.templateId());
    }

    public void setRegionNotes(String regionName, String notes) {
        RegionMeta old = ensureRegionMeta(regionName);
        setRegionMeta(regionName, old.parsedStage(), notes, old.templateId());
    }

    public void setRegionTemplate(String regionName, String templateId) {
        RegionMeta old = ensureRegionMeta(regionName);
        setRegionMeta(regionName, old.parsedStage(), old.notes(), templateId);
    }

    /** Keeps metadata attached when a PlayerZone is renamed. */
    public void renameRegion(String oldName, String newName) {
        RegionMeta old = regionMeta.remove(key(oldName));
        boolean changed = false;
        if (old != null) {
            regionMeta.put(key(newName), new RegionMeta(newName, old.stage(), old.notes(), old.templateId()));
            changed = true;
        }
        if (currentProject.equalsIgnoreCase(oldName == null ? "" : oldName.trim())) {
            currentProject = newName == null ? "" : newName.trim();
            changed = true;
        }
        if (changed) setDirty();
    }

    public void removeRegion(String name) {
        boolean changed = regionMeta.remove(key(name)) != null;
        if (currentProject.equalsIgnoreCase(name == null ? "" : name.trim())) {
            currentProject = "";
            changed = true;
        }
        if (changed) setDirty();
    }

    public void putTemplate(RegionTemplate template) {
        templates.put(template.id(), template);
        setDirty();
    }

    public boolean markMilestone(String id) {
        boolean added = milestones.add(normalizeId(id));
        if (added) setDirty();
        return added;
    }

    public void setBenchmark(BenchmarkProfile benchmark) {
        this.benchmark = benchmark;
        setDirty();
    }

    public void setCurrentProject(String regionName) {
        this.currentProject = regionName == null ? "" : regionName.trim();
        setDirty();
    }
}
