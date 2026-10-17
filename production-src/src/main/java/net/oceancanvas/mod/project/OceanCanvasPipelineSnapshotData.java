package net.oceancanvas.mod.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Bounded project-pipeline checkpoints. Counts/lineage only; never a terrain backup. */
public final class OceanCanvasPipelineSnapshotData extends SavedData {
    private static final int MAX=64;private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"pipeline_snapshots");
    public record Entry(String id,String projectId,long epochMillis,String label,int plans,int implemented,int assets,int revisions,int gaea,int worldPainter,int placement,int tasks,int complete,int ready,int phasesComplete,int milestonesComplete,int blockers){}
    private final List<String> packed;
    private static final Codec<OceanCanvasPipelineSnapshotData> CODEC=RecordCodecBuilder.create(i->i.group(Codec.STRING.listOf().optionalFieldOf("entries",List.of()).forGetter(d->d.packed)).apply(i,OceanCanvasPipelineSnapshotData::new));
    public static final SavedDataType<OceanCanvasPipelineSnapshotData> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasPipelineSnapshotData::new,CODEC,null);
    public OceanCanvasPipelineSnapshotData(){this(List.of());}private OceanCanvasPipelineSnapshotData(List<String> values){packed=new ArrayList<>();if(values!=null)packed.addAll(values.subList(Math.max(0,values.size()-MAX),values.size()));}
    public static OceanCanvasPipelineSnapshotData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}
    private static String b64(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}private static String unb64(String s){return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}
    private static String encode(Entry e){return String.join("\t",e.id(),e.projectId(),Long.toString(e.epochMillis()),b64(e.label()),Integer.toString(e.plans()),Integer.toString(e.implemented()),Integer.toString(e.assets()),Integer.toString(e.revisions()),Integer.toString(e.gaea()),Integer.toString(e.worldPainter()),Integer.toString(e.placement()),Integer.toString(e.tasks()),Integer.toString(e.complete()),Integer.toString(e.ready()),Integer.toString(e.phasesComplete()),Integer.toString(e.milestonesComplete()),Integer.toString(e.blockers()));}
    private static Entry decode(String raw){try{String[] f=raw.split("\t",-1);if(f.length!=17)return null;return new Entry(f[0],f[1],Long.parseLong(f[2]),unb64(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),Integer.parseInt(f[10]),Integer.parseInt(f[11]),Integer.parseInt(f[12]),Integer.parseInt(f[13]),Integer.parseInt(f[14]),Integer.parseInt(f[15]),Integer.parseInt(f[16]));}catch(RuntimeException e){return null;}}
    public Entry capture(ServerLevel world,String projectId,String label){var workspace=OceanCanvasWorkspaceData.get(world);var project=workspace.project(projectId);if(project==null)throw new IllegalArgumentException("unknown project");var plans=OceanCanvasPlanningData.get(world);var library=OceanCanvasPlanLibraryData.get(world);int planCount=0,implemented=0,assets=0,revisions=0,gaea=0,wp=0,placement=0,tasks=0,complete=0,ready=0,phaseDone=0,milestoneDone=0,blockers=0;
        for(String id:project.planningObjectIds()){var p=plans.object(id);if(p!=null){planCount++;if(p.implemented())implemented++;}}
        for(String id:project.terrainAssetIds()){var a=library.terrainAsset(id);if(a!=null){assets++;revisions+=a.revisions().size();if(!a.approvedGaeaRevision().isBlank())gaea++;if(!a.approvedWorldPainterRevision().isBlank())wp++;if(!a.placementData().isBlank())placement++;}}
        for(var t:workspace.tasks())if(project.id().equals(t.projectId())){tasks++;if(t.terminal())complete++;if(t.parsedStatus()==OceanCanvasWorkspaceData.TaskStatus.READY)ready++;}
        for(var p:project.phases())if(p.complete())phaseDone++;for(var m:project.milestoneRecords())if(m.complete())milestoneDone++;for(var b:project.blockers())if(!b.resolved())blockers++;
        long now=System.currentTimeMillis();Entry e=new Entry(Long.toUnsignedString(now,36)+"-"+Integer.toUnsignedString(packed.size(),36),project.id(),now,label==null||label.isBlank()?"Pipeline checkpoint":label.trim(),planCount,implemented,assets,revisions,gaea,wp,placement,tasks,complete,ready,phaseDone,milestoneDone,blockers);packed.add(encode(e));while(packed.size()>MAX)packed.remove(0);setDirty();return e;}
    public List<Entry> recent(String projectId){List<Entry> out=new ArrayList<>();for(int i=packed.size()-1;i>=0;i--){Entry e=decode(packed.get(i));if(e!=null&&e.projectId().equals(projectId))out.add(e);}return List.copyOf(out);}
    /** Imports a portable summary under its destination Project identity; no world or workflow state is replayed. */
    public Entry importSummary(Entry source,String destinationProjectId){if(source==null)throw new IllegalArgumentException("missing pipeline checkpoint");long nonce=System.currentTimeMillis();Entry e=new Entry(Long.toUnsignedString(nonce,36)+"-import-"+Integer.toUnsignedString(packed.size(),36),destinationProjectId,source.epochMillis(),source.label(),source.plans(),source.implemented(),source.assets(),source.revisions(),source.gaea(),source.worldPainter(),source.placement(),source.tasks(),source.complete(),source.ready(),source.phasesComplete(),source.milestonesComplete(),source.blockers());packed.add(encode(e));while(packed.size()>MAX)packed.remove(0);setDirty();return e;}
}
