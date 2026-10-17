package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads back an {@code .oceanproject} package written by {@link OceanCanvasProjectPackageExporter}.
 * Imports are metadata-only. They never mutate Minecraft terrain, Region rules, or maintenance state.
 *
 * <h2>Collision policy</h2>
 * Imported Plan-object, Terrain-Asset and task ids are keep-if-free / remap-if-colliding. Every
 * in-package reference is rewritten through the same map. A normal import applies that policy to
 * the package Project id too. A merge intentionally keeps the selected destination Project id.
 *
 * <h2>Merge policy</h2>
 * Merge is additive, never replacement. The destination Project keeps its id/name/status/parent/
 * Region/template/notes/timestamps. Incoming Plan/Terrain links and milestones are unioned into it,
 * and imported tasks are rebound to it. This prevents an imported package from silently rewriting
 * the user's existing execution-project identity or workflow state.
 */
public final class OceanCanvasProjectPackageImporter {
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private OceanCanvasProjectPackageImporter() { }

    public record Result(String projectId, String projectName, int tasks, int planObjects,
                         int terrainAssets, List<String> warnings, boolean merged) { }

    /** Non-mutating, world-aware summary used by the import confirmation screen. */
    public record Preview(String sourceProjectId, String sourceProjectName, int tasks, int planObjects,
                          int terrainAssets, int taskIdCollisions, int planObjectIdCollisions,
                          int terrainAssetIdCollisions, boolean projectIdCollision, int unresolvedReferences) { }

    private record PackageData(Map<String,Object> root, Map<String,Object> project,
                               List<Object> planObjects, List<Object> terrainAssets, List<Object> tasks,List<Object> pipelineCheckpoints) { }

