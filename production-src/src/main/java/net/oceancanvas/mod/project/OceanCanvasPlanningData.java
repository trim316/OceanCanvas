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
 * Versioned non-terrain planning model for imported reference images and traced
 * vector objects. The binary image itself is deliberately NOT placed in SavedData;
 * layers reference an asset id so image transport/storage can evolve without ever
 * making world terrain depend on a client image file.
 */
public final class OceanCanvasPlanningData extends SavedData {
    public static final int CURRENT_SCHEMA = 2;
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "planning_data");

    public enum ObjectType {
        CONTINENT, COASTLINE, MOUNTAIN_RANGE, RIDGELINE, PEAK, VALLEY, PLATEAU, BASIN, CLIFF, TERRAIN_ZONE, TERRAIN_PROFILE,
        RIVER, LAKE, CATCHMENT, BIOME_AREA, FOREST, DESERT, SETTLEMENT, DISTRICT, BUILD, LANDMARK, ROAD, PATH, BRIDGE, PORT, HARBOR,
        TRANSPORT_ROUTE, BORDER, REGION, CITY, FREEFORM_AREA, FREEFORM_LINE, TEXT;

        public static ObjectType parse(String raw) {
            if (raw == null) return FREEFORM_AREA;
            try { return valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return FREEFORM_AREA; }
        }
    }

    public record Point(int x, int z) { }
    public record ElevationPoint(double along, int y) { }
    public record RegistrationPoint(double imageX, double imageY, int worldX, int worldZ) { }

    public record ReferenceLayer(
            String id, String name, String assetId, boolean visible, double opacity, boolean locked,
            int minX, int minZ, int maxX, int maxZ, double rotationDegrees, int drawOrder,
            List<RegistrationPoint> registrationPoints, String notes) {
        public ReferenceLayer {
            id = normalizeId(id);
            name = name == null || name.isBlank() ? id : name.trim();
            assetId = assetId == null ? "" : assetId.trim();
            opacity = Math.max(0.0D, Math.min(1.0D, opacity));
            registrationPoints = registrationPoints == null ? List.of() : List.copyOf(registrationPoints);
            notes = notes == null ? "" : notes;
        }
    }

    public record PlanningObject(
            String id, String type, String name, List<Point> points, boolean visible, boolean locked,
            int drawOrder, String parentId, String notes, String guideData,
            int strokeArgb, int fillArgb, double widthBlocks, List<ElevationPoint> elevationProfile,
            String scenarioId, boolean implemented) {
        public PlanningObject {
            id = normalizeId(id);
            type = ObjectType.parse(type).name();
            name = name == null || name.isBlank() ? id : name.trim();
            points = points == null ? List.of() : List.copyOf(points);
            parentId = parentId == null ? "" : normalizeOptionalId(parentId);
            notes = notes == null ? "" : notes;
            guideData = guideData == null ? "" : guideData;
            widthBlocks = Math.max(0.0D, Math.min(10000.0D, widthBlocks));
            elevationProfile = elevationProfile == null ? List.of() : List.copyOf(elevationProfile);
            scenarioId = scenarioId == null ? "" : normalizeOptionalId(scenarioId);
        }
        public ObjectType parsedType() { return ObjectType.parse(type); }
    }

    private static final Codec<Point> POINT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(Point::x), Codec.INT.fieldOf("z").forGetter(Point::z)
    ).apply(instance, Point::new));

    private static final Codec<ElevationPoint> ELEVATION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("along").forGetter(ElevationPoint::along),
            Codec.INT.fieldOf("y").forGetter(ElevationPoint::y)
    ).apply(instance, ElevationPoint::new));

    private static final Codec<RegistrationPoint> REGISTRATION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("imageX").forGetter(RegistrationPoint::imageX),
            Codec.DOUBLE.fieldOf("imageY").forGetter(RegistrationPoint::imageY),
            Codec.INT.fieldOf("worldX").forGetter(RegistrationPoint::worldX),
            Codec.INT.fieldOf("worldZ").forGetter(RegistrationPoint::worldZ)
    ).apply(instance, RegistrationPoint::new));

    private static final Codec<ReferenceLayer> REFERENCE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(ReferenceLayer::id),
            Codec.STRING.fieldOf("name").forGetter(ReferenceLayer::name),
            Codec.STRING.optionalFieldOf("assetId", "").forGetter(ReferenceLayer::assetId),
            Codec.BOOL.optionalFieldOf("visible", true).forGetter(ReferenceLayer::visible),
            Codec.DOUBLE.optionalFieldOf("opacity", 0.6D).forGetter(ReferenceLayer::opacity),
            Codec.BOOL.optionalFieldOf("locked", true).forGetter(ReferenceLayer::locked),
            Codec.INT.optionalFieldOf("minX", 0).forGetter(ReferenceLayer::minX),
            Codec.INT.optionalFieldOf("minZ", 0).forGetter(ReferenceLayer::minZ),
            Codec.INT.optionalFieldOf("maxX", 0).forGetter(ReferenceLayer::maxX),
            Codec.INT.optionalFieldOf("maxZ", 0).forGetter(ReferenceLayer::maxZ),
            Codec.DOUBLE.optionalFieldOf("rotationDegrees", 0.0D).forGetter(ReferenceLayer::rotationDegrees),
            Codec.INT.optionalFieldOf("drawOrder", 0).forGetter(ReferenceLayer::drawOrder),
            REGISTRATION_CODEC.listOf().optionalFieldOf("registrationPoints", List.of()).forGetter(ReferenceLayer::registrationPoints),
            Codec.STRING.optionalFieldOf("notes", "").forGetter(ReferenceLayer::notes)
    ).apply(instance, ReferenceLayer::new));

    private static final Codec<PlanningObject> OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(PlanningObject::id),
            Codec.STRING.fieldOf("type").forGetter(PlanningObject::type),
            Codec.STRING.fieldOf("name").forGetter(PlanningObject::name),
            POINT_CODEC.listOf().optionalFieldOf("points", List.of()).forGetter(PlanningObject::points),
            Codec.BOOL.optionalFieldOf("visible", true).forGetter(PlanningObject::visible),
            Codec.BOOL.optionalFieldOf("locked", false).forGetter(PlanningObject::locked),
            Codec.INT.optionalFieldOf("drawOrder", 0).forGetter(PlanningObject::drawOrder),
            Codec.STRING.optionalFieldOf("parentId", "").forGetter(PlanningObject::parentId),
            Codec.STRING.optionalFieldOf("notes", "").forGetter(PlanningObject::notes),
            Codec.STRING.optionalFieldOf("guideData", "").forGetter(PlanningObject::guideData),
            Codec.INT.optionalFieldOf("strokeArgb", 0xD055FFFF).forGetter(PlanningObject::strokeArgb),
            Codec.INT.optionalFieldOf("fillArgb", 0x2055FFFF).forGetter(PlanningObject::fillArgb),
            Codec.DOUBLE.optionalFieldOf("widthBlocks", 0.0D).forGetter(PlanningObject::widthBlocks),
            ELEVATION_CODEC.listOf().optionalFieldOf("elevationProfile", List.of()).forGetter(PlanningObject::elevationProfile),
            Codec.STRING.optionalFieldOf("scenarioId", "").forGetter(PlanningObject::scenarioId),
            Codec.BOOL.optionalFieldOf("implemented", false).forGetter(PlanningObject::implemented)
    ).apply(instance, PlanningObject::new));

    private static final Codec<OceanCanvasPlanningData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema", CURRENT_SCHEMA).forGetter(data -> data.schema),
            REFERENCE_CODEC.listOf().optionalFieldOf("referenceLayers", List.of()).forGetter(data -> new ArrayList<>(data.referenceLayers.values())),
            OBJECT_CODEC.listOf().optionalFieldOf("objects", List.of()).forGetter(data -> new ArrayList<>(data.objects.values()))
    ).apply(instance, OceanCanvasPlanningData::new));

    public static final SavedDataType<OceanCanvasPlanningData> TYPE =
            new SavedDataType<>(DATA_ID, OceanCanvasPlanningData::new, CODEC, null);

    private int schema;
    private final Map<String, ReferenceLayer> referenceLayers = new LinkedHashMap<>();
    private final Map<String, PlanningObject> objects = new LinkedHashMap<>();

    public OceanCanvasPlanningData() { this(CURRENT_SCHEMA, List.of(), List.of()); }

    private OceanCanvasPlanningData(int schema, List<ReferenceLayer> refs, List<PlanningObject> objects) {
        this.schema = Math.max(1, schema);
        for (ReferenceLayer layer : refs) referenceLayers.put(layer.id(), layer);
        for (PlanningObject object : objects) this.objects.put(object.id(), object);
    }

    public static OceanCanvasPlanningData get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }

    public int schema() { return schema; }
    public List<ReferenceLayer> referenceLayers() { return List.copyOf(referenceLayers.values()); }
    public List<PlanningObject> objects() { return List.copyOf(objects.values()); }

    public ReferenceLayer referenceLayer(String id) { return referenceLayers.get(normalizeOptionalId(id)); }
    public PlanningObject object(String id) { return objects.get(normalizeOptionalId(id)); }

    public String newId(String prefix) { return normalizeId(prefix + "_" + UUID.randomUUID().toString().substring(0, 8)); }

    public void putReferenceLayer(ReferenceLayer layer) { referenceLayers.put(layer.id(), layer); setDirty(); }
    public void putObject(PlanningObject object) { objects.put(object.id(), object); setDirty(); }
    public boolean removeReferenceLayer(String id) { boolean changed = referenceLayers.remove(normalizeOptionalId(id)) != null; if (changed) setDirty(); return changed; }
    public boolean removeObject(String id) { boolean changed = objects.remove(normalizeOptionalId(id)) != null; if (changed) setDirty(); return changed; }
    public void replaceObjects(List<PlanningObject> replacement) { objects.clear(); for (PlanningObject object : replacement) objects.put(object.id(), object); setDirty(); }

    private static String normalizeId(String raw) {
        String id = normalizeOptionalId(raw);
        return id.isBlank() ? "planning_item" : id;
    }

    private static String normalizeOptionalId(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
