package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.project.OceanCanvasHarnessService;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;
import net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;
import net.oceancanvas.mod.worldgen.OceanCanvasStructureKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-authoritative dry-run/change-impact contract for world-affecting Ocean Canvas actions.
 *
 * <p>The preview token is a SHA-256 fingerprint of the exact normalized scope plus all state that
 * can materially change the admission decision. Execution recomputes the preview and requires the
 * token to match, so a confirmation can never silently apply to a different region/configuration
 * state than the one the player reviewed.</p>
 */
public final class OceanCanvasOperationPreviewService {
    /**
     * Synchronous server-thread previews are intentionally bounded. Large regions
     * use exact O(1) rectangle scope counts plus revision-style ledger fingerprints
     * instead of iterating/moving millions of boxed Long keys on the tick thread.
     */
    private static final long MAX_SYNCHRONOUS_REGION_STATE_SCAN = 32_768L;

    private OceanCanvasOperationPreviewService() {}

    public record Preview(String kind, String target, String argument, String token, boolean blocked,
                          int chunks, int processed, int canvas, int vanilla, int custom, int unknown,
                          int linkedProjects, int linkedTasks, List<String> blockers, List<String> warnings,
                          String compatibilitySignature, String summary, String resourceForecast, String resourceRisk, String workflowGate, String dryRunDiff) {
        public Preview {
            kind = clean(kind).toUpperCase(Locale.ROOT); target = clean(target); argument = clean(argument);
            token = clean(token); blockers = blockers == null ? List.of() : List.copyOf(blockers);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            compatibilitySignature = clean(compatibilitySignature); summary = clean(summary); resourceForecast=clean(resourceForecast); resourceRisk=clean(resourceRisk); workflowGate=clean(workflowGate); dryRunDiff=clean(dryRunDiff);
        }
    }

