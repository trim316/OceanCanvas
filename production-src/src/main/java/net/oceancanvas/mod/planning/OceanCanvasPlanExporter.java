package net.oceancanvas.mod.planning;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.project.OceanCanvasPlanLibraryData;
import net.oceancanvas.mod.project.OceanCanvasPlanningData;
import net.oceancanvas.mod.project.OceanCanvasWorkspaceData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Writes a portable, versioned, layer-aware Plan interchange package without touching terrain. */
public final class OceanCanvasPlanExporter {
    private OceanCanvasPlanExporter() { }
    public record ExportResult(Path directory,int objects,int references,int groups,int terrainAssets,OceanCanvasGeometry.Bounds bounds) { }
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",Locale.ROOT).withZone(ZoneOffset.UTC);

    public static ExportResult export(ServerLevel world,String requestedName) throws IOException {
        var plan=OceanCanvasPlanningData.get(world);
        var lib=OceanCanvasPlanLibraryData.get(world);
        var workspace=OceanCanvasWorkspaceData.get(world);
        List<OceanCanvasPlanningData.PlanningObject> objects=plan.objects().stream().sorted(Comparator.comparingInt(OceanCanvasPlanningData.PlanningObject::drawOrder).thenComparing(OceanCanvasPlanningData.PlanningObject::id)).toList();
        List<OceanCanvasPlanningData.ReferenceLayer> refs=plan.referenceLayers().stream().sorted(Comparator.comparingInt(OceanCanvasPlanningData.ReferenceLayer::drawOrder).thenComparing(OceanCanvasPlanningData.ReferenceLayer::id)).toList();
        List<OceanCanvasPlanLibraryData.PlanGroup> groups=lib.groups().stream().sorted(Comparator.comparingInt(OceanCanvasPlanLibraryData.PlanGroup::drawOrder).thenComparing(OceanCanvasPlanLibraryData.PlanGroup::id)).toList();
        var all=objects.stream().flatMap(o->o.points().stream()).toList();
        var bounds=OceanCanvasGeometry.bounds(all);
        String base=safe(requestedName);if(base.isBlank())base="plan";String folder=base+"-"+STAMP.format(Instant.now());
        MinecraftServer server=world.getServer(); Path root=server.getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("exports").resolve(folder);Files.createDirectories(root);
        Files.writeString(root.resolve("oceancanvas.json"),json(plan,lib,workspace,objects,refs,groups,bounds),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("plan.svg"),svg(objects,bounds),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("current-view.svg"),svg(currentView(objects,groups),bounds),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("objects.csv"),csv(objects),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("layers.csv"),layersCsv(groups,objects),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("references.csv"),referencesCsv(refs),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("reference-sets.csv"),referenceSetsCsv(lib.referenceSets()),StandardCharsets.UTF_8);
        Path layersDir=root.resolve("layers");Files.createDirectories(layersDir);
        Set<String> known=new HashSet<>(); for(var g:groups)known.add(g.id());
        for(var g:groups){var members=objects.stream().filter(o->g.id().equals(o.parentId())).toList();if(!members.isEmpty())Files.writeString(layersDir.resolve(safe(g.name())+"-"+safe(g.id())+".svg"),svg(members,bounds),StandardCharsets.UTF_8);}
        var ungrouped=objects.stream().filter(o->o.parentId().isBlank()||!known.contains(o.parentId())).toList();
        if(!ungrouped.isEmpty())Files.writeString(layersDir.resolve("ungrouped.svg"),svg(ungrouped,bounds),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("coordinate-contract.txt"),OceanCanvasP5PipelineService.coordinateContractText(),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("pipeline-transforms.json"),OceanCanvasP5PipelineService.transformsJson(world),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("export-recipes.json"),OceanCanvasP5PipelineService.recipesJson(world),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("EXPORT-COMPATIBILITY.md"),OceanCanvasP5PipelineService.compatibilityMarkdown(),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("interchange-schema-v4.json"),OceanCanvasP5PipelineService.interchangeSchemaJson(),StandardCharsets.UTF_8);
        String planContract=json(plan,lib,workspace,objects,refs,groups,bounds);
        Files.writeString(root.resolve("plan-fingerprint.sha256"),OceanCanvasP5PipelineService.fingerprintText("PLAN_EXPORT",planContract)+"  oceancanvas.json\n",StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README.txt"),readme(bounds),StandardCharsets.UTF_8);
        return new ExportResult(root,objects.size(),refs.size(),groups.size(),lib.terrainAssets().size(),bounds);
    }


    public record MaskExportResult(Path directory,String selectionType,String selectionId,String selectionName,int objects,int layers,OceanCanvasGeometry.Bounds bounds) { }

    /**
     * Exports a clean, isolated planning selection for use as Gaea/heightmap mask source artwork.
     * targetType is "layer" (the selected layer plus all descendants) or "preset" (the saved
     * layer visibility state). Reference images are intentionally excluded: this export is
     * geometry-only and never mutates terrain.
     */
    public static MaskExportResult exportMasks(ServerLevel world,String requestedName,String targetType,String targetId) throws IOException {
        var plan=OceanCanvasPlanningData.get(world);
        var lib=OceanCanvasPlanLibraryData.get(world);
        String type=targetType==null?"":targetType.trim().toLowerCase(Locale.ROOT);
        String id=targetId==null?"":targetId.trim().toLowerCase(Locale.ROOT);
        List<OceanCanvasPlanningData.PlanningObject> allObjects=plan.objects().stream()
                .sorted(Comparator.comparingInt(OceanCanvasPlanningData.PlanningObject::drawOrder).thenComparing(OceanCanvasPlanningData.PlanningObject::id)).toList();
        List<OceanCanvasPlanLibraryData.PlanGroup> groups=lib.groups().stream()
                .sorted(Comparator.comparingInt(OceanCanvasPlanLibraryData.PlanGroup::drawOrder).thenComparing(OceanCanvasPlanLibraryData.PlanGroup::id)).toList();
        Map<String,OceanCanvasPlanLibraryData.PlanGroup> byId=new LinkedHashMap<>();for(var g:groups)byId.put(g.id(),g);

        Set<String> includedGroups=new LinkedHashSet<>();String selectionName;
        if("layer".equals(type)){
            var selected=lib.group(id);if(selected==null)throw new IllegalArgumentException("Unknown Plan layer: "+id);
            selectionName=selected.name();collectDescendants(selected.id(),groups,includedGroups);
        }else if("preset".equals(type)){
            var preset=lib.preset(id);if(preset==null)throw new IllegalArgumentException("Unknown Plan view preset: "+id);
            selectionName=preset.name();Set<String> visible=new HashSet<>(preset.visibleGroups()),hidden=new HashSet<>(preset.hiddenGroups());
            for(var g:groups)if(presetGroupVisible(g.id(),visible,hidden,byId,new HashSet<>()))includedGroups.add(g.id());
        }else throw new IllegalArgumentException("Mask export target must be layer or preset");

        List<OceanCanvasPlanningData.PlanningObject> selectedObjects=allObjects.stream()
                .filter(OceanCanvasPlanningData.PlanningObject::visible)
                .filter(o->includedGroups.contains(o.parentId())).toList();
        if(selectedObjects.isEmpty())throw new IllegalArgumentException("Selected mask export contains no visible Plan geometry");
        var bounds=OceanCanvasGeometry.bounds(selectedObjects.stream().flatMap(o->o.points().stream()).toList());
        String base=safe(requestedName);if(base.isBlank())base=safe(selectionName);if(base.isBlank())base="gaea-mask";
        String folder=base+"-masks-"+STAMP.format(Instant.now());
        Path root=world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("exports").resolve(folder);Files.createDirectories(root);
        Files.writeString(root.resolve("mask.svg"),maskSvg(selectedObjects,bounds),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("mask-outline.svg"),svg(selectedObjects,bounds),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("mask-index.csv"),maskIndexCsv(selectedObjects,byId),StandardCharsets.UTF_8);
        Path maskDir=root.resolve("masks");Files.createDirectories(maskDir);
        int layerCount=0;
        for(var g:groups){if(!includedGroups.contains(g.id()))continue;var members=selectedObjects.stream().filter(o->g.id().equals(o.parentId())).toList();if(members.isEmpty())continue;layerCount++;Files.writeString(maskDir.resolve(safe(g.name())+"-"+safe(g.id())+".svg"),maskSvg(members,bounds),StandardCharsets.UTF_8);}
        Files.writeString(root.resolve("mask-export.json"),maskManifest(type,id,selectionName,includedGroups,selectedObjects,bounds),StandardCharsets.UTF_8);
        String maskContract=maskManifest(type,id,selectionName,includedGroups,selectedObjects,bounds);
        Files.writeString(root.resolve("coordinate-contract.txt"),OceanCanvasP5PipelineService.coordinateContractText(),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("pipeline-transforms.json"),OceanCanvasP5PipelineService.transformsJson(world),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("export-recipes.json"),OceanCanvasP5PipelineService.recipesJson(world),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("EXPORT-COMPATIBILITY.md"),OceanCanvasP5PipelineService.compatibilityMarkdown(),StandardCharsets.UTF_8);
        Files.writeString(root.resolve("mask-fingerprint.sha256"),OceanCanvasP5PipelineService.fingerprintText("GAEA_MASK",maskContract)+"  mask-export.json\n",StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README-GAEA.txt"),maskReadme(type,selectionName,bounds),StandardCharsets.UTF_8);
        return new MaskExportResult(root,type,id,selectionName,selectedObjects.size(),layerCount,bounds);
    }

    private static void collectDescendants(String id,List<OceanCanvasPlanLibraryData.PlanGroup> groups,Set<String> out){if(!out.add(id))return;for(var g:groups)if(id.equals(g.parentId()))collectDescendants(g.id(),groups,out);}
    private static boolean presetGroupVisible(String id,Set<String> visible,Set<String> hidden,Map<String,OceanCanvasPlanLibraryData.PlanGroup> byId,Set<String> seen){
        if(id==null||id.isBlank())return false;if(!seen.add(id))return false;var g=byId.get(id);if(g==null)return false;
        boolean on=!visible.isEmpty()?visible.contains(id):g.visible();if(hidden.contains(id))on=false;if(!on)return false;
        return g.parentId().isBlank()||presetGroupVisible(g.parentId(),visible,hidden,byId,seen);
    }
    private static String maskSvg(List<OceanCanvasPlanningData.PlanningObject> objects,OceanCanvasGeometry.Bounds b){
        int w=Math.max(1,b.width()),h=Math.max(1,b.height());
        StringBuilder s=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "+w+" "+h+"\">\n<rect width=\"100%\" height=\"100%\" fill=\"black\"/>\n<g fill=\"white\" stroke=\"white\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n");
        for(var o:objects){
            if(o.points().isEmpty())continue;
            double stroke=Math.max(1.0D,o.widthBlocks()>0?o.widthBlocks():1.0D);
            if(o.points().size()==1){
                var p=o.points().get(0);
                s.append("<circle id=\"").append(xml(o.id())).append("\" data-type=\"").append(xml(o.type())).append("\" data-layer=\"").append(xml(o.parentId())).append("\" cx=\"").append(p.x()-b.minX()).append("\" cy=\"").append(p.z()-b.minZ()).append("\" r=\"").append(fmt(Math.max(1.0D,stroke*0.5D))).append("\"/>\n");
                continue;
            }
            boolean area=isAreaType(o.parsedType())&&o.points().size()>=3;
            s.append(area?"<polygon":"<polyline").append(" id=\"").append(xml(o.id())).append("\" data-type=\"").append(xml(o.type())).append("\" data-layer=\"").append(xml(o.parentId())).append("\" points=\"");
            for(var p:o.points())s.append(p.x()-b.minX()).append(',').append(p.z()-b.minZ()).append(' ');
            s.append("\"");
            if(area)s.append(" stroke-width=\"0\"");else s.append(" fill=\"none\" stroke-width=\"").append(fmt(stroke)).append("\"");
            s.append("/>\n");
        }
        return s.append("</g>\n</svg>\n").toString();
    }
    private static boolean isAreaType(OceanCanvasPlanningData.ObjectType t){return switch(t){case CONTINENT,PLATEAU,BASIN,TERRAIN_ZONE,LAKE,CATCHMENT,BIOME_AREA,FOREST,DESERT,SETTLEMENT,DISTRICT,BUILD,PORT,HARBOR,REGION,CITY,FREEFORM_AREA -> true;default -> false;};}
    private static String maskIndexCsv(List<OceanCanvasPlanningData.PlanningObject> objects,Map<String,OceanCanvasPlanLibraryData.PlanGroup> groups){StringBuilder s=new StringBuilder("object_id,name,type,layer_id,layer_name,width_blocks\n");for(var o:objects){var g=groups.get(o.parentId());s.append(csvq(o.id())).append(',').append(csvq(o.name())).append(',').append(csvq(o.type())).append(',').append(csvq(o.parentId())).append(',').append(csvq(g==null?"":g.name())).append(',').append(fmt(o.widthBlocks())).append('\n');}return s.toString();}
    private static String maskManifest(String type,String id,String name,Set<String> groups,List<OceanCanvasPlanningData.PlanningObject> objects,OceanCanvasGeometry.Bounds b){return "{\n  \"format\": \"oceancanvas-gaea-mask\",\n  \"formatVersion\": 1,\n  \"selectionType\": \""+esc(type)+"\",\n  \"selectionId\": \""+esc(id)+"\",\n  \"selectionName\": \""+esc(name)+"\",\n  \"coordinateSystem\": \"minecraft-xz-north-up\",\n  \"units\": \"blocks\",\n  \"bounds\": {\"minX\":"+b.minX()+",\"minZ\":"+b.minZ()+",\"maxX\":"+b.maxX()+",\"maxZ\":"+b.maxZ()+"},\n  \"layerIds\": [\""+String.join("\",\"",groups.stream().map(OceanCanvasPlanExporter::esc).toList())+"\"],\n  \"objectCount\": "+objects.size()+"\n}\n";}
    private static String maskReadme(String type,String name,OceanCanvasGeometry.Bounds b){return "Ocean Canvas selective Gaea mask export\n\nSelection: "+type+" — "+name+"\nBounds: "+b.minX()+","+b.minZ()+" to "+b.maxX()+","+b.maxZ()+" (Minecraft X/Z, north-up, blocks)\n\nmask.svg is a clean black-background/white-geometry mask suitable for rasterization before Gaea use. Area-like Plan objects are filled white; line-like objects use their planned width when available. mask-outline.svg preserves ordinary vector outlines. masks/ contains one isolated mask per populated included layer, all sharing the exact same viewBox/bounds so they remain pixel-aligned after rasterization. Reference images and unrelated Plan geometry are intentionally excluded.\n";}

    private static List<OceanCanvasPlanningData.PlanningObject> currentView(List<OceanCanvasPlanningData.PlanningObject> objects,List<OceanCanvasPlanLibraryData.PlanGroup> groups){
        Map<String,OceanCanvasPlanLibraryData.PlanGroup> byId=new HashMap<>();for(var g:groups)byId.put(g.id(),g);
        return objects.stream().filter(OceanCanvasPlanningData.PlanningObject::visible).filter(o->groupVisible(o.parentId(),byId,new HashSet<>())).toList();
    }
    private static boolean groupVisible(String id,Map<String,OceanCanvasPlanLibraryData.PlanGroup> byId,Set<String> seen){if(id==null||id.isBlank())return true;if(!seen.add(id))return false;var g=byId.get(id);return g==null||(g.visible()&&groupVisible(g.parentId(),byId,seen));}

    private static String json(OceanCanvasPlanningData plan,OceanCanvasPlanLibraryData lib,OceanCanvasWorkspaceData workspace,List<OceanCanvasPlanningData.PlanningObject> objects,List<OceanCanvasPlanningData.ReferenceLayer> refs,List<OceanCanvasPlanLibraryData.PlanGroup> groups,OceanCanvasGeometry.Bounds b){StringBuilder s=new StringBuilder();
        s.append("{\n  \"format\": \"oceancanvas-plan\",\n  \"formatVersion\": 4,\n  \"coordinateSystem\": \"minecraft-xz-north-up\",\n  \"units\": \"blocks\",\n  \"seaLevel\": 63,\n");
        s.append("  \"bounds\": {\"minX\":").append(b.minX()).append(",\"minZ\":").append(b.minZ()).append(",\"maxX\":").append(b.maxX()).append(",\"maxZ\":").append(b.maxZ()).append("},\n");
        s.append("  \"planningSchema\": ").append(plan.schema()).append(",\n  \"librarySchema\": ").append(lib.schema()).append(",\n  \"workspaceSchema\": ").append(workspace.schema()).append(",\n");
        s.append("  \"groups\": [\n");int gi=0;for(var g:groups){if(gi++>0)s.append(",\n");s.append("    {\"id\":\"").append(esc(g.id())).append("\",\"name\":\"").append(esc(g.name())).append("\",\"parentId\":\"").append(esc(g.parentId())).append("\",\"category\":\"").append(esc(g.category())).append("\",\"visible\":").append(g.visible()).append(",\"locked\":").append(g.locked()).append(",\"opacity\":").append(fmt(g.opacity())).append(",\"drawOrder\":").append(g.drawOrder()).append('}');}s.append("\n  ],\n");
        s.append("  \"references\": [\n");int ri=0;for(var r:refs){if(ri++>0)s.append(",\n");s.append("    {\"id\":\"").append(esc(r.id())).append("\",\"name\":\"").append(esc(r.name())).append("\",\"assetId\":\"").append(esc(r.assetId())).append("\",\"visible\":").append(r.visible()).append(",\"locked\":").append(r.locked()).append(",\"opacity\":").append(fmt(r.opacity())).append(",\"drawOrder\":").append(r.drawOrder()).append(",\"rotationDegrees\":").append(fmt(r.rotationDegrees())).append(",\"bounds\":[").append(r.minX()).append(',').append(r.minZ()).append(',').append(r.maxX()).append(',').append(r.maxZ()).append("],\"registrationPoints\":[");for(int i=0;i<r.registrationPoints().size();i++){if(i>0)s.append(',');var p=r.registrationPoints().get(i);s.append('[').append(fmt(p.imageX())).append(',').append(fmt(p.imageY())).append(',').append(p.worldX()).append(',').append(p.worldZ()).append(']');}s.append("]}");}s.append("\n  ],\n");
        s.append("  \"referenceSets\": [\n");int si=0;for(var set:lib.referenceSets()){if(si++>0)s.append(",\n");s.append("    {\"id\":\"").append(esc(set.id())).append("\",\"name\":\"").append(esc(set.name())).append("\",\"referenceIds\":[");for(int i=0;i<set.referenceIds().size();i++){if(i>0)s.append(',');s.append('\"').append(esc(set.referenceIds().get(i))).append('\"');}s.append("],\"visible\":").append(set.visible()).append(",\"locked\":").append(set.locked()).append(",\"opacity\":").append(fmt(set.opacity())).append(",\"drawOrder\":").append(set.drawOrder()).append('}');}s.append("\n  ],\n");
        s.append("  \"viewPresets\": [\n");int pi=0;for(var preset:lib.presets()){if(pi++>0)s.append(",\n");s.append("    {\"id\":\"").append(esc(preset.id())).append("\",\"name\":\"").append(esc(preset.name())).append("\",\"visibleGroups\":\"").append(esc(String.join(",",preset.visibleGroups()))).append("\",\"hiddenGroups\":\"").append(esc(String.join(",",preset.hiddenGroups()))).append("\",\"visibleReferences\":\"").append(esc(String.join(",",preset.visibleReferences()))).append("\",\"hiddenReferences\":\"").append(esc(String.join(",",preset.hiddenReferences()))).append("\",\"visibleReferenceSets\":\"").append(esc(String.join(",",preset.visibleReferenceSets()))).append("\",\"hiddenReferenceSets\":\"").append(esc(String.join(",",preset.hiddenReferenceSets()))).append("\"}");}s.append("\n  ],\n");
        s.append("  \"objects\": [\n");int oi=0;for(var o:objects){if(oi++>0)s.append(",\n");s.append("    {\"id\":\"").append(esc(o.id())).append("\",\"type\":\"").append(esc(o.type())).append("\",\"name\":\"").append(esc(o.name())).append("\",\"parentId\":\"").append(esc(o.parentId())).append("\",\"visible\":").append(o.visible()).append(",\"locked\":").append(o.locked()).append(",\"drawOrder\":").append(o.drawOrder()).append(",\"widthBlocks\":").append(fmt(o.widthBlocks())).append(",\"implemented\":").append(o.implemented()).append(",\"points\":[");for(int i=0;i<o.points().size();i++){if(i>0)s.append(',');var p=o.points().get(i);s.append('[').append(p.x()).append(',').append(p.z()).append(']');}s.append("],\"elevation\":[");for(int i=0;i<o.elevationProfile().size();i++){if(i>0)s.append(',');var e=o.elevationProfile().get(i);s.append('[').append(fmt(e.along())).append(',').append(e.y()).append(']');}s.append("]}");}s.append("\n  ],\n  \"terrainAssets\": [\n");int ai=0;for(var a:lib.terrainAssets()){if(ai++>0)s.append(",\n");s.append("    {\"id\":\"").append(esc(a.id())).append("\",\"name\":\"").append(esc(a.name())).append("\",\"status\":\"").append(esc(a.status())).append("\",\"bounds\": [").append(a.minX()).append(',').append(a.minZ()).append(',').append(a.maxX()).append(',').append(a.maxZ()).append("],\"seaLevel\":").append(a.seaLevel()).append(",\"approvedGaeaRevision\":\"").append(esc(a.approvedGaeaRevision())).append("\",\"approvedWorldPainterRevision\":\"").append(esc(a.approvedWorldPainterRevision())).append("\",\"placement\":\"").append(esc(a.placementData())).append("\"}");}s.append("\n  ]\n}\n");return s.toString();}

    private static String svg(List<OceanCanvasPlanningData.PlanningObject> objects,OceanCanvasGeometry.Bounds b){int w=Math.max(1,b.width()),h=Math.max(1,b.height());StringBuilder s=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "+w+" "+h+"\">\n<g fill=\"none\" stroke=\"black\" stroke-width=\"1\">\n");for(var o:objects){if(o.points().isEmpty())continue;s.append("<polyline id=\"").append(xml(o.id())).append("\" data-type=\"").append(xml(o.type())).append("\" data-layer=\"").append(xml(o.parentId())).append("\" points=\"");for(var p:o.points())s.append(p.x()-b.minX()).append(',').append(p.z()-b.minZ()).append(' ');s.append("\"/><!-- ").append(xml(o.name())).append(" -->\n");}return s.append("</g>\n</svg>\n").toString();}
    private static String csv(List<OceanCanvasPlanningData.PlanningObject> objects){StringBuilder s=new StringBuilder("object_id,type,name,layer_id,visible,locked,draw_order,point_index,x,z,width_blocks\n");for(var o:objects)for(int i=0;i<o.points().size();i++){var p=o.points().get(i);s.append(csvq(o.id())).append(',').append(csvq(o.type())).append(',').append(csvq(o.name())).append(',').append(csvq(o.parentId())).append(',').append(o.visible()).append(',').append(o.locked()).append(',').append(o.drawOrder()).append(',').append(i).append(',').append(p.x()).append(',').append(p.z()).append(',').append(fmt(o.widthBlocks())).append('\n');}return s.toString();}
    private static String layersCsv(List<OceanCanvasPlanLibraryData.PlanGroup> groups,List<OceanCanvasPlanningData.PlanningObject> objects){StringBuilder s=new StringBuilder("layer_id,name,parent_id,category,visible,locked,opacity,draw_order,object_count\n");for(var g:groups){long count=objects.stream().filter(o->g.id().equals(o.parentId())).count();s.append(csvq(g.id())).append(',').append(csvq(g.name())).append(',').append(csvq(g.parentId())).append(',').append(csvq(g.category())).append(',').append(g.visible()).append(',').append(g.locked()).append(',').append(fmt(g.opacity())).append(',').append(g.drawOrder()).append(',').append(count).append('\n');}return s.toString();}
    private static String referencesCsv(List<OceanCanvasPlanningData.ReferenceLayer> refs){StringBuilder s=new StringBuilder("reference_id,name,asset_id,visible,locked,opacity,draw_order,min_x,min_z,max_x,max_z,rotation_degrees,registration_points\n");for(var r:refs)s.append(csvq(r.id())).append(',').append(csvq(r.name())).append(',').append(csvq(r.assetId())).append(',').append(r.visible()).append(',').append(r.locked()).append(',').append(fmt(r.opacity())).append(',').append(r.drawOrder()).append(',').append(r.minX()).append(',').append(r.minZ()).append(',').append(r.maxX()).append(',').append(r.maxZ()).append(',').append(fmt(r.rotationDegrees())).append(',').append(r.registrationPoints().size()).append('\n');return s.toString();}
    private static String referenceSetsCsv(List<OceanCanvasPlanLibraryData.ReferenceSet> sets){StringBuilder s=new StringBuilder("set_id,name,visible,locked,opacity,draw_order,reference_ids\n");for(var set:sets)s.append(csvq(set.id())).append(',').append(csvq(set.name())).append(',').append(set.visible()).append(',').append(set.locked()).append(',').append(fmt(set.opacity())).append(',').append(set.drawOrder()).append(',').append(csvq(String.join(";",set.referenceIds()))).append('\n');return s.toString();}
    private static String readme(OceanCanvasGeometry.Bounds b){return "Ocean Canvas Plan interchange package (format v4)\n\nCoordinates are Minecraft world X/Z, north-up, in blocks.\nBounds: "+b.minX()+","+b.minZ()+" to "+b.maxX()+","+b.maxZ()+"\n\noceancanvas.json is the authoritative metadata contract. plan.svg contains all vector geometry; current-view.svg respects object/layer visibility at export time. layers/ contains one SVG per geographic layer. layers.csv, references.csv, and reference-sets.csv preserve hierarchy, overlay organization, reference registration metadata, and per-set opacity. viewPresets now also record which Reference Sets were visible/hidden when saved, in addition to raw layer/reference visibility. Reference image binary files are intentionally not embedded; asset IDs remain stable so missing/relinked images cannot corrupt Plan geometry. Keep returned Gaea/WorldPainter files registered to the same Terrain Asset bounds and record revisions in Ocean Canvas before placement.\n";}
    private static String safe(String v){if(v==null)return "";return v.trim().replaceAll("[^A-Za-z0-9._-]+","_").replaceAll("^_+|_+$","");}
    private static String esc(String v){if(v==null)return "";return v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
    private static String xml(String v){if(v==null)return "";return v.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    private static String csvq(String v){return "\""+(v==null?"":v.replace("\"","\"\""))+"\"";}
    private static String fmt(double v){return String.format(Locale.ROOT,"%.4f",v);}
}
