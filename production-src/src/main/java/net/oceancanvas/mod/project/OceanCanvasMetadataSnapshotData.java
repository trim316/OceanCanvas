package net.oceancanvas.mod.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Bounded lightweight metadata snapshots for Recovery/changelog diffing; never a terrain backup. */
public final class OceanCanvasMetadataSnapshotData extends SavedData {
    public static final int CURRENT_SCHEMA=1;
    private static final int MAX_SNAPSHOTS=64;
    private static final Identifier DATA_ID=Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"metadata_snapshots");

    public record ProjectSummary(int schema,String currentProject,String pregenProfile,int regionCount,String regionSummary,String regionDigest){}
    public record TerrainSummary(int schema,long vanillaCount,long canvasCount,long customCount,long unknownCount,String digest){}
    public record SealSummary(int processed,int verified,int physical,int formatVersion,int physicalProfileVersion,String digest){}
    public record StructureSummary(int protectedRegions,int relocatedShipwrecks){}
    public record Snapshot(String id,long epochMillis,String label,String reason,ProjectSummary project,TerrainSummary terrain,
                           SealSummary seals,StructureSummary structures){}

    private static final Codec<ProjectSummary> PROJECT_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.fieldOf("schema").forGetter(ProjectSummary::schema),
            Codec.STRING.fieldOf("currentProject").forGetter(ProjectSummary::currentProject),
            Codec.STRING.fieldOf("pregenProfile").forGetter(ProjectSummary::pregenProfile),
            Codec.INT.fieldOf("regionCount").forGetter(ProjectSummary::regionCount),
            Codec.STRING.fieldOf("regionSummary").forGetter(ProjectSummary::regionSummary),
            Codec.STRING.fieldOf("regionDigest").forGetter(ProjectSummary::regionDigest)
    ).apply(i,ProjectSummary::new));
    private static final Codec<TerrainSummary> TERRAIN_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.fieldOf("schema").forGetter(TerrainSummary::schema),
            Codec.LONG.fieldOf("vanillaCount").forGetter(TerrainSummary::vanillaCount),
            Codec.LONG.fieldOf("canvasCount").forGetter(TerrainSummary::canvasCount),
            Codec.LONG.fieldOf("customCount").forGetter(TerrainSummary::customCount),
            Codec.LONG.fieldOf("unknownCount").forGetter(TerrainSummary::unknownCount),
            Codec.STRING.fieldOf("digest").forGetter(TerrainSummary::digest)
    ).apply(i,TerrainSummary::new));
    private static final Codec<SealSummary> SEAL_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.fieldOf("processed").forGetter(SealSummary::processed),
            Codec.INT.fieldOf("verified").forGetter(SealSummary::verified),
            Codec.INT.fieldOf("physical").forGetter(SealSummary::physical),
            Codec.INT.fieldOf("formatVersion").forGetter(SealSummary::formatVersion),
            Codec.INT.fieldOf("physicalProfileVersion").forGetter(SealSummary::physicalProfileVersion),
            Codec.STRING.fieldOf("digest").forGetter(SealSummary::digest)
    ).apply(i,SealSummary::new));
    private static final Codec<StructureSummary> STRUCTURE_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.fieldOf("protectedRegions").forGetter(StructureSummary::protectedRegions),
            Codec.INT.fieldOf("relocatedShipwrecks").forGetter(StructureSummary::relocatedShipwrecks)
    ).apply(i,StructureSummary::new));
    private static final Codec<Snapshot> SNAPSHOT_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(Snapshot::id),
            Codec.LONG.fieldOf("epochMillis").forGetter(Snapshot::epochMillis),
            Codec.STRING.fieldOf("label").forGetter(Snapshot::label),
            Codec.STRING.fieldOf("reason").forGetter(Snapshot::reason),
            PROJECT_CODEC.fieldOf("project").forGetter(Snapshot::project),
            TERRAIN_CODEC.fieldOf("terrain").forGetter(Snapshot::terrain),
            SEAL_CODEC.fieldOf("seals").forGetter(Snapshot::seals),
            STRUCTURE_CODEC.fieldOf("structures").forGetter(Snapshot::structures)
    ).apply(i,Snapshot::new));
    private static final Codec<OceanCanvasMetadataSnapshotData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",CURRENT_SCHEMA).forGetter(d->d.schema),
            SNAPSHOT_CODEC.listOf().optionalFieldOf("snapshots",List.of()).forGetter(d->d.snapshots)
    ).apply(i,OceanCanvasMetadataSnapshotData::new));
    public static final SavedDataType<OceanCanvasMetadataSnapshotData> TYPE=
            new SavedDataType<>(DATA_ID,OceanCanvasMetadataSnapshotData::new,CODEC,null);

    private int schema;
    private final List<Snapshot> snapshots;
    public OceanCanvasMetadataSnapshotData(){this(CURRENT_SCHEMA,List.of());}
    private OceanCanvasMetadataSnapshotData(int schema,List<Snapshot> snapshots){
        this.schema=Math.max(1,schema);this.snapshots=new ArrayList<>();
        if(snapshots!=null){int start=Math.max(0,snapshots.size()-MAX_SNAPSHOTS);this.snapshots.addAll(snapshots.subList(start,snapshots.size()));}
    }
    public static OceanCanvasMetadataSnapshotData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);}

    /** Persisted metadata snapshot schema observed in this world. */
    public int schema(){return schema;}

    public Snapshot capture(ServerLevel world,String label,String reason){
        OceanCanvasProjectData project=OceanCanvasProjectData.get(world);
        OceanCanvasTerrainStateData terrain=OceanCanvasTerrainStateData.get(world);
        OceanCanvasProtectedData seals=OceanCanvasProtectedData.get(world);
        List<OceanCanvasPlayerZones.Zone> zones=new ArrayList<>(OceanCanvasPlayerZones.get(world).all());
        zones.sort(Comparator.comparing(z->z.name().toLowerCase(java.util.Locale.ROOT)));

        StringBuilder regionText=new StringBuilder();
        for(var zone:zones){
            var meta=project.regionMeta(zone.name());
            regionText.append(zone.name()).append('|').append(zone.bounds().minX()).append(',').append(zone.bounds().minZ()).append(',')
                    .append(zone.bounds().maxX()).append(',').append(zone.bounds().maxZ()).append('|').append(zone.protectedNow()).append('|')
                    .append(meta==null?OceanCanvasProjectData.RegionStage.RESERVED.name():meta.stage()).append('|')
                    .append(zone.biomeOverride()==null?"":zone.biomeOverride()).append('|').append(zone.suppressHostileMobs()).append('|')
                    .append(zone.structureOverrides()).append('|');
            if(zone.shapeVertices().size()>=6) regionText.append("POLYGON:").append(zone.shapeVertices());
            else if(!zone.chunks().isEmpty()) regionText.append("CHUNK_MASK_COUNT:").append(zone.chunks().size());
            else regionText.append("RECTANGLE");
            regionText.append('\n');
        }

        // v253.77 P0 scale fix: terrain/seal ledgers maintain order-independent
        // fingerprints and counts as they mutate. A metadata checkpoint is therefore
        // O(1) with respect to world chunk count instead of materializing/sorting up to
        // ~1.56M entries and building giant digest strings on the server thread.
        ProjectSummary ps=new ProjectSummary(project.schema(),safe(project.currentProject()),project.pregenProfile().name(),zones.size(),regionText.toString(),digest(regionText.toString()));
        TerrainSummary ts=new TerrainSummary(terrain.schema(),terrain.stateCount(OceanCanvasTerrainStateData.TerrainState.VANILLA),
                terrain.stateCount(OceanCanvasTerrainStateData.TerrainState.CANVAS),terrain.stateCount(OceanCanvasTerrainStateData.TerrainState.CUSTOM_OR_MODIFIED),
                terrain.stateCount(OceanCanvasTerrainStateData.TerrainState.UNKNOWN),terrain.membershipFingerprint());
        SealSummary ss=new SealSummary(seals.processedChunkCount(),seals.verifiedProcessedChunkCount(),seals.physicallyVerifiedProcessedChunkCount(),
                OceanCanvasProtectedData.currentCompletionSealFormatVersion(),OceanCanvasProtectedData.currentPhysicalProfileVersion(),seals.processedMembershipFingerprint());
        StructureSummary structures=new StructureSummary(seals.protectedRegionCount(),seals.relocatedShipwreckCount());
        long now=System.currentTimeMillis();
        Snapshot snapshot=new Snapshot(Long.toUnsignedString(now,36)+"-"+Integer.toUnsignedString(snapshots.size(),36),now,safe(label),safe(reason),ps,ts,ss,structures);
        snapshots.add(snapshot);while(snapshots.size()>MAX_SNAPSHOTS)snapshots.remove(0);setDirty();return snapshot;
    }

    public List<Snapshot> recent(){List<Snapshot> out=new ArrayList<>(snapshots);java.util.Collections.reverse(out);return List.copyOf(out);}
    public Snapshot latest(){return snapshots.isEmpty()?null:snapshots.get(snapshots.size()-1);}
    public List<String> diffLatestTwo(){return snapshots.size()<2?List.of("Need at least two metadata snapshots."):diff(snapshots.get(snapshots.size()-2),snapshots.get(snapshots.size()-1));}
    public static List<String> diff(Snapshot a,Snapshot b){
        List<String> out=new ArrayList<>();
        add(out,"Project schema",a.project().schema(),b.project().schema());add(out,"Terrain schema",a.terrain().schema(),b.terrain().schema());
        add(out,"Current project",a.project().currentProject(),b.project().currentProject());add(out,"Pregen profile",a.project().pregenProfile(),b.project().pregenProfile());
        add(out,"Regions",a.project().regionCount(),b.project().regionCount());add(out,"Terrain VANILLA",a.terrain().vanillaCount(),b.terrain().vanillaCount());
        add(out,"Terrain CANVAS",a.terrain().canvasCount(),b.terrain().canvasCount());add(out,"Terrain CUSTOM_OR_MODIFIED",a.terrain().customCount(),b.terrain().customCount());
        add(out,"Terrain UNKNOWN",a.terrain().unknownCount(),b.terrain().unknownCount());add(out,"Processed seals",a.seals().processed(),b.seals().processed());
        add(out,"Verified seals",a.seals().verified(),b.seals().verified());add(out,"Physical seals",a.seals().physical(),b.seals().physical());
        add(out,"Seal format",a.seals().formatVersion(),b.seals().formatVersion());add(out,"Physical profile",a.seals().physicalProfileVersion(),b.seals().physicalProfileVersion());
        add(out,"Protected structure regions",a.structures().protectedRegions(),b.structures().protectedRegions());
        add(out,"Relocated shipwrecks",a.structures().relocatedShipwrecks(),b.structures().relocatedShipwrecks());
        if(!a.project().regionDigest().equals(b.project().regionDigest()))out.add("Region/project configuration changed.");
        if(!a.terrain().digest().equals(b.terrain().digest()))out.add("Terrain-state membership changed.");
        if(!a.seals().digest().equals(b.seals().digest()))out.add("Processed-seal membership changed.");
        return out.isEmpty()?List.of("No tracked metadata differences."):List.copyOf(out);
    }
    private static void add(List<String> out,String label,Object a,Object b){if(!java.util.Objects.equals(a,b))out.add(label+": "+a+" -> "+b);}
    private static String safe(String s){return s==null?"":s;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)),0,8);}
        catch(Exception ignored){return Integer.toHexString(value.hashCode());}}
}