    /** Resolve only inside the current world's Ocean Canvas project inbox. */
    public static Path resolve(ServerLevel world, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isBlank()) throw new IllegalArgumentException("filename cannot be blank");
        Path root = world.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("projects");
        String fileOnly = Path.of(name).getFileName().toString();
        if (!fileOnly.toLowerCase(Locale.ROOT).endsWith(".oceanproject")) fileOnly = fileOnly + ".oceanproject";
        return root.resolve(fileOnly);
    }

    /** Parse and inspect without changing any SavedData. */
    @SuppressWarnings("unchecked")
    public static Preview preview(ServerLevel world, Path file) throws IOException {
        PackageData pkg = parse(file);
        OceanCanvasWorkspaceData workspace = OceanCanvasWorkspaceData.get(world);
        OceanCanvasPlanningData planning = OceanCanvasPlanningData.get(world);
        OceanCanvasPlanLibraryData lib = OceanCanvasPlanLibraryData.get(world);
        int taskCollisions=0, planCollisions=0, terrainCollisions=0, unresolved=0;
        Set<String> packageTaskIds=new LinkedHashSet<>(), packagePlanIds=new LinkedHashSet<>();
        for(Object o:pkg.tasks())packageTaskIds.add(str((Map<String,Object>)o,"id",""));
        for(Object o:pkg.planObjects())packagePlanIds.add(str((Map<String,Object>)o,"id",""));
        for(Object o:pkg.tasks()){
            Map<String,Object> t=(Map<String,Object>)o; String id=str(t,"id",""); if(!id.isBlank()&&workspace.task(id)!=null)taskCollisions++;
            String parent=str(t,"parentTaskId",""); if(!parent.isBlank()&&!packageTaskIds.contains(parent))unresolved++;
            for(Object d:listField(t,"dependencies"))if(!packageTaskIds.contains(String.valueOf(d)))unresolved++;
            String plan=str(t,"planningObjectId",""); if(!plan.isBlank()&&!packagePlanIds.contains(plan))unresolved++;
        }
        for(Object o:pkg.planObjects()){
            String id=str((Map<String,Object>)o,"id",""); if(!id.isBlank()&&planning.object(id)!=null)planCollisions++;
        }
        for(Object o:pkg.terrainAssets()){
            String id=str((Map<String,Object>)o,"id",""); if(!id.isBlank()&&lib.terrainAsset(id)!=null)terrainCollisions++;
        }
        String region=str(pkg.project(),"regionName","");
        if(!region.isBlank()&&net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(region)==null)unresolved++;
        String parent=str(pkg.project(),"parentProjectId","");
        if(!parent.isBlank()&&workspace.project(parent)==null)unresolved++;
        String projectId=str(pkg.project(),"id","");
        return new Preview(projectId,str(pkg.project(),"name","Imported Project"),pkg.tasks().size(),pkg.planObjects().size(),pkg.terrainAssets().size(),
                taskCollisions,planCollisions,terrainCollisions,!projectId.isBlank()&&workspace.project(projectId)!=null,unresolved);
    }

    public static Result importPackage(ServerLevel world, Path file) throws IOException {
        return importPackage(world,file,"");
    }

    /**
     * Imports as a new Project when {@code mergeProjectId} is blank; otherwise merges additively
     * into that existing Project. The destination is revalidated immediately before mutation.
     */
    @SuppressWarnings("unchecked")
    public static Result importPackage(ServerLevel world, Path file, String mergeProjectId) throws IOException {
        PackageData pkg=parse(file);
        Map<String,Object> projectRaw=pkg.project(); List<Object> planObjectsRaw=pkg.planObjects();
        List<Object> terrainAssetsRaw=pkg.terrainAssets(); List<Object> tasksRaw=pkg.tasks();
        List<Object> checkpointsRaw=pkg.pipelineCheckpoints();

        List<String>warnings=new ArrayList<>();
        OceanCanvasWorkspaceData workspace=OceanCanvasWorkspaceData.get(world);
        OceanCanvasPlanningData planning=OceanCanvasPlanningData.get(world);
        OceanCanvasPlanLibraryData lib=OceanCanvasPlanLibraryData.get(world);
        String mergeId=mergeProjectId==null?"":mergeProjectId.trim().toLowerCase(Locale.ROOT);
        OceanCanvasWorkspaceData.WorkProject mergeTarget=mergeId.isBlank()?null:workspace.project(mergeId);
        if(!mergeId.isBlank()&&mergeTarget==null)throw new IllegalArgumentException("Merge target Project no longer exists: "+mergeId);
        boolean merged=mergeTarget!=null;

        // Pass 1: decide every imported id before rewriting any reference.
        Map<String,String> planObjectIds=new HashMap<>();
        for(Object o:planObjectsRaw){Map<String,Object>po=(Map<String,Object>)o;String oldId=str(po,"id","");String type=OceanCanvasPlanningData.ObjectType.parse(str(po,"type","")).name().toLowerCase(Locale.ROOT);planObjectIds.put(oldId,planning.object(oldId)!=null?planning.newId(type):(oldId.isBlank()?planning.newId(type):oldId));}
        Map<String,String> terrainAssetIds=new HashMap<>();
        for(Object o:terrainAssetsRaw){Map<String,Object>ta=(Map<String,Object>)o;String oldId=str(ta,"id","");terrainAssetIds.put(oldId,lib.terrainAsset(oldId)!=null?lib.newId("terrain"):(oldId.isBlank()?lib.newId("terrain"):oldId));}
        Map<String,String> taskIds=new HashMap<>();
        for(Object o:tasksRaw){Map<String,Object>t=(Map<String,Object>)o;String oldId=str(t,"id","");taskIds.put(oldId,workspace.task(oldId)!=null?workspace.newId("task"):(oldId.isBlank()?workspace.newId("task"):oldId));}
        Map<String,String> phaseIds=new HashMap<>(); Set<String> usedPhaseIds=new LinkedHashSet<>(); if(merged)for(var ph:mergeTarget.phases())usedPhaseIds.add(ph.id());
        for(Object o:listField(projectRaw,"phases")){Map<String,Object>ph=(Map<String,Object>)o;String oldId=str(ph,"id","");String id=oldId.isBlank()?workspace.newId("phase"):oldId;while(usedPhaseIds.contains(id))id=workspace.newId("phase");usedPhaseIds.add(id);phaseIds.put(oldId,id);}
        String oldProjectId=str(projectRaw,"id","");
        String newProjectId=merged?mergeTarget.id():(workspace.project(oldProjectId)!=null?workspace.newId("project"):(oldProjectId.isBlank()?workspace.newId("project"):oldProjectId));

        // Pass 2: Plan objects.
        for(Object o:planObjectsRaw){Map<String,Object>po=(Map<String,Object>)o;String id=planObjectIds.get(str(po,"id",""));var type=OceanCanvasPlanningData.ObjectType.parse(str(po,"type",""));List<OceanCanvasPlanningData.Point>points=new ArrayList<>();for(Object p:listField(po,"points")){List<Object>pair=(List<Object>)p;if(pair.size()>=2)points.add(new OceanCanvasPlanningData.Point((int)num(pair,0,0),(int)num(pair,1,0)));}planning.putObject(new OceanCanvasPlanningData.PlanningObject(id,type.name(),str(po,"name",id),points,true,false,planning.objects().size(),"","","",0xD055FFFF,0x2055FFFF,0.0D,List.of(),"",false));}

        // Pass 3: Terrain Assets.
        for(Object o:terrainAssetsRaw){Map<String,Object>ta=(Map<String,Object>)o;String id=terrainAssetIds.get(str(ta,"id",""));List<Object>bounds=listField(ta,"bounds");int minX=(int)num(bounds,0,0),minZ=(int)num(bounds,1,0),maxX=(int)num(bounds,2,0),maxZ=(int)num(bounds,3,0);lib.putTerrainAsset(new OceanCanvasPlanLibraryData.TerrainAsset(id,str(ta,"name",id),List.of(),minX,minZ,maxX,maxZ,(int)num(ta,"seaLevel",63),str(ta,"status","PLANNED"),List.of(),str(ta,"approvedGaeaRevision",""),str(ta,"approvedWorldPainterRevision",""),str(ta,"placementData",""),""));}

        List<String>projectPlanIds=new ArrayList<>();for(Object o:planObjectsRaw)projectPlanIds.add(planObjectIds.get(str((Map<String,Object>)o,"id","")));
        List<String>projectTerrainIds=new ArrayList<>();for(Object o:terrainAssetsRaw)projectTerrainIds.add(terrainAssetIds.get(str((Map<String,Object>)o,"id","")));
        List<String>incomingMilestones=new ArrayList<>();for(Object m:listField(projectRaw,"milestones"))incomingMilestones.add(String.valueOf(m));
        List<OceanCanvasWorkspaceData.ProjectPhase> incomingPhases=new ArrayList<>();for(Object o:listField(projectRaw,"phases")){Map<String,Object>ph=(Map<String,Object>)o;incomingPhases.add(new OceanCanvasWorkspaceData.ProjectPhase(phaseIds.get(str(ph,"id","")),str(ph,"name","Phase"),str(ph,"status","PLANNED"),(int)num(ph,"order",incomingPhases.size())));}
        List<OceanCanvasWorkspaceData.ProjectBlocker> incomingBlockers=new ArrayList<>();Set<String>usedBlockerIds=new LinkedHashSet<>();if(merged)for(var b:mergeTarget.blockers())usedBlockerIds.add(b.id());
        for(Object o:listField(projectRaw,"blockers")){Map<String,Object>b=(Map<String,Object>)o;String id=str(b,"id","");if(id.isBlank()||usedBlockerIds.contains(id))id=workspace.newId("blocker");usedBlockerIds.add(id);String scope=str(b,"scopeType","PROJECT");String rawScope=str(b,"scopeId","");String mappedScope=switch(scope.toUpperCase(Locale.ROOT)){case "TASK"->taskIds.getOrDefault(rawScope,"");case "ASSET"->terrainAssetIds.getOrDefault(rawScope,"");case "PLAN"->planObjectIds.getOrDefault(rawScope,"");default->"";};if(!rawScope.isBlank()&&mappedScope.isBlank()&&!"PROJECT".equalsIgnoreCase(scope))warnings.add("Blocker '"+str(b,"text","Blocker")+"': scoped target was not part of the package - converted to Project scope.");incomingBlockers.add(new OceanCanvasWorkspaceData.ProjectBlocker(id,str(b,"text","Blocker"),bool(b,"resolved",false),mappedScope.isBlank()?"PROJECT":scope,mappedScope));}
        List<OceanCanvasWorkspaceData.ProjectMilestone> incomingRecords=new ArrayList<>();Set<String>usedMilestoneIds=new LinkedHashSet<>();if(merged)for(var m:mergeTarget.milestoneRecords())usedMilestoneIds.add(m.id());
        for(Object o:listField(projectRaw,"milestoneRecords")){Map<String,Object>m=(Map<String,Object>)o;String id=str(m,"id","");if(id.isBlank()||usedMilestoneIds.contains(id))id=workspace.newId("milestone");usedMilestoneIds.add(id);String rawPhase=str(m,"phaseId","");String rawTask=str(m,"taskId","");String mappedPhase=rawPhase.isBlank()?"":phaseIds.getOrDefault(rawPhase,"");String mappedTask=rawTask.isBlank()?"":taskIds.getOrDefault(rawTask,"");if(!rawPhase.isBlank()&&mappedPhase.isBlank())warnings.add("Milestone '"+str(m,"name","Milestone")+"': phase link was not part of the package - cleared.");if(!rawTask.isBlank()&&mappedTask.isBlank())warnings.add("Milestone '"+str(m,"name","Milestone")+"': task link was not part of the package - cleared.");incomingRecords.add(new OceanCanvasWorkspaceData.ProjectMilestone(id,str(m,"name","Milestone"),str(m,"status","PLANNED"),mappedPhase,mappedTask,str(m,"notes",""),(int)num(m,"progress",0),str(m,"targetDate","")));}
        String rawCurrentTask=str(projectRaw,"currentTaskId","");String incomingCurrentTask=rawCurrentTask.isBlank()?"":taskIds.getOrDefault(rawCurrentTask,"");
        String projectName;

        // Pass 4: create new Project or add package execution metadata to the selected destination.
        if(merged){
            var plans=new ArrayList<>(mergeTarget.planningObjectIds());for(String id:projectPlanIds)if(!plans.contains(id))plans.add(id);
            var terrain=new ArrayList<>(mergeTarget.terrainAssetIds());for(String id:projectTerrainIds)if(!terrain.contains(id))terrain.add(id);
            var milestones=new ArrayList<>(mergeTarget.milestones());for(String m:incomingMilestones)if(!m.isBlank()&&!milestones.contains(m)&&milestones.size()<128)milestones.add(m);
            var phases=new ArrayList<>(mergeTarget.phases());phases.addAll(incomingPhases);
            var blockers=new ArrayList<>(mergeTarget.blockers());blockers.addAll(incomingBlockers);
            var records=new ArrayList<>(mergeTarget.milestoneRecords());records.addAll(incomingRecords);
            projectName=mergeTarget.name();
            workspace.putProject(new OceanCanvasWorkspaceData.WorkProject(mergeTarget.id(),mergeTarget.name(),mergeTarget.parentProjectId(),plans,terrain,mergeTarget.regionName(),mergeTarget.status(),mergeTarget.templateId(),mergeTarget.notes(),milestones,mergeTarget.createdAt(),System.currentTimeMillis(),phases,blockers,mergeTarget.currentTaskId(),records));
        }else{
            String regionName=str(projectRaw,"regionName","");if(!regionName.isBlank()&&net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(regionName)==null){warnings.add("Region '"+regionName+"' does not exist in this world - project's Region link was cleared.");regionName="";}
            String parentProjectId=str(projectRaw,"parentProjectId","");if(!parentProjectId.isBlank()&&workspace.project(parentProjectId)==null){warnings.add("Parent project '"+parentProjectId+"' does not exist in this world - cleared.");parentProjectId="";}
            projectName=str(projectRaw,"name","Imported Project");
            workspace.putProject(new OceanCanvasWorkspaceData.WorkProject(newProjectId,projectName,parentProjectId,projectPlanIds,projectTerrainIds,regionName,str(projectRaw,"status","PLANNED"),str(projectRaw,"templateId",""),str(projectRaw,"notes",""),incomingMilestones,(long)num(projectRaw,"createdAt",System.currentTimeMillis()),System.currentTimeMillis(),incomingPhases,incomingBlockers,incomingCurrentTask,incomingRecords));
        }

        // Pass 5: tasks. Dependencies/parents only resolve within this package; all tasks bind to destination Project.
        int importedTasks=0;
        for(Object o:tasksRaw){
            Map<String,Object>t=(Map<String,Object>)o;String oldTaskId=str(t,"id","");String id=taskIds.get(oldTaskId);String title=str(t,"title","Task");
            String parentTaskId="";String rawParent=str(t,"parentTaskId","");if(!rawParent.isBlank()){String mapped=taskIds.get(rawParent);if(mapped!=null)parentTaskId=mapped;else warnings.add("Task '"+title+"': parent task '"+rawParent+"' was not part of the package - cleared.");}
            List<String>dependencies=new ArrayList<>();for(Object d:listField(t,"dependencies")){String rawDep=String.valueOf(d);String mapped=taskIds.get(rawDep);if(mapped!=null)dependencies.add(mapped);else warnings.add("Task '"+title+"': dependency '"+rawDep+"' was not part of the package - dropped.");}
            String planningObjectId="";String rawPlanObj=str(t,"planningObjectId","");if(!rawPlanObj.isBlank()){String mapped=planObjectIds.get(rawPlanObj);if(mapped!=null)planningObjectId=mapped;else warnings.add("Task '"+title+"': linked Plan object '"+rawPlanObj+"' was not part of the package - cleared.");}
            List<OceanCanvasWorkspaceData.ChecklistItem>checklist=new ArrayList<>();for(Object c:listField(t,"checklist")){Map<String,Object>cm=(Map<String,Object>)c;checklist.add(new OceanCanvasWorkspaceData.ChecklistItem(str(cm,"id",""),str(cm,"text",""),bool(cm,"complete",false)));}
            String rawPhase=str(t,"phaseId","");String phaseId=rawPhase.isBlank()?"":phaseIds.getOrDefault(rawPhase,"");if(!rawPhase.isBlank()&&phaseId.isBlank())warnings.add("Task '"+title+"': phase '"+rawPhase+"' was not part of the package - cleared.");
            long now=System.currentTimeMillis();workspace.putTask(new OceanCanvasWorkspaceData.GeoTask(id,title,str(t,"notes",""),(int)num(t,"x",0),(int)num(t,"z",0),"",planningObjectId,str(t,"status","PLANNED"),(int)num(t,"priority",1),now,now,newProjectId,parentTaskId,dependencies,checklist,num(t,"weight",1.0D),phaseId));importedTasks++;
        }
        workspace.setActiveWorkProject(newProjectId);
        var checkpointData=OceanCanvasPipelineSnapshotData.get(world);for(Object o:checkpointsRaw){Map<String,Object>c=(Map<String,Object>)o;checkpointData.importSummary(new OceanCanvasPipelineSnapshotData.Entry("portable",newProjectId,(long)num(c,"epochMillis",System.currentTimeMillis()),str(c,"label","Imported checkpoint"),(int)num(c,"plans",0),(int)num(c,"implemented",0),(int)num(c,"assets",0),(int)num(c,"revisions",0),(int)num(c,"gaea",0),(int)num(c,"worldPainter",0),(int)num(c,"placement",0),(int)num(c,"tasks",0),(int)num(c,"complete",0),(int)num(c,"ready",0),(int)num(c,"phasesComplete",0),(int)num(c,"milestonesComplete",0),(int)num(c,"blockers",0)),newProjectId);}
        workspace.addJournal((merged?"Merged project package into ":"Imported project package: ")+projectName,"",0,0);
        return new Result(newProjectId,projectName,importedTasks,planObjectsRaw.size(),terrainAssetsRaw.size(),List.copyOf(warnings),merged);
    }

    @SuppressWarnings("unchecked")
    private static PackageData parse(Path file) throws IOException {
        if(!Files.isRegularFile(file))throw new IllegalArgumentException("No such package: "+file.getFileName());
        Object parsed;try{parsed=OceanCanvasJson.parse(readManifest(file));}catch(RuntimeException ex){throw new IllegalArgumentException("Malformed manifest.json: "+ex.getMessage());}
        if(!(parsed instanceof Map<?,?>))throw new IllegalArgumentException("manifest.json is not a JSON object");
        Map<String,Object>root=(Map<String,Object>)parsed;String format=str(root,"format","");if(!"oceancanvas-project".equals(format))throw new IllegalArgumentException("Not an Ocean Canvas project package (format='"+format+"')");
        long formatVersion=(long)num(root,"formatVersion",0);if(formatVersion<1||formatVersion>4)throw new IllegalArgumentException("Unsupported package format version "+formatVersion+" (this build reads v1-v4)");
        Map<String,Object>projectRaw=objField(root,"project");if(projectRaw.isEmpty())throw new IllegalArgumentException("manifest.json is missing the 'project' object");
        return new PackageData(root,projectRaw,listField(root,"planObjects"),listField(root,"terrainAssets"),listField(root,"tasks"),listField(root,"pipelineCheckpoints"));
    }

    private static String readManifest(Path file) throws IOException {
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(file),StandardCharsets.UTF_8)){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(!"manifest.json".equals(entry.getName())) continue;
                long declared=entry.getSize();
                if(declared>MAX_MANIFEST_BYTES)throw new IllegalArgumentException("manifest.json exceeds "+MAX_MANIFEST_BYTES+" bytes");
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream((int)Math.max(0L,Math.min(declared,64*1024L)));
                byte[] buffer=new byte[8192]; int total=0,read;
                while((read=zip.read(buffer))!=-1){
                    total+=read;
                    if(total>MAX_MANIFEST_BYTES)throw new IllegalArgumentException("manifest.json exceeds "+MAX_MANIFEST_BYTES+" bytes after decompression");
                    out.write(buffer,0,read);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("Package is missing manifest.json - not a valid .oceanproject file");
    }
    private static String str(Map<String,Object>m,String key,String def){Object v=m.get(key);return v==null?def:String.valueOf(v);}private static boolean bool(Map<String,Object>m,String key,boolean def){Object v=m.get(key);return v instanceof Boolean b?b:def;}private static double num(Map<String,Object>m,String key,double def){Object v=m.get(key);return v instanceof Number n?n.doubleValue():def;}private static double num(List<Object>l,int idx,double def){if(idx>=l.size())return def;Object v=l.get(idx);return v instanceof Number n?n.doubleValue():def;}
    @SuppressWarnings("unchecked") private static Map<String,Object>objField(Map<String,Object>m,String key){Object v=m.get(key);return v instanceof Map?(Map<String,Object>)v:Map.of();}
    @SuppressWarnings("unchecked") private static List<Object>listField(Map<String,Object>m,String key){Object v=m.get(key);return v instanceof List?(List<Object>)v:List.of();}
}
