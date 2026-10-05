package net.oceancanvas.mod.planning;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.project.OceanCanvasP5PipelineData;
import net.oceancanvas.mod.project.OceanCanvasPlanLibraryData;
import net.oceancanvas.mod.project.OceanCanvasPlanningData;
import net.oceancanvas.mod.project.OceanCanvasWorkspaceData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reproducible P5 round-trip tooling.  Every method is metadata/file analysis only unless its name
 * explicitly says reconcile; reconcile changes Ocean Canvas planning metadata, never Minecraft
 * blocks/chunks or maintenance operation state.
 */
public final class OceanCanvasP5PipelineService {
    private OceanCanvasP5PipelineService() {}

    public record ContractReport(boolean pass,int severity,List<String> issues,String summary) {}
    public record RoundTripReport(boolean aligned,double originDriftBlocks,double scaleDriftPercent,int seaLevelDrift,
                                  boolean orientationChanged,boolean contentChanged,List<String> losses,String summary) {}
    public record SeamReport(String leftId,String rightId,String edge,boolean pass,double meanDelta,double maxDelta,int samples,String summary) {}
    public record SeaLevelProposal(int sourceSeaLevel,int targetSeaLevel,int offset,double scale,int sourceMinY,int sourceMaxY,int targetMinY,int targetMaxY,String summary) {}
    public record FormatCapability(String target,String elevation,String vectors,String semantics,String groups,String anchors,String notes,String coordinateMetadata,String fingerprint) {}
    public record PluginCapability(String modId,boolean present,String version,List<String> capabilities,String mode) {}
    public record Finding(String feature,String targetId,int severity,String message) { public Finding { severity=Math.max(0,Math.min(100,severity));message=message==null?"":message; } }
    public record QuarantineResult(OceanCanvasP5PipelineData.QuarantineItem item,ContractReport contract) {}

    /** Canonical coordinate/origin contract shared by every P5 exporter/importer. */
    public static String coordinateContractText() {
        return "Ocean Canvas coordinate contract v1\n"
                +"- World plane: X east/west, Z north/south; +X east, +Z south.\n"
                +"- Canonical origin: Minecraft world block (0,0) unless a saved Transform Profile overrides it.\n"
                +"- Bounds: min inclusive / max inclusive in block coordinates.\n"
                +"- Rotation: clockwise degrees around the saved origin after optional axis flips.\n"
                +"- Image convention: pixel X maps east; image Y maps south (+Z). Gaea profiles may flip image Y explicitly.\n"
                +"- Scale: blocks per source unit/pixel; must be finite and > 0.\n"
                +"- Sea level/floor Y are explicit metadata and never inferred silently.\n"
                +"- All round trips retain a SHA-256 fingerprint sidecar when the target format cannot embed metadata.\n";
    }

    public static OceanCanvasPlanningData.Point transform(OceanCanvasPlanningData.Point p, OceanCanvasP5PipelineData.TransformProfile t) {
        double x=p.x(),z=p.z(); if(t.flipX())x=-x;if(t.flipZ())z=-z;x*=t.scale();z*=t.scale();
        double r=Math.toRadians(t.rotationDegrees()),c=Math.cos(r),s=Math.sin(r);double rx=x*c-z*s,rz=x*s+z*c;
        return new OceanCanvasPlanningData.Point((int)Math.round(rx+t.originX()),(int)Math.round(rz+t.originZ()));
    }

