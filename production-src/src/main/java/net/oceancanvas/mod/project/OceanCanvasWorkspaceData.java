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
import java.util.Set;
import java.util.UUID;

/**
 * Non-terrain execution workspace. Plan describes the finished world; this file describes how the
 * player intends to make that plan real. It is intentionally separate from region/rule persistence.
 */
public final class OceanCanvasWorkspaceData extends SavedData {
    public static final int CURRENT_SCHEMA = 7;
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "workspace_data");

    /** Canonical Stage-8 task states. Old TODO/DONE saves are migrated by parse(). */
    public enum TaskStatus { PLANNED, BLOCKED, READY, IN_PROGRESS, COMPLETE, SKIPPED;
        public static TaskStatus parse(String raw) {
            if (raw == null) return PLANNED;
            String v = raw.trim().toUpperCase(Locale.ROOT);
            if (v.equals("TODO")) return PLANNED;
            if (v.equals("DONE")) return COMPLETE;
            try { return valueOf(v); } catch (IllegalArgumentException ex) { return PLANNED; }
        }
    }

    public enum ProjectStatus { PLANNED, ACTIVE, BLOCKED, COMPLETE, ARCHIVED;
        public static ProjectStatus parse(String raw) {
            if (raw == null) return PLANNED;
            try { return valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { return PLANNED; }
        }
    }

    public record ChecklistItem(String id, String text, boolean complete) {
        public ChecklistItem { id=normalizeId(id,"item"); text=clean(text,"Item"); }
    }

    public record GeoTask(String id, String title, String notes, int x, int z, String regionName,
                          String planningObjectId, String status, int priority, long createdAt, long updatedAt,
                          String projectId, String parentTaskId, List<String> dependencies,
                          List<ChecklistItem> checklist, double weight, String phaseId) {
        /** Compatibility constructor for schema-2 callers and old code. */
        public GeoTask(String id, String title, String notes, int x, int z, String regionName,
                       String planningObjectId, String status, int priority, long createdAt, long updatedAt,
                       String projectId, String parentTaskId, List<String> dependencies,
                       List<ChecklistItem> checklist, double weight) {
            this(id,title,notes,x,z,regionName,planningObjectId,status,priority,createdAt,updatedAt,projectId,parentTaskId,dependencies,checklist,weight,"");
        }
        /** Compatibility constructor for schema-1 callers and old code. */
        public GeoTask(String id, String title, String notes, int x, int z, String regionName,
                       String planningObjectId, String status, int priority, long createdAt, long updatedAt) {
            this(id,title,notes,x,z,regionName,planningObjectId,status,priority,createdAt,updatedAt,"","",List.of(),List.of(),1.0D);
        }
        public GeoTask {
            id = normalizeId(id, "task"); title = clean(title, "Task"); notes = notes == null ? "" : notes;
            regionName = regionName == null ? "" : regionName; planningObjectId = planningObjectId == null ? "" : planningObjectId;
            status = TaskStatus.parse(status).name(); priority = Math.max(0, Math.min(3, priority));
            projectId = projectId == null ? "" : normalizeOptionalId(projectId); parentTaskId = parentTaskId == null ? "" : normalizeOptionalId(parentTaskId);
            dependencies = dependencies == null ? List.of() : dependencies.stream().map(OceanCanvasWorkspaceData::normalizeOptionalId).filter(v->!v.isBlank()).distinct().limit(64).toList();
            checklist = checklist == null ? List.of() : List.copyOf(checklist.stream().limit(128).toList());
            weight = Math.max(0.1D, Math.min(100.0D, weight)); phaseId=phaseId==null?"":normalizeOptionalId(phaseId);
        }
        public TaskStatus parsedStatus() { return TaskStatus.parse(status); }
        public boolean terminal(){ return parsedStatus()==TaskStatus.COMPLETE || parsedStatus()==TaskStatus.SKIPPED; }
    }

    public record ProjectPhase(String id,String name,String status,int order) {
        public ProjectPhase { id=normalizeId(id,"phase"); name=clean(name,"Phase"); status=clean(status,"PLANNED").toUpperCase(Locale.ROOT); order=Math.max(0,Math.min(999,order)); }
        public boolean complete(){return "COMPLETE".equals(status)||"SKIPPED".equals(status);}
    }
    public record ProjectMilestone(String id,String name,String status,String phaseId,String taskId,String notes,int progress,String targetDate) {
        public ProjectMilestone {
            id=normalizeId(id,"milestone"); name=clean(name,"Milestone"); status=clean(status,"PLANNED").toUpperCase(Locale.ROOT);
            if(!Set.of("PLANNED","ACTIVE","COMPLETE","SKIPPED").contains(status))status="PLANNED";
            phaseId=phaseId==null?"":normalizeOptionalId(phaseId); taskId=taskId==null?"":normalizeOptionalId(taskId); notes=notes==null?"":notes.trim(); progress=Math.max(0,Math.min(100,progress)); targetDate=targetDate==null?"":targetDate.trim();
            if(!targetDate.isBlank())try{java.time.LocalDate.parse(targetDate);}catch(java.time.format.DateTimeParseException ex){targetDate="";}
        }
        public boolean complete(){return "COMPLETE".equals(status)||"SKIPPED".equals(status);}
    }

    public record ProjectBlocker(String id,String text,boolean resolved,String scopeType,String scopeId) {
        public ProjectBlocker(String id,String text,boolean resolved){this(id,text,resolved,"PROJECT","");}
        public ProjectBlocker {
            id=normalizeId(id,"blocker"); text=clean(text,"Blocker");
            scopeType=clean(scopeType,"PROJECT").toUpperCase(Locale.ROOT);
            if(!Set.of("PROJECT","TASK","ASSET","PLAN").contains(scopeType))scopeType="PROJECT";
            scopeId=scopeId==null?"":normalizeOptionalId(scopeId);
            if("PROJECT".equals(scopeType))scopeId="";
        }
    }

    /** A persistent execution project linked to one or more Plan objects/Terrain Assets. */
    public record WorkProject(String id, String name, String parentProjectId, List<String> planningObjectIds,
                              List<String> terrainAssetIds, String regionName, String status, String templateId,
                              String notes, List<String> milestones, long createdAt, long updatedAt,
                              List<ProjectPhase> phases,List<ProjectBlocker> blockers,String currentTaskId,List<ProjectMilestone> milestoneRecords) {
        public WorkProject(String id,String name,String parentProjectId,List<String> planningObjectIds,List<String> terrainAssetIds,String regionName,String status,String templateId,String notes,List<String> milestones,long createdAt,long updatedAt){
            this(id,name,parentProjectId,planningObjectIds,terrainAssetIds,regionName,status,templateId,notes,milestones,createdAt,updatedAt,List.of(),List.of(),"",List.of());
        }
        public WorkProject(String id,String name,String parentProjectId,List<String> planningObjectIds,List<String> terrainAssetIds,String regionName,String status,String templateId,String notes,List<String> milestones,long createdAt,long updatedAt,List<ProjectPhase> phases,List<ProjectBlocker> blockers,String currentTaskId){
            this(id,name,parentProjectId,planningObjectIds,terrainAssetIds,regionName,status,templateId,notes,milestones,createdAt,updatedAt,phases,blockers,currentTaskId,List.of());
        }
        public WorkProject {
            id=normalizeId(id,"project"); name=clean(name,"Project"); parentProjectId=parentProjectId==null?"":normalizeOptionalId(parentProjectId);
            planningObjectIds=cleanIds(planningObjectIds,64); terrainAssetIds=cleanIds(terrainAssetIds,64);
            regionName=regionName==null?"":regionName; status=ProjectStatus.parse(status).name(); templateId=templateId==null?"":normalizeOptionalId(templateId);
            notes=notes==null?"":notes; milestones=milestones==null?List.of():List.copyOf(milestones.stream().map(String::trim).filter(v->!v.isBlank()).limit(128).toList());
            phases=phases==null?List.of():List.copyOf(phases.stream().limit(64).toList()); blockers=blockers==null?List.of():List.copyOf(blockers.stream().limit(64).toList()); currentTaskId=currentTaskId==null?"":normalizeOptionalId(currentTaskId);
            milestoneRecords=milestoneRecords==null?List.of():List.copyOf(milestoneRecords.stream().limit(128).toList());
        }
        public ProjectStatus parsedStatus(){ return ProjectStatus.parse(status); }
    }


    /** Tiny durable resume list shown at the start of the next play session (OC-F098). */
    public record NextSessionItem(String id,String kind,String taskId,String projectId,String label,int x,int z,String regionName,long createdAt) {
        public NextSessionItem {
            id=normalizeId(id,"next"); kind=clean(kind,"LOCATION").toUpperCase(Locale.ROOT);
            if(!Set.of("TASK","LOCATION").contains(kind))kind="LOCATION";
            taskId=taskId==null?"":normalizeOptionalId(taskId); projectId=projectId==null?"":normalizeOptionalId(projectId);
            label=clean(label,kind.equals("TASK")?"Task":"Location"); regionName=regionName==null?"":regionName;
        }
    }

    public record JournalEntry(String id, long epochMillis, String text, String regionName, int x, int z) {
        public JournalEntry { id=normalizeId(id,"journal"); text=text==null?"":text; regionName=regionName==null?"":regionName; }
    }
    public record Viewpoint(String id, String name, double x, double y, double z, float yaw, float pitch,
                            String regionName, String screenshotAssetId) {
        public Viewpoint { id=normalizeId(id,"view"); name=clean(name,"Viewpoint"); regionName=regionName==null?"":regionName; screenshotAssetId=screenshotAssetId==null?"":screenshotAssetId; }
    }
    public record AtlasFeature(String id, String type, String name, String planningObjectId, String regionName,
                               int x, int z, String notes, String color) {
        /** One of {@link #VALID_WAYPOINT_COLORS}, or blank for the default map colour. */
        public AtlasFeature { id=normalizeId(id,"feature"); type=clean(type,"LANDMARK").toUpperCase(Locale.ROOT); name=clean(name,"Feature"); planningObjectId=planningObjectId==null?"":planningObjectId; regionName=regionName==null?"":regionName; notes=notes==null?"":notes; color=normalizeWaypointColor(color); }
    }
    /** Same eight-name palette as {@code OceanCanvasPlayerZones#VALID_REGION_COLORS}, kept separate
     *  so a change to one vocabulary cannot silently reach into the other's saved data. */
    public static final Set<String> VALID_WAYPOINT_COLORS = Set.of(
            "RED", "ORANGE", "YELLOW", "LIME", "CYAN", "BLUE", "PURPLE", "PINK");
    public static String normalizeWaypointColor(String raw){
        if(raw==null||raw.isBlank()) return "";
        String v=raw.trim().toUpperCase(Locale.ROOT);
        return VALID_WAYPOINT_COLORS.contains(v)?v:"";
    }
    public record DesignScenario(String id, String name, boolean visible, String notes) {
        public DesignScenario { id=normalizeId(id,"scenario"); name=clean(name,"Scenario"); notes=notes==null?"":notes; }
    }

    private static final Codec<ChecklistItem> CHECK_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(ChecklistItem::id), Codec.STRING.fieldOf("text").forGetter(ChecklistItem::text),
            Codec.BOOL.optionalFieldOf("complete",false).forGetter(ChecklistItem::complete)).apply(i,ChecklistItem::new));
    private static final Codec<GeoTask> TASK_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(GeoTask::id), Codec.STRING.fieldOf("title").forGetter(GeoTask::title),
            Codec.STRING.optionalFieldOf("notes","").forGetter(GeoTask::notes), Codec.INT.fieldOf("x").forGetter(GeoTask::x),
            Codec.INT.fieldOf("z").forGetter(GeoTask::z), Codec.STRING.optionalFieldOf("regionName","").forGetter(GeoTask::regionName),
            Codec.STRING.optionalFieldOf("planningObjectId","").forGetter(GeoTask::planningObjectId), Codec.STRING.optionalFieldOf("status","PLANNED").forGetter(GeoTask::status),
            Codec.INT.optionalFieldOf("priority",1).forGetter(GeoTask::priority), Codec.LONG.optionalFieldOf("createdAt",0L).forGetter(GeoTask::createdAt),
            Codec.STRING.optionalFieldOf("projectId","").forGetter(GeoTask::projectId), Codec.STRING.optionalFieldOf("parentTaskId","").forGetter(GeoTask::parentTaskId),
            Codec.STRING.listOf().optionalFieldOf("dependencies",List.of()).forGetter(GeoTask::dependencies), CHECK_CODEC.listOf().optionalFieldOf("checklist",List.of()).forGetter(GeoTask::checklist),
            Codec.DOUBLE.optionalFieldOf("weight",1.0D).forGetter(GeoTask::weight), Codec.STRING.optionalFieldOf("phaseId","").forGetter(GeoTask::phaseId)
    ).apply(i, (id,title,notes,x,z,regionName,planningObjectId,status,priority,createdAt,projectId,parentTaskId,dependencies,checklist,weight,phaseId) ->
            new GeoTask(id,title,notes,x,z,regionName,planningObjectId,status,priority,createdAt,0L,projectId,parentTaskId,dependencies,checklist,weight,phaseId)));
    private static final Codec<ProjectPhase> PHASE_CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("id").forGetter(ProjectPhase::id),Codec.STRING.fieldOf("name").forGetter(ProjectPhase::name),Codec.STRING.optionalFieldOf("status","PLANNED").forGetter(ProjectPhase::status),Codec.INT.optionalFieldOf("order",0).forGetter(ProjectPhase::order)).apply(i,ProjectPhase::new));
    private static final Codec<ProjectMilestone> MILESTONE_CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("id").forGetter(ProjectMilestone::id),Codec.STRING.fieldOf("name").forGetter(ProjectMilestone::name),Codec.STRING.optionalFieldOf("status","PLANNED").forGetter(ProjectMilestone::status),Codec.STRING.optionalFieldOf("phaseId","").forGetter(ProjectMilestone::phaseId),Codec.STRING.optionalFieldOf("taskId","").forGetter(ProjectMilestone::taskId),Codec.STRING.optionalFieldOf("notes","").forGetter(ProjectMilestone::notes),Codec.INT.optionalFieldOf("progress",0).forGetter(ProjectMilestone::progress),Codec.STRING.optionalFieldOf("targetDate","").forGetter(ProjectMilestone::targetDate)).apply(i,ProjectMilestone::new));
    private static final Codec<ProjectBlocker> BLOCKER_CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("id").forGetter(ProjectBlocker::id),Codec.STRING.fieldOf("text").forGetter(ProjectBlocker::text),Codec.BOOL.optionalFieldOf("resolved",false).forGetter(ProjectBlocker::resolved),Codec.STRING.optionalFieldOf("scopeType","PROJECT").forGetter(ProjectBlocker::scopeType),Codec.STRING.optionalFieldOf("scopeId","").forGetter(ProjectBlocker::scopeId)).apply(i,ProjectBlocker::new));
    private static final Codec<WorkProject> PROJECT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(WorkProject::id), Codec.STRING.fieldOf("name").forGetter(WorkProject::name),
            Codec.STRING.optionalFieldOf("parentProjectId","").forGetter(WorkProject::parentProjectId), Codec.STRING.listOf().optionalFieldOf("planningObjectIds",List.of()).forGetter(WorkProject::planningObjectIds),
            Codec.STRING.listOf().optionalFieldOf("terrainAssetIds",List.of()).forGetter(WorkProject::terrainAssetIds), Codec.STRING.optionalFieldOf("regionName","").forGetter(WorkProject::regionName),
            Codec.STRING.optionalFieldOf("status","PLANNED").forGetter(WorkProject::status), Codec.STRING.optionalFieldOf("templateId","").forGetter(WorkProject::templateId),
            Codec.STRING.optionalFieldOf("notes","").forGetter(WorkProject::notes), Codec.STRING.listOf().optionalFieldOf("milestones",List.of()).forGetter(WorkProject::milestones),
            Codec.LONG.optionalFieldOf("createdAt",0L).forGetter(WorkProject::createdAt), Codec.LONG.optionalFieldOf("updatedAt",0L).forGetter(WorkProject::updatedAt),
            PHASE_CODEC.listOf().optionalFieldOf("phases",List.of()).forGetter(WorkProject::phases),BLOCKER_CODEC.listOf().optionalFieldOf("blockers",List.of()).forGetter(WorkProject::blockers),Codec.STRING.optionalFieldOf("currentTaskId","").forGetter(WorkProject::currentTaskId),
            MILESTONE_CODEC.listOf().optionalFieldOf("milestoneRecords",List.of()).forGetter(WorkProject::milestoneRecords)
    ).apply(i,WorkProject::new));
    private static final Codec<NextSessionItem> NEXT_SESSION_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(NextSessionItem::id), Codec.STRING.fieldOf("kind").forGetter(NextSessionItem::kind),
            Codec.STRING.optionalFieldOf("taskId","").forGetter(NextSessionItem::taskId), Codec.STRING.optionalFieldOf("projectId","").forGetter(NextSessionItem::projectId),
            Codec.STRING.fieldOf("label").forGetter(NextSessionItem::label), Codec.INT.fieldOf("x").forGetter(NextSessionItem::x), Codec.INT.fieldOf("z").forGetter(NextSessionItem::z),
            Codec.STRING.optionalFieldOf("regionName","").forGetter(NextSessionItem::regionName), Codec.LONG.optionalFieldOf("createdAt",0L).forGetter(NextSessionItem::createdAt)
    ).apply(i,NextSessionItem::new));
    private static final Codec<JournalEntry> JOURNAL_CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("id").forGetter(JournalEntry::id),Codec.LONG.fieldOf("epochMillis").forGetter(JournalEntry::epochMillis),Codec.STRING.fieldOf("text").forGetter(JournalEntry::text),Codec.STRING.optionalFieldOf("regionName","").forGetter(JournalEntry::regionName),Codec.INT.optionalFieldOf("x",0).forGetter(JournalEntry::x),Codec.INT.optionalFieldOf("z",0).forGetter(JournalEntry::z)).apply(i,JournalEntry::new));
    private static final Codec<Viewpoint> VIEW_CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("id").forGetter(Viewpoint::id),Codec.STRING.fieldOf("name").forGetter(Viewpoint::name),Codec.DOUBLE.fieldOf("x").forGetter(Viewpoint::x),Codec.DOUBLE.fieldOf("y").forGetter(Viewpoint::y),Codec.DOUBLE.fieldOf("z").forGetter(Viewpoint::z),Codec.FLOAT.optionalFieldOf("yaw",0F).forGetter(Viewpoint::yaw),Codec.FLOAT.optionalFieldOf("pitch",0F).forGetter(Viewpoint::pitch),Codec.STRING.optionalFieldOf("regionName","").forGetter(Viewpoint::regionName),Codec.STRING.optionalFieldOf("screenshotAssetId","").forGetter(Viewpoint::screenshotAssetId)).apply(i,Viewpoint::new));
    private static final Codec<AtlasFeature> FEATURE_CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("id").forGetter(AtlasFeature::id),Codec.STRING.fieldOf("type").forGetter(AtlasFeature::type),Codec.STRING.fieldOf("name").forGetter(AtlasFeature::name),Codec.STRING.optionalFieldOf("planningObjectId","").forGetter(AtlasFeature::planningObjectId),Codec.STRING.optionalFieldOf("regionName","").forGetter(AtlasFeature::regionName),Codec.INT.fieldOf("x").forGetter(AtlasFeature::x),Codec.INT.fieldOf("z").forGetter(AtlasFeature::z),Codec.STRING.optionalFieldOf("notes","").forGetter(AtlasFeature::notes),Codec.STRING.optionalFieldOf("color","").forGetter(AtlasFeature::color)).apply(i,AtlasFeature::new));
    private static final Codec<DesignScenario> SCENARIO_CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.fieldOf("id").forGetter(DesignScenario::id),Codec.STRING.fieldOf("name").forGetter(DesignScenario::name),Codec.BOOL.optionalFieldOf("visible",true).forGetter(DesignScenario::visible),Codec.STRING.optionalFieldOf("notes","").forGetter(DesignScenario::notes)).apply(i,DesignScenario::new));

    private static final Codec<OceanCanvasWorkspaceData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",CURRENT_SCHEMA).forGetter(d->d.schema), TASK_CODEC.listOf().optionalFieldOf("tasks",List.of()).forGetter(d->new ArrayList<>(d.tasks.values())),
            PROJECT_CODEC.listOf().optionalFieldOf("projects",List.of()).forGetter(d->new ArrayList<>(d.projects.values())), JOURNAL_CODEC.listOf().optionalFieldOf("journal",List.of()).forGetter(d->d.journal),
            VIEW_CODEC.listOf().optionalFieldOf("viewpoints",List.of()).forGetter(d->new ArrayList<>(d.viewpoints.values())), FEATURE_CODEC.listOf().optionalFieldOf("atlasFeatures",List.of()).forGetter(d->new ArrayList<>(d.atlasFeatures.values())),
            SCENARIO_CODEC.listOf().optionalFieldOf("scenarios",List.of()).forGetter(d->new ArrayList<>(d.scenarios.values())), Codec.STRING.optionalFieldOf("activeScenario","").forGetter(d->d.activeScenario),
            Codec.STRING.optionalFieldOf("sessionNote","").forGetter(d->d.sessionNote), Codec.STRING.optionalFieldOf("activeWorkProject","").forGetter(d->d.activeWorkProject),
            NEXT_SESSION_CODEC.listOf().optionalFieldOf("nextSession",List.of()).forGetter(d->d.nextSession), Codec.STRING.optionalFieldOf("worldMapMode","ALL").forGetter(d->d.worldMapMode)
    ).apply(i,OceanCanvasWorkspaceData::new));
    public static final SavedDataType<OceanCanvasWorkspaceData> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasWorkspaceData::new,CODEC,null);

    private int schema; private final Map<String,GeoTask> tasks=new LinkedHashMap<>(); private final Map<String,WorkProject> projects=new LinkedHashMap<>();
    private final List<JournalEntry> journal=new ArrayList<>(); private final Map<String,Viewpoint> viewpoints=new LinkedHashMap<>(); private final Map<String,AtlasFeature> atlasFeatures=new LinkedHashMap<>();
    private final Map<String,DesignScenario> scenarios=new LinkedHashMap<>(); private String activeScenario=""; private String sessionNote=""; private String activeWorkProject="";
    private final List<NextSessionItem> nextSession=new ArrayList<>(); private String worldMapMode="ALL";

    public OceanCanvasWorkspaceData(){this(CURRENT_SCHEMA,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"","","",List.of(),"ALL");}
    private OceanCanvasWorkspaceData(int schema,List<GeoTask> tasks,List<WorkProject> projects,List<JournalEntry> journal,List<Viewpoint> viewpoints,List<AtlasFeature> features,List<DesignScenario> scenarios,String activeScenario,String sessionNote,String activeWorkProject,List<NextSessionItem> nextSession,String worldMapMode){
        this.schema=Math.max(1,schema); tasks.forEach(v->this.tasks.put(v.id(),v)); projects.forEach(v->this.projects.put(v.id(),v)); this.journal.addAll(journal); viewpoints.forEach(v->this.viewpoints.put(v.id(),v)); features.forEach(v->this.atlasFeatures.put(v.id(),v)); scenarios.forEach(v->this.scenarios.put(v.id(),v)); this.activeScenario=activeScenario==null?"":activeScenario; this.sessionNote=sessionNote==null?"":sessionNote; this.activeWorkProject=activeWorkProject==null?"":activeWorkProject;
        if(nextSession!=null)this.nextSession.addAll(nextSession.stream().limit(8).toList()); this.worldMapMode=normalizeMapMode(worldMapMode);
        migrateLegacyMilestones();
        migrateReadiness();
        this.schema=CURRENT_SCHEMA;
    }
    public static OceanCanvasWorkspaceData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);} public int schema(){return schema;}
    public List<GeoTask> tasks(){return List.copyOf(tasks.values());} public List<WorkProject> projects(){return List.copyOf(projects.values());} public List<JournalEntry> journal(){return List.copyOf(journal);}
    public List<Viewpoint> viewpoints(){return List.copyOf(viewpoints.values());} public List<AtlasFeature> atlasFeatures(){return List.copyOf(atlasFeatures.values());} public List<DesignScenario> scenarios(){return List.copyOf(scenarios.values());}
    public String activeScenario(){return activeScenario;} public String sessionNote(){return sessionNote;} public String activeWorkProject(){return activeWorkProject;}
    public List<NextSessionItem> nextSession(){return List.copyOf(nextSession);} public String worldMapMode(){return worldMapMode;}
    public GeoTask task(String id){return tasks.get(normalizeOptionalId(id));} public WorkProject project(String id){return projects.get(normalizeOptionalId(id));} public DesignScenario scenario(String id){return scenarios.get(normalizeOptionalId(id));}
    public String newId(String prefix){return normalizeId(prefix+"_"+UUID.randomUUID().toString().substring(0,8),prefix);}
    public void putTask(GeoTask task){tasks.put(task.id(),task); recomputeReadyStates(); setDirty();} public boolean removeTask(String id){boolean c=tasks.remove(normalizeOptionalId(id))!=null;if(c){recomputeReadyStates();setDirty();}return c;}
    public void putProject(WorkProject project){projects.put(project.id(),project);setDirty();} public boolean removeProject(String id){
        String key=normalizeOptionalId(id); WorkProject removed=projects.remove(key); if(removed==null)return false; long now=System.currentTimeMillis();
        if(activeWorkProject.equals(key))activeWorkProject="";
        for(var e:new ArrayList<>(tasks.entrySet())){GeoTask t=e.getValue();if(t.projectId().equals(key))tasks.put(e.getKey(),new GeoTask(t.id(),t.title(),t.notes(),t.x(),t.z(),t.regionName(),t.planningObjectId(),t.status(),t.priority(),t.createdAt(),now,"",t.parentTaskId(),t.dependencies(),t.checklist(),t.weight(),t.phaseId()));}
        for(var e:new ArrayList<>(projects.entrySet())){WorkProject v=e.getValue();if(v.parentProjectId().equals(key))projects.put(e.getKey(),new WorkProject(v.id(),v.name(),"",v.planningObjectIds(),v.terrainAssetIds(),v.regionName(),v.status(),v.templateId(),v.notes(),v.milestones(),v.createdAt(),now,v.phases(),v.blockers(),v.currentTaskId(),v.milestoneRecords()));}
        setDirty(); return true;
    }
    public void setActiveWorkProject(String id){String v=normalizeOptionalId(id);activeWorkProject=projects.containsKey(v)?v:"";setDirty();}
    public void addJournal(String text,String region,int x,int z){long now=System.currentTimeMillis();journal.add(new JournalEntry(newId("journal"),now,text,region,x,z));if(journal.size()>5000)journal.remove(0);setDirty();}
    public void putViewpoint(Viewpoint v){viewpoints.put(v.id(),v);setDirty();} public void putAtlasFeature(AtlasFeature v){atlasFeatures.put(v.id(),v);setDirty();} public void putScenario(DesignScenario v){scenarios.put(v.id(),v);setDirty();}
    public AtlasFeature atlasFeature(String id){return atlasFeatures.get(normalizeOptionalId(id));}
    public boolean removeAtlasFeature(String id){boolean c=atlasFeatures.remove(normalizeOptionalId(id))!=null;if(c)setDirty();return c;}
    public AtlasFeature renameAtlasFeature(String id,String name){AtlasFeature v=atlasFeature(id);if(v==null)return null;AtlasFeature next=new AtlasFeature(v.id(),v.type(),name,v.planningObjectId(),v.regionName(),v.x(),v.z(),v.notes(),v.color());atlasFeatures.put(v.id(),next);setDirty();return next;}
    public AtlasFeature moveAtlasFeature(String id,int x,int z){AtlasFeature v=atlasFeature(id);if(v==null)return null;AtlasFeature next=new AtlasFeature(v.id(),v.type(),v.name(),v.planningObjectId(),v.regionName(),x,z,v.notes(),v.color());atlasFeatures.put(v.id(),next);setDirty();return next;}
    public AtlasFeature recolorAtlasFeature(String id,String color){AtlasFeature v=atlasFeature(id);if(v==null)return null;AtlasFeature next=new AtlasFeature(v.id(),v.type(),v.name(),v.planningObjectId(),v.regionName(),v.x(),v.z(),v.notes(),color);atlasFeatures.put(v.id(),next);setDirty();return next;}
    public void setActiveScenario(String id){activeScenario=id==null?"":normalizeOptionalId(id);setDirty();} public void setSessionNote(String value){sessionNote=value==null?"":value;setDirty();}
    public void setWorldMapMode(String mode){String next=normalizeMapMode(mode);if(!worldMapMode.equals(next)){worldMapMode=next;setDirty();}}
    public NextSessionItem pinTask(String taskId){GeoTask t=task(taskId);if(t==null)throw new IllegalArgumentException("unknown task");removeNextSessionForTask(t.id());NextSessionItem item=new NextSessionItem(newId("next"),"TASK",t.id(),t.projectId(),t.title(),t.x(),t.z(),t.regionName(),System.currentTimeMillis());nextSession.add(item);trimNextSession();setDirty();return item;}
    public NextSessionItem pinLocation(String label,int x,int z,String regionName){NextSessionItem item=new NextSessionItem(newId("next"),"LOCATION","","",label,x,z,regionName,System.currentTimeMillis());nextSession.add(item);trimNextSession();setDirty();return item;}
    public boolean removeNextSession(String id){boolean changed=nextSession.removeIf(v->v.id().equals(normalizeOptionalId(id)));if(changed)setDirty();return changed;}
    public void clearNextSession(){if(nextSession.isEmpty())return;nextSession.clear();setDirty();}
    private void removeNextSessionForTask(String taskId){String id=normalizeOptionalId(taskId);nextSession.removeIf(v->v.kind().equals("TASK")&&v.taskId().equals(id));}
    private void trimNextSession(){while(nextSession.size()>8)nextSession.remove(0);}
    private static String normalizeMapMode(String raw){String v=raw==null?"ALL":raw.trim().toUpperCase(Locale.ROOT);return Set.of("ALL","UNFINISHED","PLANNED","PARTIAL","ABANDONED","RESTORED","COMPLETE").contains(v)?v:"ALL";}

    /** Keeps every non-terrain workspace reference attached when a named Region is renamed. */
    public void renameRegionReference(String oldName,String newName){
        String oldValue=oldName==null?"":oldName.trim(), next=newName==null?"":newName.trim(); boolean changed=false; long now=System.currentTimeMillis();
        for(var e:new ArrayList<>(tasks.entrySet())){GeoTask t=e.getValue();if(t.regionName().equalsIgnoreCase(oldValue)){tasks.put(e.getKey(),new GeoTask(t.id(),t.title(),t.notes(),t.x(),t.z(),next,t.planningObjectId(),t.status(),t.priority(),t.createdAt(),now,t.projectId(),t.parentTaskId(),t.dependencies(),t.checklist(),t.weight(),t.phaseId()));changed=true;}}
        for(var e:new ArrayList<>(projects.entrySet())){WorkProject v=e.getValue();if(v.regionName().equalsIgnoreCase(oldValue)){projects.put(e.getKey(),new WorkProject(v.id(),v.name(),v.parentProjectId(),v.planningObjectIds(),v.terrainAssetIds(),next,v.status(),v.templateId(),v.notes(),v.milestones(),v.createdAt(),now,v.phases(),v.blockers(),v.currentTaskId(),v.milestoneRecords()));changed=true;}}
        for(int i=0;i<journal.size();i++){JournalEntry v=journal.get(i);if(v.regionName().equalsIgnoreCase(oldValue)){journal.set(i,new JournalEntry(v.id(),v.epochMillis(),v.text(),next,v.x(),v.z()));changed=true;}}
        for(var e:new ArrayList<>(viewpoints.entrySet())){Viewpoint v=e.getValue();if(v.regionName().equalsIgnoreCase(oldValue)){viewpoints.put(e.getKey(),new Viewpoint(v.id(),v.name(),v.x(),v.y(),v.z(),v.yaw(),v.pitch(),next,v.screenshotAssetId()));changed=true;}}
        for(var e:new ArrayList<>(atlasFeatures.entrySet())){AtlasFeature v=e.getValue();if(v.regionName().equalsIgnoreCase(oldValue)){atlasFeatures.put(e.getKey(),new AtlasFeature(v.id(),v.type(),v.name(),v.planningObjectId(),next,v.x(),v.z(),v.notes(),v.color()));changed=true;}}
        if(changed)setDirty();
    }

    /** Region deletion never deletes planning/history records; it only clears their now-invalid Region link. */
    public void removeRegionReference(String name){renameRegionReference(name,"");}

    /** Recomputes only PLANNED/READY states; explicit BLOCKED/IN_PROGRESS/terminal choices are respected. */
    public void recomputeReadyStates(){
        boolean changed=false; Map<String,GeoTask> copy=new LinkedHashMap<>(tasks);
        for(GeoTask t:copy.values()){
            TaskStatus s=t.parsedStatus(); if(s!=TaskStatus.PLANNED&&s!=TaskStatus.READY)continue;
            boolean ready=true; for(String dep:t.dependencies()){GeoTask d=copy.get(dep);if(d==null||!d.terminal()){ready=false;break;}}
            TaskStatus next=ready?TaskStatus.READY:TaskStatus.PLANNED; if(next!=s){tasks.put(t.id(),copyTask(t,next.name(),t.checklist()));changed=true;}
        } if(changed)setDirty();
    }
    public double projectProgress(String projectId){
        List<GeoTask> ts=tasks.values().stream().filter(t->t.projectId().equals(normalizeOptionalId(projectId))).toList(); if(ts.isEmpty())return 0D;
        double total=0,done=0;for(GeoTask t:ts){total+=t.weight();if(t.terminal())done+=t.weight();}return total<=0?0:done/total;
    }
    private void migrateLegacyMilestones(){
        long now=System.currentTimeMillis();
        for(var e:new ArrayList<>(projects.entrySet())){WorkProject p=e.getValue();if(!p.milestoneRecords().isEmpty()||p.milestones().isEmpty())continue;var ms=new ArrayList<ProjectMilestone>();int n=1;for(String name:p.milestones())ms.add(new ProjectMilestone("milestone_legacy_"+(n++),name,"PLANNED","","","Migrated from legacy milestone text.",0,""));projects.put(e.getKey(),new WorkProject(p.id(),p.name(),p.parentProjectId(),p.planningObjectIds(),p.terrainAssetIds(),p.regionName(),p.status(),p.templateId(),p.notes(),p.milestones(),p.createdAt(),now,p.phases(),p.blockers(),p.currentTaskId(),ms));}
    }
    private void migrateReadiness(){recomputeReadyStates();}
    public static GeoTask copyTask(GeoTask t,String status,List<ChecklistItem> checklist){return new GeoTask(t.id(),t.title(),t.notes(),t.x(),t.z(),t.regionName(),t.planningObjectId(),status,t.priority(),t.createdAt(),System.currentTimeMillis(),t.projectId(),t.parentTaskId(),t.dependencies(),checklist,t.weight(),t.phaseId());}
    public static WorkProject copyProject(WorkProject p,List<ProjectPhase> phases,List<ProjectBlocker> blockers,String currentTaskId){return new WorkProject(p.id(),p.name(),p.parentProjectId(),p.planningObjectIds(),p.terrainAssetIds(),p.regionName(),p.status(),p.templateId(),p.notes(),p.milestones(),p.createdAt(),System.currentTimeMillis(),phases,blockers,currentTaskId,p.milestoneRecords());}
    public static WorkProject copyProjectMilestones(WorkProject p,List<ProjectMilestone> milestones){return new WorkProject(p.id(),p.name(),p.parentProjectId(),p.planningObjectIds(),p.terrainAssetIds(),p.regionName(),p.status(),p.templateId(),p.notes(),p.milestones(),p.createdAt(),System.currentTimeMillis(),p.phases(),p.blockers(),p.currentTaskId(),milestones);}
    private static List<String> cleanIds(List<String> values,int max){if(values==null)return List.of();return values.stream().map(OceanCanvasWorkspaceData::normalizeOptionalId).filter(v->!v.isBlank()).distinct().limit(max).toList();}
    private static String clean(String raw,String fallback){return raw==null||raw.isBlank()?fallback:raw.trim();}
    private static String normalizeOptionalId(String raw){return raw==null?"":raw.trim().toLowerCase(Locale.ROOT).replace(' ','_');}
    private static String normalizeId(String raw,String fallback){String v=normalizeOptionalId(raw);return v.isBlank()?fallback:v;}
}
