package net.oceancanvas.mod.worldgen;

/**
 * Orthogonal world-authoring policy for the canonical Ocean Canvas surface.
 *
 * <p>Terrain preparation, ecology, structure rules and entity preservation are separate concerns.
 * Keeping them explicit prevents a terrain operation from quietly becoming an ecology decorator or
 * an entity cleanup pass. Structure-specific Never/Default/Always rules remain in the existing
 * configuration/Region model; this class defines the higher-level ownership boundary.</p>
 */
public record OceanCanvasWorldPolicy(
        TerrainPolicy terrain,
        EcologyPolicy ecology,
        EntityPolicy entities,
        StructurePolicy structures) {

    public enum TerrainPolicy { CANVAS_OCEAN }
    public enum EcologyPolicy { VANILLA_BIOME_BEHAVIOR_NO_OC_DECORATION }
    public enum EntityPolicy { PRESERVE_EXISTING }
    public enum StructurePolicy { CONFIG_AND_REGION_RULES }

    private static final OceanCanvasWorldPolicy CANONICAL = new OceanCanvasWorldPolicy(
            TerrainPolicy.CANVAS_OCEAN,
            EcologyPolicy.VANILLA_BIOME_BEHAVIOR_NO_OC_DECORATION,
            EntityPolicy.PRESERVE_EXISTING,
            StructurePolicy.CONFIG_AND_REGION_RULES);

    public static OceanCanvasWorldPolicy canonical() { return CANONICAL; }
}