    public static Preview region(ServerLevel world, String rawKind, OceanCanvasPlayerZones.Zone zone) {
        String kind = clean(rawKind).toUpperCase(Locale.ROOT);
        if (!Set.of("PREGEN", "REWIPE", "RESTORE").contains(kind)) throw new IllegalArgumentException("unsupported region preview kind " + kind);
        if (zone == null) throw new IllegalArgumentException("region is missing");
        OceanCanvasConfig config = OceanCanvasConfig.get();
        long exactScopeCount = clippedRegionChunkCount(zone, config);

        // v253.71: never expand a region just to discover a cheap authoritative
        // blocker. The v253.70.0 watchdog proved a preview packet could otherwise
        // spend minutes in Set.copyOf/Zone.exactChunks on the integrated server.
        if (OceanCanvasTerrainOperationActivity.pregenOrRestoreRunning()) {
            return deferredLargeRegionPreview(world, kind, zone, exactScopeCount,
                    "Another terrain operation is already running.", true);
        }

        // Large fresh regions still need to be previewable/startable from the UI.
        // Their scope count is exact, but per-chunk state breakdown is deliberately
        // deferred so a 20k x 20k region cannot monopolize the server thread.
        if (exactScopeCount > MAX_SYNCHRONOUS_REGION_STATE_SCAN) {
            return deferredLargeRegionPreview(world, kind, zone, exactScopeCount, "", false);
        }

        Set<Long> chunks = clippedRegionChunks(zone, config);
        var terrain = OceanCanvasTerrainStateData.get(world);
        var protectedData = OceanCanvasProtectedData.get(world);
        int processed = 0, canvas = 0, vanilla = 0, custom = 0, unknown = 0;
        for (long packed : chunks) {
            if (protectedData.isChunkProcessed(new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)))) processed++;
            switch (terrain.get(packed)) {
                case CANVAS -> canvas++;
                case VANILLA -> vanilla++;
                case CUSTOM_OR_MODIFIED -> custom++;
                case UNKNOWN -> unknown++;
            }
        }
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String persistence = OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
        if (!persistence.isBlank()) blockers.add(persistence);
        var meta = OceanCanvasProjectData.get(world).regionMeta(zone.name());
        if (meta != null && meta.parsedStage() == OceanCanvasProjectData.RegionStage.ARCHIVED) blockers.add("Region is Archived.");
        if (chunks.isEmpty()) blockers.add("The region has no chunks inside the configured Canvas.");
        if ((OceanCanvasTerrainOperationActivity.pregenOrRestoreRunning())) blockers.add("Another terrain operation is already running.");
        var workflowGate=OceanCanvasWorkflowGateService.evaluate(world,kind);
        if(!workflowGate.pass()) blockers.add("Workflow gate blocked: "+workflowGate.firstFailure());
        if ("PREGEN".equals(kind) && zone.protectedNow()) blockers.add("Region is already pregenerated/protected; ordinary Pregen must skip committed work.");
        if (("REWIPE".equals(kind) || "RESTORE".equals(kind)) && custom > 0)
            warnings.add(custom + " chunk(s) are marked CUSTOM_OR_MODIFIED; player-authored terrain may be replaced.");
        if ("RESTORE".equals(kind)) addRestoreCompatibilityWarnings(world, warnings);

        var ws = OceanCanvasWorkspaceData.get(world);
        int linkedProjects = 0, linkedTasks = 0;
        for (var p : ws.projects()) if (p.regionName().equalsIgnoreCase(zone.name())) linkedProjects++;
        for (var t : ws.tasks()) if (t.regionName().equalsIgnoreCase(zone.name())) linkedTasks++;
        if (linkedProjects > 0 || linkedTasks > 0)
            warnings.add("Linked planning context: " + linkedProjects + " project(s), " + linkedTasks + " task(s).");

        String compat = compatibilityHash(world);
        String scopeHash = chunkHash(chunks);
        String material = "region|" + kind + '|' + zone.name().toLowerCase(Locale.ROOT) + '|' + scopeHash + '|'
                + zone.protectedNow() + '|' + processed + '|' + canvas + '|' + vanilla + '|' + custom + '|' + unknown + '|'
                + configFingerprint(OceanCanvasConfig.get()) + '|' + compat + '|' + String.join("\u001f", blockers) + '|' + String.join("\u001f", warnings);
        String token = sha256(material);
        String verb = switch (kind) { case "PREGEN" -> "prepare as Canvas"; case "REWIPE" -> "clear back to Canvas"; default -> "restore to vanilla"; };
        String summary = kind + " dry run · " + zone.name() + " · " + chunks.size() + " exact chunk(s) · "
                + "state C=" + canvas + " V=" + vanilla + " M=" + custom + " U=" + unknown + " · would " + verb
                + (blockers.isEmpty() ? " · READY" : " · BLOCKED: " + blockers.get(0))
                + (warnings.isEmpty() ? "" : " · WARNING: " + warnings.get(0));
        var forecast=OceanCanvasPreflightResourceForecast.estimate(world,kind,chunks.size());
        return new Preview(kind, zone.name(), "", token, !blockers.isEmpty(), chunks.size(), processed,
                canvas, vanilla, custom, unknown, linkedProjects, linkedTasks, blockers, warnings, compat, summary,forecast.compact(),forecast.risk(),workflowGate.summary(),regionDryRunDiff(world,chunks));
    }

    public static Preview metadataRepair(ServerLevel world) {
        var report=OceanCanvasDeepHealthService.scan(world,256);
        List<String> blockers=new ArrayList<>();List<String>warnings=new ArrayList<>();
        String persistence=OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
        if(!persistence.isBlank())blockers.add(persistence);
        var workflowGate=OceanCanvasWorkflowGateService.evaluate(world,"REPAIR_METADATA");
        if(!workflowGate.pass())blockers.add("Workflow gate blocked: "+workflowGate.firstFailure());
        if(report.legacyUnverified()>0)warnings.add(report.legacyUnverified()+" legacy processed chunk(s) remain intentionally unclassified and will NOT be guessed/repaired.");
        String compat=compatibilityHash(world);
        String material="repair-metadata|"+report.explicitStates()+'|'+report.healthyExplicit()+'|'+report.mismatches()+'|'+report.legacyUnverified()+'|'+compat+'|'+String.join("\u001f",blockers)+'|'+String.join("\u001f",warnings);
        String token=sha256(material);
        String summary="REPAIR METADATA dry run · "+report.mismatches()+" explicit processed-seal contradiction(s) would be repaired; "+report.legacyUnverified()+" legacy/unverified chunk(s) would remain unchanged"
                +(blockers.isEmpty()?" · READY":" · BLOCKED: "+blockers.get(0))+(warnings.isEmpty()?"":" · WARNING: "+warnings.get(0));
        var forecast=OceanCanvasPreflightResourceForecast.estimate(world,"REPAIR_METADATA",(int)Math.min(Integer.MAX_VALUE,report.explicitStates()));
        return new Preview("REPAIR_METADATA","WORLD","",token,!blockers.isEmpty(),(int)Math.min(Integer.MAX_VALUE,report.explicitStates()),0,0,0,0,(int)Math.min(Integer.MAX_VALUE,report.legacyUnverified()),0,0,blockers,warnings,compat,summary,forecast.compact(),forecast.risk(),workflowGate.summary(),"metadata records checked="+report.explicitStates()+"; legacy/unverified="+report.legacyUnverified()+"; physical blocks/entities/structures changed=0");
    }

    /** Server-authored impact preview for an exact region geometry edit. Argument formats:
     * RECT:minX,minZ,maxX,maxZ or POLY:REPLACE:x,z;x,z;... . */
    /**
     * Server-authored impact preview for an exact region geometry edit.
     *
     * <p>Critical performance rule: a preview must never eagerly materialize a
     * million-chunk rectangular Region on the integrated-server thread. Rectangle
     * counts/diffs are arithmetic. If a terrain operation already blocks geometry
     * mutation we return that authoritative blocker before any polygon raster or
     * exact-footprint scan is attempted.</p>
     */
    public static Preview regionGeometry(ServerLevel world,String regionName,String argument){
        var zone=OceanCanvasPlayerZones.get(world).zoneByName(regionName);
        if(zone==null)throw new IllegalArgumentException("No region named '"+regionName+"'.");
        argument=clean(argument);

        boolean rectangle=argument.startsWith("RECT:");
        boolean polygon=argument.startsWith("POLY:");
        if(!rectangle&&!polygon)throw new IllegalArgumentException("Unsupported region geometry preview.");

        OceanCanvasRegionGeometry.ChunkBounds proposedRect=null;
        List<Integer> vertices=List.of();
        long proposedEnvelopeCount=0L;
        if(rectangle){
            String[] q=argument.substring(5).split(",",-1);
            if(q.length!=4)throw new IllegalArgumentException("Invalid RECT geometry preview.");
            int x1=Integer.parseInt(q[0]),z1=Integer.parseInt(q[1]),x2=Integer.parseInt(q[2]),z2=Integer.parseInt(q[3]);
            proposedRect=OceanCanvasRegionGeometry.chunkBoundsForBlocks(x1,z1,x2,z2);
            proposedEnvelopeCount=proposedRect.count();
            if(proposedEnvelopeCount<=0L)throw new IllegalArgumentException("Proposed region footprint is empty.");
        }else{
            String raw=argument.substring(5);int colon=raw.indexOf(':');if(colon>=0)raw=raw.substring(colon+1);
            ArrayList<Integer> parsed=new ArrayList<>();
            if(!raw.isBlank())for(String pair:raw.split(";")){
                String[] q=pair.split(",",-1);if(q.length!=2)throw new IllegalArgumentException("Invalid polygon geometry preview.");
                parsed.add(Integer.parseInt(q[0]));parsed.add(Integer.parseInt(q[1]));
            }
            vertices=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.cleanVertices(parsed);
            if(vertices.size()<6)throw new IllegalArgumentException("A polygon Region needs at least three vertices.");
            proposedEnvelopeCount=polygonEnvelopeChunkCount(vertices);
        }

        List<String> blockers=new ArrayList<>(),warnings=new ArrayList<>();
        // Admission gates intentionally come before any exact geometry expansion.
        if(OceanCanvasTerrainOperationActivity.pregenOrRestoreRunning())blockers.add("A terrain operation is running; region geometry cannot change mid-operation.");
        String persistence=OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);if(!persistence.isBlank())blockers.add(persistence);
        var meta=OceanCanvasProjectData.get(world).regionMeta(zone.name());if(meta!=null&&meta.parsedStage()==OceanCanvasProjectData.RegionStage.ARCHIVED)blockers.add("Region is Archived.");
        var workflowGate=OceanCanvasWorkflowGateService.evaluate(world,"REGION_GEOMETRY");if(!workflowGate.pass())blockers.add("Workflow gate blocked: "+workflowGate.firstFailure());

        var ws=OceanCanvasWorkspaceData.get(world);int lp=0,lt=0;
        for(var pr:ws.projects())if(pr.regionName().equalsIgnoreCase(zone.name()))lp++;
        for(var t:ws.tasks())if(t.regionName().equalsIgnoreCase(zone.name()))lt++;
        if(lp>0||lt>0)warnings.add("Linked context affected: "+lp+" project(s), "+lt+" task(s).");

        long currentCount=zoneChunkCount(zone);
        String compat=compatibilityHash(world);
        if(!blockers.isEmpty()){
            String proposedText=rectangle?Long.toString(proposedEnvelopeCount):"polygon validation deferred";
            String material="region-geometry-blocked|"+zoneGeometryFingerprint(zone)+'|'+argument+'|'+compat+'|'+String.join("\u001f",blockers)+'|'+String.join("\u001f",warnings);
            String token=sha256(material);
            String summary="CHANGE IMPACT · "+zone.name()+" geometry · "+currentCount+" → "+proposedText+" chunk(s) · BLOCKED: "+blockers.get(0)+(warnings.isEmpty()?"":" · WARNING: "+warnings.get(0));
            return new Preview("REGION_GEOMETRY",zone.name(),argument,token,true,rectangle?countToInt(proposedEnvelopeCount):0,0,0,0,0,0,lp,lt,blockers,warnings,compat,summary,"","",workflowGate.summary(),
                    "Exact geometry diff intentionally skipped because mutation is blocked; no Region chunk set was expanded on the server thread.");
        }

        long proposedCount;
        long overlap;
        if(rectangle){
            proposedCount=proposedEnvelopeCount;
            if(zone.hasExplicitShape()){
                long hit=0L;
                for(long packed:zone.chunks())if(proposedRect.containsPacked(packed))hit++;
                overlap=hit;
            }else{
                overlap=OceanCanvasRegionGeometry.intersectionCount(zoneChunkBounds(zone),proposedRect);
            }
        }else{
            // Vertices are canonical. Use the lazy chunk view so a full-canvas
            // preview cannot allocate a million-entry HashSet on the server thread.
            Set<Long> proposed=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.polygonChunkSet(vertices);
            if(proposed.isEmpty()) throw new IllegalArgumentException("Proposed region footprint is empty.");
            proposedCount=proposed.size();
            if(zone.hasExplicitShape()){
                Set<Long> current=zone.chunks();
                long hit=0L;
                if(current.size()<=proposed.size()){
                    for(long packed:current)if(proposed.contains(packed))hit++;
                }else{
                    for(long packed:proposed)if(current.contains(packed))hit++;
                }
                overlap=hit;
            }else{
                OceanCanvasRegionGeometry.ChunkBounds currentRect=zoneChunkBounds(zone);long hit=0L;
                for(long packed:proposed)if(currentRect.containsPacked(packed))hit++;
                overlap=hit;
            }
        }

        long added=Math.max(0L,proposedCount-overlap);
        long removed=Math.max(0L,currentCount-overlap);
        if(zone.protectedNow()&&removed>0L)warnings.add(removed+" protected chunk(s) leave this region's footprint; existing terrain protection/history is not silently deleted.");
        String material="region-geometry|"+zoneGeometryFingerprint(zone)+'|'+argument+'|'+currentCount+'|'+proposedCount+'|'+overlap+'|'+compat+'|'+String.join("\u001f",warnings);
        String token=sha256(material);
        String summary="CHANGE IMPACT · "+zone.name()+" geometry · "+currentCount+" → "+proposedCount+" chunk(s) · +"+added+" / -"+removed+" · linked "+lp+" project(s), "+lt+" task(s)"+(warnings.isEmpty()?"":" · WARNING: "+warnings.get(0));
        return new Preview("REGION_GEOMETRY",zone.name(),argument,token,false,countToInt(proposedCount),0,0,0,0,0,lp,lt,blockers,warnings,compat,summary,"","",workflowGate.summary(),
                "proposed chunks="+proposedCount+"; added="+added+"; removed="+removed+"; linked projects="+lp+"; linked tasks="+lt);
    }

    private static OceanCanvasRegionGeometry.ChunkBounds zoneChunkBounds(OceanCanvasPlayerZones.Zone zone){
        var b=zone.bounds();
        return OceanCanvasRegionGeometry.chunkBoundsForBlocks(b.minX(),b.minZ(),b.maxX(),b.maxZ());
    }
    private static long zoneChunkCount(OceanCanvasPlayerZones.Zone zone){
        return zone.hasExplicitShape()?zone.chunks().size():zoneChunkBounds(zone).count();
    }
    private static long polygonEnvelopeChunkCount(List<Integer> vertices){
        var b=OceanCanvasRegionGeometry.blockBounds(vertices);
        return OceanCanvasRegionGeometry.chunkBoundsForBlocks(b.minX(),b.minZ(),b.maxX(),b.maxZ()).count();
    }
    private static int countToInt(long value){return (int)Math.min(Integer.MAX_VALUE,Math.max(0L,value));}
    /**
     * Short-lived preview tokens only need to detect replacement of this immutable Zone record.
     * Every Region mutation swaps the record in OceanCanvasPlayerZones, so identity + cheap scalar
     * geometry is a constant-time exact stale-preview guard without hashing millions of chunk keys.
     */
    private static String zoneGeometryFingerprint(OceanCanvasPlayerZones.Zone zone){
        var b=zone.bounds();
        return Integer.toHexString(System.identityHashCode(zone))+":"+b.minX()+":"+b.minZ()+":"+b.maxX()+":"+b.maxZ()+":"+zone.chunks().size()+":"+zone.shapeVertices().hashCode()+":"+zone.protectedNow();
    }

    public static boolean tokenMatchesRegionGeometry(ServerLevel world,String regionName,String argument,String token){return token!=null&&!token.isBlank()&&token.equals(regionGeometry(world,regionName,argument).token());}

    public static boolean tokenMatchesMetadataRepair(ServerLevel world,String token){
        return token!=null&&!token.isBlank()&&token.equals(metadataRepair(world).token());
    }

    public static Preview config(ServerLevel world, String key, String proposedValue) {
        key = clean(key); proposedValue = clean(proposedValue);
        OceanCanvasConfig c = OceanCanvasConfig.get();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int chunks = explicitManagedChunkCount(world);
        int linkedProjects = OceanCanvasWorkspaceData.get(world).projects().size();
        int linkedTasks = OceanCanvasWorkspaceData.get(world).tasks().size();
        int regions = OceanCanvasPlayerZones.get(world).all().size();

        if (!isImpactfulConfigKey(key)) {
            warnings.add("This setting has no modeled terrain/region impact; preview is informational only.");
        } else if (Set.of("canvasSize", "centerX", "centerZ", "oceanFloorY", "oceanFloorVariation", "oceanFloorTransitionThickness", "taperEnabled", "taperWidthChunks").contains(key)) {
            warnings.add("Geometry/terrain setting: " + regions + " region(s), " + chunks + " explicitly tracked chunk(s), and " + linkedProjects + " project(s) may require review.");
            if (OceanCanvasTerrainOperationActivity.pregenOrRestoreRunning()) blockers.add("A terrain operation is running; geometry/terrain settings cannot change mid-operation.");
        } else if (structureConfigKey(key)) {
            int inheriting = 0;
            OceanCanvasStructureKind kind = structureKindForConfigKey(key);
            if (kind != null) for (var z : OceanCanvasPlayerZones.get(world).all()) if (z.overrideFor(kind) == net.oceancanvas.mod.config.StructureOverride.INHERIT) inheriting++;
            warnings.add("World structure default: " + inheriting + " region(s) currently inherit this rule. Existing physical structures are not rewritten until an explicit terrain operation.");
        } else if (key.startsWith("biomeMask")) {
            long inheriting = OceanCanvasPlayerZones.get(world).all().stream().filter(z -> z.biomeOverride() == null).count();
            warnings.add(inheriting + " region(s) currently inherit the world biome-mask setting. Existing chunks are not silently rewritten.");
        }

        String persistence = OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
        if (!persistence.isBlank() && isImpactfulConfigKey(key)) blockers.add(persistence);
        String compat = compatibilityHash(world);
        String material = "config|" + key + '|' + proposedValue + '|' + configFingerprint(c) + '|' + regions + '|' + chunks + '|'
                + linkedProjects + '|' + linkedTasks + '|' + compat + '|' + String.join("\u001f", blockers) + '|' + String.join("\u001f", warnings);
        String token = sha256(material);
        String summary = "CHANGE IMPACT · " + key + " → " + proposedValue + " · " + regions + " region(s), " + chunks
                + " tracked chunk(s), " + linkedProjects + " project(s), " + linkedTasks + " task(s)"
                + (blockers.isEmpty() ? " · READY" : " · BLOCKED: " + blockers.get(0))
                + (warnings.isEmpty() ? "" : " · " + warnings.get(0));
        var workflowGate=OceanCanvasWorkflowGateService.evaluate(world,"CONFIG_CHANGE");
        if(!workflowGate.pass() && isImpactfulConfigKey(key)){ blockers.add("Workflow gate blocked: "+workflowGate.firstFailure()); }
        // Recompute the token if the shared workflow gate changed the admission result.
        if(!workflowGate.pass() && isImpactfulConfigKey(key)){
            material += "|gate|"+workflowGate.summary(); token=sha256(material);
            summary += " · GATE: "+workflowGate.firstFailure();
        }
        return new Preview("CONFIG", key, proposedValue, token, !blockers.isEmpty(), chunks, 0, 0, 0, 0, 0,
                linkedProjects, linkedTasks, blockers, warnings, compat, summary,"","",workflowGate.summary(),"configuration-only preview; tracked chunks="+chunks+"; regions="+regions+"; linked projects="+linkedProjects+"; linked tasks="+linkedTasks);
    }

    public static boolean tokenMatchesRegion(ServerLevel world, String kind, OceanCanvasPlayerZones.Zone zone, String token) {
        return token != null && !token.isBlank() && token.equals(region(world, kind, zone).token());
    }

    public static boolean tokenMatchesConfig(ServerLevel world, String key, String value, String token) {
        return token != null && !token.isBlank() && token.equals(config(world, key, value).token());
    }

    public static boolean isImpactfulConfigKey(String key) {
        key = clean(key);
        return Set.of("canvasSize", "centerX", "centerZ", "oceanFloorY", "oceanFloorVariation", "oceanFloorTransitionThickness",
                "taperEnabled", "taperWidthChunks", "biomeMaskEnabled", "biomeMaskBiome", "shipwrecksEnabled",
                "naturalOceanRuinsProtected", "buriedTreasureEnabled", "naturalOceanMonumentsProtected", "naturalRuinedPortalsProtected").contains(key);
    }

    private static void addRestoreCompatibilityWarnings(ServerLevel world, List<String> warnings) {
        String report = OceanCanvasHarnessService.compatibilityReport(world);
        if (report.contains("changedSinceLastRecorded=true")) warnings.add("Dependency/version fingerprint changed since the last recorded compatibility baseline. Restore output may differ from earlier vanilla generation; rehearse before restoring irreplaceable areas.");
        var restore = OceanCanvasHarnessService.compatibilityMatrix(world).stream().filter(r -> r.capability().equals("Restore runtime")).findFirst().orElse(null);
        if (restore == null || !(restore.status().equals("OBSERVED") || restore.status().equals("VALIDATED")))
            warnings.add("Restore runtime is " + (restore == null ? "UNVERIFIED" : restore.status()) + " for the current exact Minecraft/Fabric/dependency signature.");
    }

    private static Preview deferredLargeRegionPreview(ServerLevel world, String kind, OceanCanvasPlayerZones.Zone zone,
            long exactScopeCount, String forcedBlocker, boolean operationRunning) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (forcedBlocker != null && !forcedBlocker.isBlank()) blockers.add(forcedBlocker);
        String persistence = OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
        if (!persistence.isBlank()) blockers.add(persistence);
        var meta = OceanCanvasProjectData.get(world).regionMeta(zone.name());
        if (meta != null && meta.parsedStage() == OceanCanvasProjectData.RegionStage.ARCHIVED) blockers.add("Region is Archived.");
        if (exactScopeCount <= 0L) blockers.add("The region has no chunks inside the configured Canvas.");
        var workflowGate = OceanCanvasWorkflowGateService.evaluate(world, kind);
        if (!workflowGate.pass()) blockers.add("Workflow gate blocked: " + workflowGate.firstFailure());
        if ("PREGEN".equals(kind) && zone.protectedNow()) blockers.add("Region is already pregenerated/protected; ordinary Pregen must skip committed work.");
        if (("REWIPE".equals(kind) || "RESTORE".equals(kind)) && !operationRunning)
            warnings.add("Large-region state breakdown is deferred; CUSTOM_OR_MODIFIED warnings are re-evaluated by the operation's normal safety gates.");
        if ("RESTORE".equals(kind)) addRestoreCompatibilityWarnings(world, warnings);

        var ws = OceanCanvasWorkspaceData.get(world);
        int linkedProjects = 0, linkedTasks = 0;
        for (var p : ws.projects()) if (p.regionName().equalsIgnoreCase(zone.name())) linkedProjects++;
        for (var t : ws.tasks()) if (t.regionName().equalsIgnoreCase(zone.name())) linkedTasks++;
        if (linkedProjects > 0 || linkedTasks > 0)
            warnings.add("Linked planning context: " + linkedProjects + " project(s), " + linkedTasks + " task(s).");

        int chunks = countToInt(exactScopeCount);
        String compat = compatibilityHash(world);
        var terrain = OceanCanvasTerrainStateData.get(world);
        var protectedData = OceanCanvasProtectedData.get(world);
        String material = "region-large|" + kind + '|' + zoneGeometryFingerprint(zone) + '|' + exactScopeCount + '|'
                + terrain.explicitStateCount() + '|' + protectedData.processedChunkCount() + '|'
                + protectedData.physicallyVerifiedProcessedChunkCount() + '|' + protectedData.lightingVerifiedProcessedChunkCount() + '|'
                + zone.protectedNow() + '|' + configFingerprint(OceanCanvasConfig.get()) + '|' + compat + '|'
                + String.join("\u001f", blockers) + '|' + String.join("\u001f", warnings);
        String token = sha256(material);
        String verb = switch (kind) { case "PREGEN" -> "prepare as Canvas"; case "REWIPE" -> "clear back to Canvas"; default -> "restore to vanilla"; };
        String summary = kind + " dry run · " + zone.name() + " · " + exactScopeCount + " exact chunk(s) · "
                + "large-region state scan deferred · would " + verb
                + (blockers.isEmpty() ? " · READY" : " · BLOCKED: " + blockers.get(0))
                + (warnings.isEmpty() ? "" : " · WARNING: " + warnings.get(0));
        var forecast = OceanCanvasPreflightResourceForecast.estimate(world, kind, chunks);
        return new Preview(kind, zone.name(), "", token, !blockers.isEmpty(), chunks, 0, 0, 0, 0, chunks,
                linkedProjects, linkedTasks, blockers, warnings, compat, summary, forecast.compact(), forecast.risk(), workflowGate.summary(),
                "exact scope=" + exactScopeCount + "; synchronous per-chunk state scan deferred above " + MAX_SYNCHRONOUS_REGION_STATE_SCAN + " chunks");
    }

    private static long clippedRegionChunkCount(OceanCanvasPlayerZones.Zone zone, OceanCanvasConfig c) {
        var canvas = OceanCanvasRegionGeometry.chunkBoundsForBlocks(
                c.centerX()-c.radius(), c.centerZ()-c.radius(), c.centerX()+c.radius()-1, c.centerZ()+c.radius()-1);
        if (!zone.hasExplicitShape()) return OceanCanvasRegionGeometry.intersectionCount(zoneChunkBounds(zone), canvas);
        return OceanCanvasRegionGeometry.clippedChunkSet(zone.chunks(), canvas).size();
    }

    private static Set<Long> clippedRegionChunks(OceanCanvasPlayerZones.Zone zone, OceanCanvasConfig c) {
        var canvas = OceanCanvasRegionGeometry.chunkBoundsForBlocks(
                c.centerX()-c.radius(), c.centerZ()-c.radius(), c.centerX()+c.radius()-1, c.centerZ()+c.radius()-1);
        var out = new LinkedHashSet<Long>(OceanCanvasRegionGeometry.clippedChunkSet(zone.exactChunks(), canvas));
        return out;
    }

    private static int explicitManagedChunkCount(ServerLevel world) { return OceanCanvasTerrainStateData.get(world).explicitStateCount(); }

    private static boolean structureConfigKey(String key) { return structureKindForConfigKey(key) != null; }
    private static OceanCanvasStructureKind structureKindForConfigKey(String key) {
        for (OceanCanvasStructureKind k : OceanCanvasStructureKind.values()) if (k.configKey().equals(key)) return k;
        return null;
    }

    /** OC-F111 exact dry-run diff over known server state without loading chunks. */
    private static String regionDryRunDiff(ServerLevel world,Set<Long> scope){
        int loaded=0,unloaded=0,entities=0,metadata=0,boundaryContacts=0,structures=0;
        var state=OceanCanvasTerrainStateData.get(world);
        for(long p:scope){
            int cx=ChunkPos.getX(p),cz=ChunkPos.getZ(p);
            if(world.getChunkSource().getChunkNow(cx,cz)==null)unloaded++;else loaded++;
            if(state.get(p)!=OceanCanvasTerrainStateData.TerrainState.UNKNOWN)metadata++;
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(!scope.contains(ChunkPos.pack(cx+d[0],cz+d[1])))boundaryContacts++;
        }
        if(!scope.isEmpty()){int minCx=Integer.MAX_VALUE,minCz=Integer.MAX_VALUE,maxCx=Integer.MIN_VALUE,maxCz=Integer.MIN_VALUE;for(long packed:scope){int cx=ChunkPos.getX(packed),cz=ChunkPos.getZ(packed);minCx=Math.min(minCx,cx);maxCx=Math.max(maxCx,cx);minCz=Math.min(minCz,cz);maxCz=Math.max(maxCz,cz);}var box=new net.minecraft.world.phys.AABB((double)(minCx<<4),world.getMinY(),(double)(minCz<<4),(double)((maxCx+1)<<4),world.getMaxY()+1.0D,(double)((maxCz+1)<<4));for(var e:world.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,box)){var bp=e.blockPosition();long p=ChunkPos.pack(Math.floorDiv(bp.getX(),16),Math.floorDiv(bp.getZ(),16));if(scope.contains(p))entities++;}}
        OceanCanvasP1W3Service.syncStructureEvidence(world);
        for(var st:OceanCanvasP1W3Data.get(world).structures()){int a=Math.floorDiv(st.minX(),16),b=Math.floorDiv(st.maxX(),16),c=Math.floorDiv(st.minZ(),16),d=Math.floorDiv(st.maxZ(),16);boolean hit=false;for(int x=a;x<=b&&!hit;x++)for(int z=c;z<=d;z++)if(scope.contains(ChunkPos.pack(x,z))){hit=true;break;}if(hit)structures++;}
        return "chunks="+scope.size()+" (loaded="+loaded+", unloaded="+unloaded+")"+
                "; loaded entities="+entities+"; known structures="+structures+"; metadata records="+metadata+
                "; boundary contacts="+boundaryContacts+"; unloaded entity/block detail remains explicitly UNVERIFIED until resident";
    }

    private static String compatibilityHash(ServerLevel world) {
        String sig = OceanCanvasHarnessService.compatibilitySignature(world); int s = sig.indexOf(' '); return s < 0 ? sig : sig.substring(0, s);
    }
    private static String configFingerprint(OceanCanvasConfig c) {
        return c.canvasSize()+":"+c.centerX()+":"+c.centerZ()+":"+c.oceanFloorY()+":"+c.oceanFloorVariation()+":"+c.oceanFloorTransitionThickness()+":"
                +c.taperEnabled()+":"+c.taperWidthChunks()+":"+c.biomeMaskEnabled()+":"+c.biomeMaskBiome()+":"
                +c.shipwrecksRule()+":"+c.naturalOceanRuinsRule()+":"+c.buriedTreasureRule()+":"+c.naturalOceanMonumentsRule()+":"+c.naturalRuinedPortalsRule();
    }
    private static String chunkHash(Set<Long> chunks) {
        var sorted = new ArrayList<>(chunks); sorted.sort(Comparator.naturalOrder()); StringBuilder s = new StringBuilder(); for (long p : sorted) s.append(Long.toUnsignedString(p)).append(','); return sha256(s.toString());
    }
    private static String sha256(String raw) {
        try { byte[] b = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)); StringBuilder s = new StringBuilder(64); for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v & 0xff)); return s.toString(); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