    public static ContractReport heightmapContract(OceanCanvasPlanLibraryData.TerrainAsset asset, OceanCanvasPlanLibraryData.AssetRevision rev) {
        List<String> issues=new ArrayList<>();int severity=0;
        if(asset==null||rev==null)return new ContractReport(false,100,List.of("Terrain asset/revision is missing."),"Contract FAIL: missing asset/revision");
        if(rev.widthPx()<1||rev.heightPx()<1){issues.add("Pixel dimensions are missing.");severity=Math.max(severity,90);}
        if(!(rev.blocksPerPixel()>0)||!Double.isFinite(rev.blocksPerPixel())){issues.add("blocksPerPixel must be finite and > 0.");severity=Math.max(severity,100);}
        if(rev.maxX()<=rev.minX()||rev.maxZ()<=rev.minZ()){issues.add("World bounds are invalid.");severity=Math.max(severity,100);}
        if(rev.blocksPerPixel()>0&&rev.widthPx()>0&&rev.heightPx()>0){
            double expectedW=(rev.maxX()-rev.minX()+1)/rev.blocksPerPixel(),expectedH=(rev.maxZ()-rev.minZ()+1)/rev.blocksPerPixel();
            double dw=Math.abs(expectedW-rev.widthPx())/Math.max(1,expectedW),dh=Math.abs(expectedH-rev.heightPx())/Math.max(1,expectedH);
            if(dw>0.02||dh>0.02){issues.add(String.format(Locale.ROOT,"Resolution/world-bounds mismatch: expected about %.0fx%.0f px at %.4f b/px, got %dx%d.",expectedW,expectedH,rev.blocksPerPixel(),rev.widthPx(),rev.heightPx()));severity=Math.max(severity,75);}
        }
        if(rev.seaLevel()!=asset.seaLevel()){issues.add("Revision sea level "+rev.seaLevel()+" differs from Terrain Asset sea level "+asset.seaLevel()+".");severity=Math.max(severity,65);}
        String ori=rev.orientation().toUpperCase(Locale.ROOT);if(!(ori.contains("NORTH")||ori.contains("SOUTH")||ori.contains("EAST")||ori.contains("WEST")||ori.contains("FLIP")||ori.contains("ROT"))){issues.add("Orientation is not a recognized explicit convention: "+rev.orientation());severity=Math.max(severity,60);}
        int bits=metricInt(rev.notes(),"bits",0);if(bits==0){issues.add("Bit depth is not recorded.");severity=Math.max(severity,45);}else if(bits!=8&&bits!=16&&bits!=32){issues.add("Unusual heightmap bit depth: "+bits+" bits.");severity=Math.max(severity,55);}
        if(rev.fileHash().isBlank()){issues.add("SHA-256 file hash/fingerprint source is missing.");severity=Math.max(severity,45);}
        boolean pass=severity<60;String summary=pass?"Contract PASS"+(issues.isEmpty()?"":": "+issues.size()+" advisory warning(s)"):"Contract FAIL: "+issues.size()+" issue(s)";
        return new ContractReport(pass,severity,List.copyOf(issues),summary);
    }

