package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded metadata-only Stage-9 integrity checks; never loads chunks or touches terrain. */
public final class OceanCanvasAssetIntegrityService {
    private OceanCanvasAssetIntegrityService(){}
    public record Issue(String kind,String id,String detail){}
    public record Report(int projects,int tasks,int terrainAssets,int planningObjects,int issues,List<Issue> findings,boolean truncated){public boolean healthy(){return issues==0;}}
    public static Report scan(ServerLevel world,int max){
        var plan=OceanCanvasPlanningData.get(world);var lib=OceanCanvasPlanLibraryData.get(world);var ws=OceanCanvasWorkspaceData.get(world);
        Set<String> objects=new HashSet<>();for(var o:plan.objects())objects.add(o.id());Set<String> projects=new HashSet<>();for(var p:ws.projects())projects.add(p.id());Set<String> tasks=new HashSet<>();for(var t:ws.tasks())tasks.add(t.id());Set<String> assets=new HashSet<>();for(var a:lib.terrainAssets())assets.add(a.id());
        List<Issue> f=new ArrayList<>();int count=0;
        for(var t:ws.tasks()){
            if(!t.projectId().isBlank()&&!projects.contains(t.projectId())){count++;add(f,max,new Issue("TASK_PROJECT",t.id(),"Task links to missing project "+t.projectId()));}
            if(!t.planningObjectId().isBlank()&&!objects.contains(t.planningObjectId())){count++;add(f,max,new Issue("TASK_PLAN",t.id(),"Task links to missing Plan object "+t.planningObjectId()));}
            if(!t.projectId().isBlank()&&!t.planningObjectId().isBlank()){
                var owner=ws.project(t.projectId());
                if(owner!=null&&!owner.planningObjectIds().contains(t.planningObjectId())){count++;add(f,max,new Issue("TASK_PROJECT_PLAN",t.id(),"Task Plan "+t.planningObjectId()+" is not linked by Project "+owner.id()));}
            }
            for(String dep:t.dependencies())if(!tasks.contains(dep)){count++;add(f,max,new Issue("TASK_DEPENDENCY",t.id(),"Missing dependency "+dep));}
        }
        java.util.Map<String,java.util.List<String>> projectsByPlan=new java.util.HashMap<>();
        for(var p:ws.projects())for(String oid:p.planningObjectIds())projectsByPlan.computeIfAbsent(oid,k->new java.util.ArrayList<>()).add(p.id());
        for(var e:projectsByPlan.entrySet())if(e.getValue().size()>1){count++;add(f,max,new Issue("PLAN_PROJECT_AMBIGUOUS",e.getKey(),"Plan object is linked by multiple Projects: "+String.join(", ",e.getValue())));}
        for(var p:ws.projects()){
            if(!p.parentProjectId().isBlank()&&!projects.contains(p.parentProjectId())){count++;add(f,max,new Issue("PROJECT_PARENT",p.id(),"Missing parent project "+p.parentProjectId()));}
            for(String oid:p.planningObjectIds())if(!objects.contains(oid)){count++;add(f,max,new Issue("PROJECT_PLAN",p.id(),"Missing Plan object "+oid));}
            for(String aid:p.terrainAssetIds())if(!assets.contains(aid)){count++;add(f,max,new Issue("PROJECT_TERRAIN",p.id(),"Missing Terrain Asset "+aid));}
        }
        for(var a:lib.terrainAssets()){
            for(String oid:a.planningObjectIds())if(!objects.contains(oid)){count++;add(f,max,new Issue("TERRAIN_PLAN",a.id(),"Missing Plan object "+oid));}
            if(!a.approvedGaeaRevision().isBlank()&&a.revision(a.approvedGaeaRevision())==null){count++;add(f,max,new Issue("GAEA_APPROVAL",a.id(),"Approved Gaea revision is missing"));}
            if(!a.approvedWorldPainterRevision().isBlank()&&a.revision(a.approvedWorldPainterRevision())==null){count++;add(f,max,new Issue("WORLDPAINTER_APPROVAL",a.id(),"Approved WorldPainter revision is missing"));}
            for(var r:a.revisions())if(r.maxX()<=r.minX()||r.maxZ()<=r.minZ()||r.blocksPerPixel()<0){count++;add(f,max,new Issue("REVISION_BOUNDS",a.id(),"Revision "+r.id()+" has invalid registration metadata"));}
        }
        return new Report(projects.size(),tasks.size(),assets.size(),objects.size(),count,List.copyOf(f),count>f.size());
    }
    /**
     * Explicit, bounded repair for references whose target can be proven absent.
     * Never guesses between existing objects and never touches terrain/chunks.
     */
    public static String repairStaleReference(ServerLevel world,String kind,String id){
        if(kind==null||id==null)return "Nothing repaired.";
        String k=kind.trim().toUpperCase(java.util.Locale.ROOT), key=id.trim().toLowerCase(java.util.Locale.ROOT);
        var plan=OceanCanvasPlanningData.get(world);var lib=OceanCanvasPlanLibraryData.get(world);var ws=OceanCanvasWorkspaceData.get(world);long now=System.currentTimeMillis();
        switch(k){
            case "TASK_PROJECT" -> {var t=ws.task(key);if(t!=null&&!t.projectId().isBlank()&&ws.project(t.projectId())==null){ws.putTask(new OceanCanvasWorkspaceData.GeoTask(t.id(),t.title(),t.notes(),t.x(),t.z(),t.regionName(),t.planningObjectId(),t.status(),t.priority(),t.createdAt(),now,"",t.parentTaskId(),t.dependencies(),t.checklist(),t.weight()));return "Cleared stale missing Project link from task '"+t.title()+"'.";}}
            case "TASK_PLAN" -> {var t=ws.task(key);if(t!=null&&!t.planningObjectId().isBlank()&&plan.object(t.planningObjectId())==null){ws.putTask(new OceanCanvasWorkspaceData.GeoTask(t.id(),t.title(),t.notes(),t.x(),t.z(),t.regionName(),"",t.status(),t.priority(),t.createdAt(),now,t.projectId(),t.parentTaskId(),t.dependencies(),t.checklist(),t.weight()));return "Cleared stale missing Plan link from task '"+t.title()+"'.";}}
            case "TASK_DEPENDENCY" -> {var t=ws.task(key);if(t!=null){var deps=new java.util.ArrayList<String>();for(String dep:t.dependencies())if(ws.task(dep)!=null)deps.add(dep);if(deps.size()!=t.dependencies().size()){ws.putTask(new OceanCanvasWorkspaceData.GeoTask(t.id(),t.title(),t.notes(),t.x(),t.z(),t.regionName(),t.planningObjectId(),t.status(),t.priority(),t.createdAt(),now,t.projectId(),t.parentTaskId(),deps,t.checklist(),t.weight()));return "Removed stale missing dependency link(s) from task '"+t.title()+"'.";}}}
            case "PROJECT_PARENT" -> {var pr=ws.project(key);if(pr!=null&&!pr.parentProjectId().isBlank()&&ws.project(pr.parentProjectId())==null){ws.putProject(new OceanCanvasWorkspaceData.WorkProject(pr.id(),pr.name(),"",pr.planningObjectIds(),pr.terrainAssetIds(),pr.regionName(),pr.status(),pr.templateId(),pr.notes(),pr.milestones(),pr.createdAt(),now,pr.phases(),pr.blockers(),pr.currentTaskId(),pr.milestoneRecords()));return "Cleared stale missing parent from Project '"+pr.name()+"'.";}}
            case "PROJECT_PLAN" -> {var pr=ws.project(key);if(pr!=null){var ids=pr.planningObjectIds().stream().filter(v->plan.object(v)!=null).toList();if(ids.size()!=pr.planningObjectIds().size()){ws.putProject(new OceanCanvasWorkspaceData.WorkProject(pr.id(),pr.name(),pr.parentProjectId(),ids,pr.terrainAssetIds(),pr.regionName(),pr.status(),pr.templateId(),pr.notes(),pr.milestones(),pr.createdAt(),now,pr.phases(),pr.blockers(),pr.currentTaskId(),pr.milestoneRecords()));return "Removed stale missing Plan link(s) from Project '"+pr.name()+"'.";}}}
            case "PROJECT_TERRAIN" -> {var pr=ws.project(key);if(pr!=null){var ids=pr.terrainAssetIds().stream().filter(v->lib.terrainAsset(v)!=null).toList();if(ids.size()!=pr.terrainAssetIds().size()){ws.putProject(new OceanCanvasWorkspaceData.WorkProject(pr.id(),pr.name(),pr.parentProjectId(),pr.planningObjectIds(),ids,pr.regionName(),pr.status(),pr.templateId(),pr.notes(),pr.milestones(),pr.createdAt(),now,pr.phases(),pr.blockers(),pr.currentTaskId(),pr.milestoneRecords()));return "Removed stale missing Terrain Asset link(s) from Project '"+pr.name()+"'.";}}}
            case "TERRAIN_PLAN" -> {var a=lib.terrainAsset(key);if(a!=null){var ids=a.planningObjectIds().stream().filter(v->plan.object(v)!=null).toList();if(ids.size()!=a.planningObjectIds().size()){lib.putTerrainAsset(new OceanCanvasPlanLibraryData.TerrainAsset(a.id(),a.name(),ids,a.minX(),a.minZ(),a.maxX(),a.maxZ(),a.seaLevel(),a.status(),a.revisions(),a.approvedGaeaRevision(),a.approvedWorldPainterRevision(),a.placementData(),a.notes()));return "Removed stale missing Plan link(s) from Terrain Asset '"+a.name()+"'.";}}}
            case "GAEA_APPROVAL" -> {var a=lib.terrainAsset(key);if(a!=null&&!a.approvedGaeaRevision().isBlank()&&a.revision(a.approvedGaeaRevision())==null){lib.putTerrainAsset(new OceanCanvasPlanLibraryData.TerrainAsset(a.id(),a.name(),a.planningObjectIds(),a.minX(),a.minZ(),a.maxX(),a.maxZ(),a.seaLevel(),a.status(),a.revisions(),"",a.approvedWorldPainterRevision(),a.placementData(),a.notes()));return "Cleared stale missing approved Gaea revision from Terrain Asset '"+a.name()+"'.";}}
            case "WORLDPAINTER_APPROVAL" -> {var a=lib.terrainAsset(key);if(a!=null&&!a.approvedWorldPainterRevision().isBlank()&&a.revision(a.approvedWorldPainterRevision())==null){lib.putTerrainAsset(new OceanCanvasPlanLibraryData.TerrainAsset(a.id(),a.name(),a.planningObjectIds(),a.minX(),a.minZ(),a.maxX(),a.maxZ(),a.seaLevel(),a.status(),a.revisions(),a.approvedGaeaRevision(),"",a.placementData(),a.notes()));return "Cleared stale missing approved WorldPainter revision from Terrain Asset '"+a.name()+"'.";}}
            default -> {}
        }
        return "Nothing repaired; the finding changed or requires user intent.";
    }

    private static void add(List<Issue> out,int max,Issue i){if(out.size()<Math.max(0,max))out.add(i);}
}
