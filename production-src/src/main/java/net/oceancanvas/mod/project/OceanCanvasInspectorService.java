package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;
import net.oceancanvas.mod.worldgen.OceanCanvasStructureKind;

import java.util.ArrayList;
import java.util.List;

/**
 * One authoritative explanation of "what applies here?" for both commands and
 * the future map inspector. Keeping rule provenance in one service prevents the
 * UI and command diagnostics from drifting apart.
 */
public final class OceanCanvasInspectorService {
    private OceanCanvasInspectorService() { }

    public record RuleExplanation(String label, String value, String source) { }

    public record Snapshot(
            int blockX, int blockZ, int chunkX, int chunkZ,
            OceanCanvasTerrainStateData.TerrainState terrainState,
            boolean processed,
            boolean protectedHere,
            List<String> containingRegions,
            List<RuleExplanation> rules,
            String provenanceClass, String provenanceDetail, String lastOperation) { }

    public static Snapshot inspect(ServerLevel world, int x, int z) {
        int y = world.getSeaLevel();
        ChunkPos chunk = new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        OceanCanvasPlayerZones zones = OceanCanvasPlayerZones.get(world);
        List<OceanCanvasPlayerZones.Zone> containing = zones.zonesAt(x, y, z);
        List<String> names = containing.stream().map(OceanCanvasPlayerZones.Zone::name).toList();

        List<RuleExplanation> rules = new ArrayList<>();
        String biome = zones.biomeOverrideAt(x, y, z);
        if (biome != null) {
            rules.add(new RuleExplanation("Biome", biome, firstRegionProvidingBiome(containing, x, y, z)));
        } else if (OceanCanvasConfig.get().biomeMaskEnabled()) {
            rules.add(new RuleExplanation("Biome", OceanCanvasConfig.get().biomeMaskBiome(), "World setting"));
        } else {
            rules.add(new RuleExplanation("Biome", "Vanilla", "World default"));
        }

        for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) {
            OceanCanvasPlayerZones.Zone source = firstRegionProvidingStructure(containing, kind);
            if (source != null) {
                var override = source.overrideFor(kind);
                rules.add(new RuleExplanation(kind.displayName(),
                        override == net.oceancanvas.mod.config.StructureOverride.FORCE_ON ? "Enabled" : "Disabled",
                        source.name()));
            } else {
                var worldDefault = kind.globalDefault(OceanCanvasConfig.get());
                rules.add(new RuleExplanation(kind.displayName(),
                        switch (worldDefault) {
                            case FORCE_ON -> "Enabled";
                            case FORCE_OFF -> "Disabled";
                            case INHERIT -> "Vanilla";
                        },
                        "World default"));
            }
        }

        OceanCanvasPlayerZones.Zone mobSource = containing.stream()
                .filter(zone -> zone.protectedNow() && zone.suppressHostileMobs()).findFirst().orElse(null);
        rules.add(new RuleExplanation("Hostile mobs", mobSource == null ? "Vanilla" : "Suppressed",
                mobSource == null ? "World default" : mobSource.name()));

        var terrainState=OceanCanvasTerrainStateData.get(world).get(chunk);
        boolean processed=OceanCanvasProtectedData.get(world).isChunkProcessed(chunk);
        String provenanceClass=switch(terrainState){
            case CANVAS -> processed?"CANVAS_TRACKED":"CANVAS_STATE_UNSEALED";
            case VANILLA -> "RESTORED_VANILLA";
            case CUSTOM_OR_MODIFIED -> "CUSTOM_OR_MODIFIED";
            case UNKNOWN -> processed?"LEGACY_PROCESSED_UNCLASSIFIED":"UNTRACKED";
        };
        String provenanceDetail=switch(provenanceClass){
            case "CANVAS_TRACKED" -> "Terrain ledger says CANVAS and Ocean Canvas has a processed completion marker for this chunk.";
            case "CANVAS_STATE_UNSEALED" -> "Terrain ledger says CANVAS but no processed completion marker is present; treat physical state as unverified.";
            case "RESTORED_VANILLA" -> "Terrain ledger records VANILLA provenance, normally produced by Restore to Vanilla.";
            case "CUSTOM_OR_MODIFIED" -> "Terrain is explicitly marked custom/modified; destructive operations must treat it as player-authored risk.";
            case "LEGACY_PROCESSED_UNCLASSIFIED" -> "A historical processed marker exists without an explicit terrain-state classification.";
            default -> "No authoritative Ocean Canvas terrain provenance is recorded for this chunk.";
        };
        String lastOperation=latestOperationForChunk(world,chunk.x(),chunk.z());
        return new Snapshot(
                x, z, chunk.x(), chunk.z(), terrainState, processed, zones.isProtected(x, y, z),
                List.copyOf(names), List.copyOf(rules),provenanceClass,provenanceDetail,lastOperation);
    }

    private static String firstRegionProvidingBiome(List<OceanCanvasPlayerZones.Zone> zones, int x, int y, int z) {
        for (OceanCanvasPlayerZones.Zone zone : zones) if (zone.biomeOverride() != null && zone.contains(x, y, z)) return zone.name();
        return "Region rule";
    }

    private static OceanCanvasPlayerZones.Zone firstRegionProvidingStructure(
            List<OceanCanvasPlayerZones.Zone> zones, OceanCanvasStructureKind kind) {
        for (OceanCanvasPlayerZones.Zone zone : zones) {
            if (zone.overrideFor(kind) != net.oceancanvas.mod.config.StructureOverride.INHERIT) return zone;
        }
        return null;
    }
    private static String latestOperationForChunk(ServerLevel world,int cx,int cz){
        for(var e:net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData.get(world).recent()){
            if(!e.hasScope()||cx<e.minChunkX()||cx>e.maxChunkX()||cz<e.minChunkZ()||cz>e.maxChunkZ())continue;
            if(!scopeContains(e.scopeDescriptor(),cx,cz))continue;
            return e.kind()+" "+e.phase()+" @ "+e.epochMillis()+" by "+e.requester();
        }
        return "none recorded";
    }

    private static boolean scopeContains(String descriptor,int cx,int cz){
        if(descriptor==null||descriptor.isBlank()||descriptor.startsWith("RECT:"))return true;
        try{
            if(descriptor.startsWith("RUNS:")){
                String body=descriptor.substring(5);
                for(String row:body.split(";")){String[] a=row.split(":",2);if(a.length!=2||Integer.parseInt(a[0])!=cz)continue;for(String run:a[1].split(",")){String[] r=run.split("-",2);int lo=Integer.parseInt(r[0]),hi=r.length>1?Integer.parseInt(r[1]):lo;if(cx>=lo&&cx<=hi)return true;}}
                return false;
            }
            if(descriptor.startsWith("POLY:")){
                String[] raw=descriptor.substring(5).split(",");var v=new java.util.ArrayList<Integer>();for(String n:raw)v.add(Integer.parseInt(n));
                return net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.polygonIntersectsChunk(v,cx,cz);
            }
        }catch(RuntimeException ignored){return false;}
        return false;
    }

}