    public static String fingerprintText(String scope,String text) {
        String raw="oceancanvas-fingerprint-v1|"+(scope==null?"":scope)+"|"+(text==null?"":text);
        return sha256(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static String fingerprint(OceanCanvasPlanLibraryData.TerrainAsset asset, OceanCanvasPlanLibraryData.AssetRevision rev) {
        String raw="oceancanvas-fingerprint-v1|"+asset.id()+"|"+rev.id()+"|"+rev.stage()+"|"+rev.fileHash()+"|"+rev.minX()+","+rev.minZ()+","+rev.maxX()+","+rev.maxZ()+"|"+rev.widthPx()+"x"+rev.heightPx()+"|"+rev.blocksPerPixel()+"|"+rev.seaLevel()+"|"+rev.orientation();
        return sha256(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static RoundTripReport roundTrip(OceanCanvasPlanLibraryData.TerrainAsset asset,OceanCanvasPlanLibraryData.AssetRevision source,OceanCanvasPlanLibraryData.AssetRevision returned,String targetTool) {
        if(asset==null||source==null||returned==null)return new RoundTripReport(false,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,0,true,true,List.of("Missing source/returned revision."),"Round-trip FAIL: missing revision");
        double origin=Math.hypot(returned.minX()-source.minX(),returned.minZ()-source.minZ());
        origin=Math.max(origin,Math.hypot(returned.maxX()-source.maxX(),returned.maxZ()-source.maxZ()));
        double scale=source.blocksPerPixel()<=0||returned.blocksPerPixel()<=0?Double.POSITIVE_INFINITY:Math.abs(returned.blocksPerPixel()/source.blocksPerPixel()-1D)*100D;
        int sea=returned.seaLevel()-source.seaLevel();boolean orientation=!returned.orientation().equalsIgnoreCase(source.orientation());
        boolean content=!source.fileHash().isBlank()&&!returned.fileHash().isBlank()&&!source.fileHash().equalsIgnoreCase(returned.fileHash());
        List<String> losses=lossesFor(targetTool);boolean aligned=origin<=1.0&&scale<=0.1&&sea==0&&!orientation;
        String summary=String.format(Locale.ROOT,"%s · origin drift %.2f blocks · scale drift %.3f%% · sea %+d · orientation %s · content %s",
                aligned?"ALIGNED":"DRIFT",origin,scale,sea,orientation?"changed":"stable",content?"changed":"same/unknown");
        return new RoundTripReport(aligned,origin,scale,sea,orientation,content,List.copyOf(losses),summary);
    }

    public static SeaLevelProposal normalizeSeaLevel(int sourceSea,int targetSea,int sourceMinY,int sourceMaxY,int targetMinY,int targetMaxY){
        if(sourceMaxY<=sourceMinY)sourceMaxY=sourceMinY+1;if(targetMaxY<=targetMinY)targetMaxY=targetMinY+1;
        double scale=(targetMaxY-targetMinY)/(double)(sourceMaxY-sourceMinY);int offset=targetSea-(int)Math.round(sourceSea*scale);
        String summary=String.format(Locale.ROOT,"Map Y with y' = round(y × %.6f) %+d; source sea %d → target sea %d; source range %d..%d → target range %d..%d. Source is unchanged.",scale,offset,sourceSea,targetSea,sourceMinY,sourceMaxY,targetMinY,targetMaxY);
        return new SeaLevelProposal(sourceSea,targetSea,offset,scale,sourceMinY,sourceMaxY,targetMinY,targetMaxY,summary);
    }

    public static List<FormatCapability> formatCapabilities(){
        return List.of(
                new FormatCapability("OCEANPROJECT","PRESERVED","PRESERVED","PRESERVED","PRESERVED","PRESERVED","PRESERVED","PRESERVED","PRESERVED"),
                new FormatCapability("JSON","METADATA","PRESERVED","PRESERVED","PRESERVED","PRESERVED","PRESERVED","PRESERVED","PRESERVED"),
                new FormatCapability("SVG","UNSUPPORTED","PRESERVED","APPROX","GROUPS","APPROX","UNSUPPORTED","SIDECAR","SIDECAR"),
                new FormatCapability("PNG_HEIGHTMAP","PRESERVED","UNSUPPORTED","UNSUPPORTED","UNSUPPORTED","UNSUPPORTED","UNSUPPORTED","SIDECAR","SIDECAR"),
                new FormatCapability("GAEA","PRESERVED","MASKS","APPROX","APPROX","UNSUPPORTED","UNSUPPORTED","SIDECAR","SIDECAR"),
                new FormatCapability("WORLDPAINTER","PRESERVED","MASKS","LAYERS","APPROX","APPROX","NOTES_SIDECAR","MANIFEST","SIDECAR"),
                new FormatCapability("LITEMATICA","BLOCKS","PLACEMENT","UNSUPPORTED","SCHEMATIC","PRESERVED","UNSUPPORTED","PLACEMENT_MANIFEST","SIDECAR")
        );
    }

    public static List<PluginCapability> pluginCapabilities(){
        return List.of(plugin("voxy",List.of("TERRAIN_INVALIDATION","LOD_REFRESH")),plugin("journeymap",List.of("MAP_TILE_SOURCE","WAYPOINT_CONTEXT")),plugin("litematica",List.of("SCHEMATIC_PLACEMENT_REFERENCE")),plugin("c2me",List.of("ASYNC_CHUNK_ENVIRONMENT")));
    }
    private static PluginCapability plugin(String modId,List<String> caps){var c=FabricLoader.getInstance().getModContainer(modId);boolean present=c.isPresent();String ver=c.map(v->v.getMetadata().getVersion().getFriendlyString()).orElse("absent");return new PluginCapability(modId,present,ver,caps,present?"CAPABILITY_GATED":"OPTIONAL_ABSENT");}

    public static QuarantineResult quarantine(ServerLevel world,String terrainAssetId,String fileName,int minX,int minZ,int maxX,int maxZ,int width,int height,double bpp,int seaLevel,String orientation) throws IOException {
        var lib=OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(terrainAssetId);if(asset==null)throw new IllegalArgumentException("Unknown Terrain Asset: "+terrainAssetId);
        Path file=resolveImportFile(world,fileName);boolean exists=Files.isRegularFile(file);String hash=exists?sha256(file):"";int actualW=width,actualH=height,bits=0;
        if(exists){BufferedImage img=tryImage(file);if(img!=null){actualW=img.getWidth();actualH=img.getHeight();bits=img.getColorModel().getComponentSize(0);}}
        String notes=bits>0?"HEIGHTMAP bits="+bits:"";
        var rev=new OceanCanvasPlanLibraryData.AssetRevision("quarantine_preview","RETURN",file.getFileName().toString(),hash,minX,minZ,maxX,maxZ,actualW,actualH,bpp,seaLevel,orientation,System.currentTimeMillis(),notes);
        ContractReport contract=heightmapContract(asset,rev);if(!exists){var issues=new ArrayList<>(contract.issues());issues.add(0,"Import file is not present in <world>/oceancanvas/imports: "+file.getFileName());contract=new ContractReport(false,100,List.copyOf(issues),"Contract FAIL: import file not found");}String issues=String.join(" | ",contract.issues());var data=OceanCanvasP5PipelineData.get(world);String id=data.newId("quarantine");
        var item=new OceanCanvasP5PipelineData.QuarantineItem(id,file.getFileName().toString(),hash,"TERRAIN_ASSET",asset.id(),minX,minZ,maxX,maxZ,actualW,actualH,bpp,seaLevel,orientation,contract.pass()?"VALIDATED":"BLOCKED",issues,System.currentTimeMillis());data.putQuarantine(item);return new QuarantineResult(item,contract);
    }

    public static String reconcile(ServerLevel world,String quarantineId,String mode){
        var p5=OceanCanvasP5PipelineData.get(world);var q=p5.quarantine(quarantineId);if(q==null)throw new IllegalArgumentException("Unknown quarantine item");
        if("BLOCKED".equals(q.status()))throw new IllegalArgumentException("Quarantine contract is blocked; fix metadata before reconciliation.");
        var lib=OceanCanvasPlanLibraryData.get(world);var a=lib.terrainAsset(q.targetId());if(a==null)throw new IllegalArgumentException("Quarantine target Terrain Asset no longer exists.");
        String m=mode==null?"REGISTER_RETURN":mode.trim().toUpperCase(Locale.ROOT);int minX=a.minX(),minZ=a.minZ(),maxX=a.maxX(),maxZ=a.maxZ(),sea=a.seaLevel();String orientation=q.orientation();
        if(m.equals("BOUNDS")||m.equals("ALL_METADATA")){minX=q.minX();minZ=q.minZ();maxX=q.maxX();maxZ=q.maxZ();}
        if(m.equals("SEA_LEVEL")||m.equals("ALL_METADATA"))sea=q.seaLevel();
        var revs=new ArrayList<>(a.revisions());var rev=new OceanCanvasPlanLibraryData.AssetRevision(lib.newId("revision"),"RETURN",q.fileName(),q.fileHash(),q.minX(),q.minZ(),q.maxX(),q.maxZ(),q.widthPx(),q.heightPx(),q.blocksPerPixel(),q.seaLevel(),orientation,System.currentTimeMillis(),"P5_RECONCILED source="+q.id()+" mode="+m);revs.add(rev);
        lib.putTerrainAsset(new OceanCanvasPlanLibraryData.TerrainAsset(a.id(),a.name(),a.planningObjectIds(),minX,minZ,maxX,maxZ,sea,"RETURN_REVIEW",revs,a.approvedGaeaRevision(),a.approvedWorldPainterRevision(),a.placementData(),a.notes()));
        p5.putQuarantine(new OceanCanvasP5PipelineData.QuarantineItem(q.id(),q.fileName(),q.fileHash(),q.targetType(),q.targetId(),q.minX(),q.minZ(),q.maxX(),q.maxZ(),q.widthPx(),q.heightPx(),q.blocksPerPixel(),q.seaLevel(),q.orientation(),"RECONCILED",q.issues(),q.createdAt()));
        return "Reconciled external edit as metadata revision "+rev.id()+" using "+m+". No terrain was changed.";
    }

    /** Adjacent tile metadata plus bounded raster-edge comparison when both files exist in the import inbox. */
    public static List<SeamReport> inspectSeams(ServerLevel world){
        var lib=OceanCanvasPlanLibraryData.get(world);List<SeamReport> out=new ArrayList<>();var assets=lib.terrainAssets();
        for(int i=0;i<assets.size();i++)for(int j=i+1;j<assets.size();j++){
            var a=assets.get(i);var b=assets.get(j);var ar=latest(a);var br=latest(b);if(ar==null||br==null)continue;String edge=null;
            if(Math.abs(a.maxX()+1-b.minX())<=1&&rangesOverlap(a.minZ(),a.maxZ(),b.minZ(),b.maxZ()))edge="EAST_WEST";
            else if(Math.abs(b.maxX()+1-a.minX())<=1&&rangesOverlap(a.minZ(),a.maxZ(),b.minZ(),b.maxZ()))edge="WEST_EAST";
            else if(Math.abs(a.maxZ()+1-b.minZ())<=1&&rangesOverlap(a.minX(),a.maxX(),b.minX(),b.maxX()))edge="SOUTH_NORTH";
            else if(Math.abs(b.maxZ()+1-a.minZ())<=1&&rangesOverlap(a.minX(),a.maxX(),b.minX(),b.maxX()))edge="NORTH_SOUTH";
            if(edge==null)continue;
            boolean meta=ar.blocksPerPixel()>0&&br.blocksPerPixel()>0&&Math.abs(ar.blocksPerPixel()-br.blocksPerPixel())<1e-6&&ar.seaLevel()==br.seaLevel()&&ar.orientation().equalsIgnoreCase(br.orientation());
            SeamReport raster=compareRasterEdges(world,ar,br,edge);if(raster!=null)out.add(new SeamReport(a.id(),b.id(),edge,meta&&raster.pass(),raster.meanDelta(),raster.maxDelta(),raster.samples(),(meta?"Metadata aligned; ":"Metadata mismatch; ")+raster.summary()));
            else out.add(new SeamReport(a.id(),b.id(),edge,meta,meta?0:Double.NaN,meta?0:Double.NaN,0,meta?"Tile metadata aligns; raster files not available in import inbox.":"Tile metadata mismatch (scale/sea/orientation); raster comparison skipped."));
        }
        return List.copyOf(out.stream().limit(128).toList());
    }

    public static List<Finding> analyze(ServerLevel world){
        List<Finding> out=new ArrayList<>();var lib=OceanCanvasPlanLibraryData.get(world);for(var a:lib.terrainAssets()){
            var r=latest(a);if(r==null){out.add(new Finding("HEIGHTMAP_CONTRACT",a.id(),60,"Terrain Asset has no revision metadata."));continue;}
            var c=heightmapContract(a,r);if(!c.pass()||!c.issues().isEmpty())out.add(new Finding("HEIGHTMAP_CONTRACT",a.id(),c.severity(),c.summary()+": "+String.join("; ",c.issues())));
            var source=a.revisions().stream().filter(v->v.stage().contains("SOURCE")||v.stage().contains("GAEA")||v.stage().contains("EXPORT")).findFirst().orElse(null);
            if(source!=null&&source!=r){var rt=roundTrip(a,source,r,r.stage());if(!rt.aligned())out.add(new Finding("IMPORT_DRIFT",a.id(),Math.min(100,(int)Math.round(rt.originDriftBlocks()*4+rt.scaleDriftPercent()*8+Math.abs(rt.seaLevelDrift())*4+(rt.orientationChanged()?40:0))),rt.summary()));}
        }
        for(var s:inspectSeams(world))if(!s.pass())out.add(new Finding("TILE_SEAM",s.leftId()+":"+s.rightId(),80,s.summary()));
        var p5=OceanCanvasP5PipelineData.get(world);for(var q:p5.quarantine())if("BLOCKED".equals(q.status()))out.add(new Finding("IMPORT_QUARANTINE",q.id(),85,q.issues()));
        for(var t:p5.transforms())if(t.scale()<=0||!Double.isFinite(t.scale()))out.add(new Finding("COORDINATE_TRANSFORM",t.id(),100,"Transform scale is invalid."));
        out.sort(Comparator.comparingInt(Finding::severity).reversed().thenComparing(Finding::feature));return List.copyOf(out.stream().limit(256).toList());
    }

    public static String interchangeSchemaJson(){return "{\n  \"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\n  \"$id\":\"https://oceancanvas.local/schema/oceancanvas-project-v4.json\",\n  \"title\":\"Ocean Canvas Project Interchange\",\n  \"type\":\"object\",\n  \"required\":[\"format\",\"formatVersion\",\"coordinateContract\",\"project\",\"planObjects\",\"terrainAssets\"],\n  \"properties\":{\"format\":{\"const\":\"oceancanvas-project\"},\"formatVersion\":{\"type\":\"integer\",\"minimum\":4},\"coordinateContract\":{\"type\":\"object\"},\"project\":{\"type\":\"object\"},\"planObjects\":{\"type\":\"array\"},\"terrainAssets\":{\"type\":\"array\"},\"pipeline\":{\"type\":\"object\"}}\n}\n";}

    public static String compatibilityMarkdown(){StringBuilder s=new StringBuilder("# Export compatibility\n\n| Target | Elevation | Vectors | Semantics | Groups | Anchors | Notes | Coordinates | Fingerprint |\n|---|---|---|---|---|---|---|---|---|\n");for(var c:formatCapabilities())s.append('|').append(c.target()).append('|').append(c.elevation()).append('|').append(c.vectors()).append('|').append(c.semantics()).append('|').append(c.groups()).append('|').append(c.anchors()).append('|').append(c.notes()).append('|').append(c.coordinateMetadata()).append('|').append(c.fingerprint()).append("|\n");return s.toString();}

    public static String worldPainterManifest(ServerLevel world,OceanCanvasWorkspaceData.WorkProject project){
        var lib=OceanCanvasPlanLibraryData.get(world);StringBuilder s=new StringBuilder("# WorldPainter handoff manifest\n\n");s.append("Project: ").append(project.name()).append(" (`").append(project.id()).append("`)\n\n").append(coordinateContractText()).append("\n## Terrain assets\n\n");
        for(String id:project.terrainAssetIds()){var a=lib.terrainAsset(id);if(a==null)continue;s.append("- ").append(a.name()).append(": bounds ").append(a.minX()).append(',').append(a.minZ()).append(" → ").append(a.maxX()).append(',').append(a.maxZ()).append("; sea ").append(a.seaLevel()).append("; approved WP revision `").append(a.approvedWorldPainterRevision()).append("`\n");}
        s.append("\n## Intended Plan layers\n\n");for(var g:lib.groups())if(g.visible())s.append("- ").append(g.name()).append(" (`").append(g.id()).append("`) opacity ").append(String.format(Locale.ROOT,"%.2f",g.opacity())).append("\n");
        s.append("\nExpected return alignment: identical world bounds/origin, blocks-per-pixel, sea level and orientation unless a saved transform profile explicitly records the change.\n");return s.toString();
    }

    public static String litematicaManifest(ServerLevel world,String projectId){StringBuilder s=new StringBuilder("# Litematica placement manifest\n\n");for(var p:OceanCanvasP5PipelineData.get(world).placements())if(projectId==null||projectId.isBlank()||p.projectId().equals(projectId))s.append("- ").append(p.name()).append(" · file `").append(p.fileName()).append("` · origin ").append(p.originX()).append(',').append(p.originY()).append(',').append(p.originZ()).append(" · rotation ").append(p.rotationDegrees()).append("° · mirror ").append(p.mirror()).append(" · status ").append(p.status()).append(" · version ").append(p.version()).append(p.dependencies().isEmpty()?"":" · depends on "+String.join(", ",p.dependencies())).append('\n');return s.toString();}

    public static String recipesJson(ServerLevel world){StringBuilder s=new StringBuilder("{\n  \"recipes\":[");int n=0;for(var r:OceanCanvasP5PipelineData.get(world).recipes()){if(n++>0)s.append(',');s.append("{\"id\":\"").append(esc(r.id())).append("\",\"name\":\"").append(esc(r.name())).append("\",\"target\":\"").append(esc(r.targetTool())).append("\",\"transformProfileId\":\"").append(esc(r.transformProfileId())).append("\",\"widthPx\":").append(r.widthPx()).append(",\"heightPx\":").append(r.heightPx()).append(",\"layers\":[");for(int i=0;i<r.layerIds().size();i++){if(i>0)s.append(',');s.append('"').append(esc(r.layerIds().get(i))).append('"');}s.append("]}");}return s.append("]\n}\n").toString();}

    public static String transformsJson(ServerLevel world){StringBuilder s=new StringBuilder("{\n  \"coordinateContractVersion\":1,\n  \"profiles\":[");int n=0;for(var t:OceanCanvasP5PipelineData.get(world).transforms()){if(n++>0)s.append(',');s.append("{\"id\":\"").append(esc(t.id())).append("\",\"name\":\"").append(esc(t.name())).append("\",\"source\":\"").append(t.sourceTool()).append("\",\"target\":\"").append(t.targetTool()).append("\",\"originX\":").append(t.originX()).append(",\"originZ\":").append(t.originZ()).append(",\"scale\":").append(t.scale()).append(",\"rotation\":").append(t.rotationDegrees()).append(",\"flipX\":").append(t.flipX()).append(",\"flipZ\":").append(t.flipZ()).append(",\"seaLevel\":").append(t.seaLevel()).append(",\"floorY\":").append(t.floorY()).append(",\"originConvention\":\"").append(esc(t.originConvention())).append("\"}");}return s.append("]\n}\n").toString();}

    private static List<String> lossesFor(String tool){String t=tool==null?"":tool.toUpperCase(Locale.ROOT);for(var c:formatCapabilities())if(c.target().equals(t)){List<String> out=new ArrayList<>();if(bad(c.vectors()))out.add("vectors:"+c.vectors());if(bad(c.semantics()))out.add("semantics:"+c.semantics());if(bad(c.groups()))out.add("groups:"+c.groups());if(bad(c.anchors()))out.add("anchors:"+c.anchors());if(bad(c.notes()))out.add("notes:"+c.notes());return out;}return List.of("Unknown target capability profile: "+t);}
    private static boolean bad(String s){return !("PRESERVED".equals(s)||"LAYERS".equals(s)||"PLACEMENT".equals(s));}
    private static OceanCanvasPlanLibraryData.AssetRevision latest(OceanCanvasPlanLibraryData.TerrainAsset a){return a.revisions().stream().max(Comparator.comparingLong(OceanCanvasPlanLibraryData.AssetRevision::createdAt)).orElse(null);}
    private static int metricInt(String notes,String key,int def){var m=java.util.regex.Pattern.compile("(?:^| )"+java.util.regex.Pattern.quote(key)+"=([-+0-9]+)").matcher(notes==null?"":notes);if(!m.find())return def;try{return Integer.parseInt(m.group(1));}catch(Exception e){return def;}}
    private static boolean rangesOverlap(int a1,int a2,int b1,int b2){return Math.max(a1,b1)<=Math.min(a2,b2);}
    private static Path importRoot(ServerLevel world) throws IOException {Path root=world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("imports");Files.createDirectories(root);return root;}
    public static Path resolveImportFile(ServerLevel world,String raw) throws IOException {String name=Path.of(raw==null?"":raw).getFileName().toString();if(name.isBlank())throw new IllegalArgumentException("Import filename cannot be blank");return importRoot(world).resolve(name);}
    private static BufferedImage tryImage(Path p){try{return ImageIO.read(p.toFile());}catch(Exception e){return null;}}
    private static SeamReport compareRasterEdges(ServerLevel world,OceanCanvasPlanLibraryData.AssetRevision a,OceanCanvasPlanLibraryData.AssetRevision b,String edge){try{Path pa=resolveImportFile(world,a.fileName()),pb=resolveImportFile(world,b.fileName());if(!Files.isRegularFile(pa)||!Files.isRegularFile(pb))return null;BufferedImage ia=tryImage(pa),ib=tryImage(pb);if(ia==null||ib==null)return null;Raster ra=ia.getRaster(),rb=ib.getRaster();boolean vertical=edge.contains("EAST")||edge.contains("WEST");int n=Math.min(4096,vertical?Math.min(ia.getHeight(),ib.getHeight()):Math.min(ia.getWidth(),ib.getWidth()));if(n<1)return null;double sum=0,max=0;for(int k=0;k<n;k++){int ai=vertical?(int)Math.round(k*(ia.getHeight()-1)/(double)Math.max(1,n-1)):(int)Math.round(k*(ia.getWidth()-1)/(double)Math.max(1,n-1));int bi=vertical?(int)Math.round(k*(ib.getHeight()-1)/(double)Math.max(1,n-1)):(int)Math.round(k*(ib.getWidth()-1)/(double)Math.max(1,n-1));double va,vb;if(edge.equals("EAST_WEST")){va=ra.getSampleDouble(ia.getWidth()-1,ai,0);vb=rb.getSampleDouble(0,bi,0);}else if(edge.equals("WEST_EAST")){va=ra.getSampleDouble(0,ai,0);vb=rb.getSampleDouble(ib.getWidth()-1,bi,0);}else if(edge.equals("SOUTH_NORTH")){va=ra.getSampleDouble(ai,ia.getHeight()-1,0);vb=rb.getSampleDouble(bi,0,0);}else{va=ra.getSampleDouble(ai,0,0);vb=rb.getSampleDouble(bi,ib.getHeight()-1,0);}double d=Math.abs(va-vb);sum+=d;max=Math.max(max,d);}double mean=sum/n;double full=Math.max((1L<<Math.min(16,ia.getColorModel().getComponentSize(0)))-1,1);double meanPct=mean/full*100,maxPct=max/full*100;boolean pass=meanPct<=0.25&&maxPct<=2.0;return new SeamReport("","",edge,pass,meanPct,maxPct,n,String.format(Locale.ROOT,"raster seam mean %.3f%% max %.3f%% across %d samples",meanPct,maxPct,n));}catch(Exception e){return null;}}
    private static String sha256(Path p) throws IOException {try(InputStream in=Files.newInputStream(p)){MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[65536];for(int n;(n=in.read(buf))>0;)d.update(buf,0,n);return hex(d.digest());}catch(java.security.GeneralSecurityException e){throw new IOException(e);}}
    private static String sha256(byte[] b){try{return hex(MessageDigest.getInstance("SHA-256").digest(b));}catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder(b.length*2);for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    private static String esc(String v){return v==null?"":v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
}
