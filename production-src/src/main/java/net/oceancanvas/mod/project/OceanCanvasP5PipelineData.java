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
import java.util.UUID;

/**
 * P5 terrain-pipeline metadata.  This layer records reproducible interchange contracts and review
 * state only.  It never mutates chunks, blocks, biomes, tickets, Region protection, or operation
 * state; all destructive world authority remains in the existing server operation controllers.
 */
public final class OceanCanvasP5PipelineData extends SavedData {
    public static final int CURRENT_SCHEMA = 1;
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "p5_pipeline_data");

    /** Shared coordinate transform contract used by OC/Gaea/WorldPainter/image/Litematica handoffs. */
    public record TransformProfile(String id,String name,String sourceTool,String targetTool,
                                   int originX,int originZ,double scale,double rotationDegrees,
                                   boolean flipX,boolean flipZ,int seaLevel,int floorY,String originConvention,long updatedAt) {
        public TransformProfile {
            id=norm(id,"transform"); name=clean(name,"Transform"); sourceTool=tool(sourceTool,"OCEAN_CANVAS"); targetTool=tool(targetTool,"MINECRAFT");
            if(!Double.isFinite(scale)||scale<=0)scale=1D; scale=Math.max(0.000001D,Math.min(1_000_000D,scale));
            if(!Double.isFinite(rotationDegrees))rotationDegrees=0D; rotationDegrees=((rotationDegrees%360D)+360D)%360D;
            seaLevel=Math.max(-2048,Math.min(2048,seaLevel)); floorY=Math.max(-2048,Math.min(2048,floorY));
            originConvention=clean(originConvention,"WORLD_XZ_NORTH_UP").toUpperCase(Locale.ROOT); updatedAt=Math.max(0L,updatedAt);
        }
    }

    /** Complete reproducible export workflow; selected layers are canonical Plan-group ids. */
    public record ExportRecipe(String id,String name,String targetTool,String transformProfileId,
                               String boundsSource,int widthPx,int heightPx,List<String> layerIds,
                               String namingRule,List<String> validationSteps,long updatedAt) {
        public ExportRecipe {
            id=norm(id,"recipe");name=clean(name,"Export Recipe");targetTool=tool(targetTool,"WORLDPAINTER");transformProfileId=opt(transformProfileId);
            boundsSource=clean(boundsSource,"PROJECT").toUpperCase(Locale.ROOT);widthPx=Math.max(1,Math.min(131072,widthPx));heightPx=Math.max(1,Math.min(131072,heightPx));
            layerIds=ids(layerIds);namingRule=clean(namingRule,"{project}-{stage}-{timestamp}");validationSteps=textList(validationSteps,32);updatedAt=Math.max(0L,updatedAt);
        }
    }

    /** File-based Litematica placement registry.  Integration remains optional. */
    public record LitematicaPlacement(String id,String name,String projectId,String terrainAssetId,String fileName,String fileHash,
                                      int originX,int originY,int originZ,int rotationDegrees,String mirror,String status,String version,
                                      List<String> dependencies,String notes,long updatedAt) {
        public LitematicaPlacement {
            id=norm(id,"placement");name=clean(name,"Litematica Placement");projectId=opt(projectId);terrainAssetId=opt(terrainAssetId);
            fileName=fileName==null?"":fileName.trim();fileHash=hash(fileHash);rotationDegrees=Math.floorMod(rotationDegrees,360);
            mirror=clean(mirror,"NONE").toUpperCase(Locale.ROOT);status=clean(status,"PLANNED").toUpperCase(Locale.ROOT);version=clean(version,"1");
            dependencies=ids(dependencies);notes=limit(notes,2048);updatedAt=Math.max(0L,updatedAt);
        }
    }

    /** External file inspection sandbox.  A quarantine item cannot itself alter authoritative data. */
    public record QuarantineItem(String id,String fileName,String fileHash,String targetType,String targetId,
                                 int minX,int minZ,int maxX,int maxZ,int widthPx,int heightPx,double blocksPerPixel,
                                 int seaLevel,String orientation,String status,String issues,long createdAt) {
        public QuarantineItem {
            id=norm(id,"quarantine");fileName=clean(fileName,"external-asset");fileHash=hash(fileHash);targetType=clean(targetType,"TERRAIN_ASSET").toUpperCase(Locale.ROOT);targetId=opt(targetId);
            widthPx=Math.max(0,Math.min(131072,widthPx));heightPx=Math.max(0,Math.min(131072,heightPx));if(!Double.isFinite(blocksPerPixel)||blocksPerPixel<0)blocksPerPixel=0D;
            seaLevel=Math.max(-2048,Math.min(2048,seaLevel));orientation=clean(orientation,"NORTH_UP").toUpperCase(Locale.ROOT);status=clean(status,"QUARANTINED").toUpperCase(Locale.ROOT);
            issues=limit(issues,4096);createdAt=Math.max(0L,createdAt);
        }
    }

    /** User-extensible atlas/planning marker schema.  Values remain metadata, never code. */
    public record MarkerSchema(String id,String name,String scope,List<String> fields,String icon,String style,String notes,long updatedAt) {
        public MarkerSchema {
            id=norm(id,"marker_schema");name=clean(name,"Custom Marker");scope=clean(scope,"PLANNING").toUpperCase(Locale.ROOT);
            fields=textList(fields,32);icon=clean(icon,"waypoint");style=clean(style,"DEFAULT").toUpperCase(Locale.ROOT);notes=limit(notes,2048);updatedAt=Math.max(0L,updatedAt);
        }
    }

    private static final Codec<TransformProfile> TRANSFORM_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(TransformProfile::id),Codec.STRING.fieldOf("name").forGetter(TransformProfile::name),
            Codec.STRING.optionalFieldOf("sourceTool","OCEAN_CANVAS").forGetter(TransformProfile::sourceTool),Codec.STRING.optionalFieldOf("targetTool","MINECRAFT").forGetter(TransformProfile::targetTool),
            Codec.INT.optionalFieldOf("originX",0).forGetter(TransformProfile::originX),Codec.INT.optionalFieldOf("originZ",0).forGetter(TransformProfile::originZ),
            Codec.DOUBLE.optionalFieldOf("scale",1D).forGetter(TransformProfile::scale),Codec.DOUBLE.optionalFieldOf("rotationDegrees",0D).forGetter(TransformProfile::rotationDegrees),
            Codec.BOOL.optionalFieldOf("flipX",false).forGetter(TransformProfile::flipX),Codec.BOOL.optionalFieldOf("flipZ",false).forGetter(TransformProfile::flipZ),
            Codec.INT.optionalFieldOf("seaLevel",63).forGetter(TransformProfile::seaLevel),Codec.INT.optionalFieldOf("floorY",-25).forGetter(TransformProfile::floorY),
            Codec.STRING.optionalFieldOf("originConvention","WORLD_XZ_NORTH_UP").forGetter(TransformProfile::originConvention),Codec.LONG.optionalFieldOf("updatedAt",0L).forGetter(TransformProfile::updatedAt)
    ).apply(i,TransformProfile::new));
    private static final Codec<ExportRecipe> RECIPE_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(ExportRecipe::id),Codec.STRING.fieldOf("name").forGetter(ExportRecipe::name),Codec.STRING.optionalFieldOf("targetTool","WORLDPAINTER").forGetter(ExportRecipe::targetTool),
            Codec.STRING.optionalFieldOf("transformProfileId","").forGetter(ExportRecipe::transformProfileId),Codec.STRING.optionalFieldOf("boundsSource","PROJECT").forGetter(ExportRecipe::boundsSource),
            Codec.INT.optionalFieldOf("widthPx",4096).forGetter(ExportRecipe::widthPx),Codec.INT.optionalFieldOf("heightPx",4096).forGetter(ExportRecipe::heightPx),
            Codec.STRING.listOf().optionalFieldOf("layerIds",List.of()).forGetter(ExportRecipe::layerIds),Codec.STRING.optionalFieldOf("namingRule","{project}-{stage}-{timestamp}").forGetter(ExportRecipe::namingRule),
            Codec.STRING.listOf().optionalFieldOf("validationSteps",List.of()).forGetter(ExportRecipe::validationSteps),Codec.LONG.optionalFieldOf("updatedAt",0L).forGetter(ExportRecipe::updatedAt)
    ).apply(i,ExportRecipe::new));
    private static final Codec<LitematicaPlacement> PLACEMENT_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(LitematicaPlacement::id),Codec.STRING.fieldOf("name").forGetter(LitematicaPlacement::name),Codec.STRING.optionalFieldOf("projectId","").forGetter(LitematicaPlacement::projectId),
            Codec.STRING.optionalFieldOf("terrainAssetId","").forGetter(LitematicaPlacement::terrainAssetId),Codec.STRING.optionalFieldOf("fileName","").forGetter(LitematicaPlacement::fileName),Codec.STRING.optionalFieldOf("fileHash","").forGetter(LitematicaPlacement::fileHash),
            Codec.INT.optionalFieldOf("originX",0).forGetter(LitematicaPlacement::originX),Codec.INT.optionalFieldOf("originY",64).forGetter(LitematicaPlacement::originY),Codec.INT.optionalFieldOf("originZ",0).forGetter(LitematicaPlacement::originZ),
            Codec.INT.optionalFieldOf("rotationDegrees",0).forGetter(LitematicaPlacement::rotationDegrees),Codec.STRING.optionalFieldOf("mirror","NONE").forGetter(LitematicaPlacement::mirror),Codec.STRING.optionalFieldOf("status","PLANNED").forGetter(LitematicaPlacement::status),
            Codec.STRING.optionalFieldOf("version","1").forGetter(LitematicaPlacement::version),Codec.STRING.listOf().optionalFieldOf("dependencies",List.of()).forGetter(LitematicaPlacement::dependencies),Codec.STRING.optionalFieldOf("notes","").forGetter(LitematicaPlacement::notes),
            Codec.LONG.optionalFieldOf("updatedAt",0L).forGetter(LitematicaPlacement::updatedAt)
    ).apply(i,LitematicaPlacement::new));
    private static final Codec<QuarantineItem> QUARANTINE_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(QuarantineItem::id),Codec.STRING.fieldOf("fileName").forGetter(QuarantineItem::fileName),Codec.STRING.optionalFieldOf("fileHash","").forGetter(QuarantineItem::fileHash),
            Codec.STRING.optionalFieldOf("targetId","").forGetter(QuarantineItem::targetId),Codec.INT.optionalFieldOf("minX",0).forGetter(QuarantineItem::minX),Codec.INT.optionalFieldOf("minZ",0).forGetter(QuarantineItem::minZ),
            Codec.INT.optionalFieldOf("maxX",0).forGetter(QuarantineItem::maxX),Codec.INT.optionalFieldOf("maxZ",0).forGetter(QuarantineItem::maxZ),Codec.INT.optionalFieldOf("widthPx",0).forGetter(QuarantineItem::widthPx),
            Codec.INT.optionalFieldOf("heightPx",0).forGetter(QuarantineItem::heightPx),Codec.DOUBLE.optionalFieldOf("blocksPerPixel",0D).forGetter(QuarantineItem::blocksPerPixel),Codec.INT.optionalFieldOf("seaLevel",63).forGetter(QuarantineItem::seaLevel),
            Codec.STRING.optionalFieldOf("orientation","NORTH_UP").forGetter(QuarantineItem::orientation),Codec.STRING.optionalFieldOf("status","QUARANTINED").forGetter(QuarantineItem::status),Codec.STRING.optionalFieldOf("issues","").forGetter(QuarantineItem::issues),
            Codec.LONG.optionalFieldOf("createdAt",0L).forGetter(QuarantineItem::createdAt)
    ).apply(i,(id,file,hash,targetId,minX,minZ,maxX,maxZ,width,height,bpp,sea,orientation,status,issues,createdAt)->new QuarantineItem(id,file,hash,"TERRAIN_ASSET",targetId,minX,minZ,maxX,maxZ,width,height,bpp,sea,orientation,status,issues,createdAt)));
    private static final Codec<MarkerSchema> MARKER_CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.STRING.fieldOf("id").forGetter(MarkerSchema::id),Codec.STRING.fieldOf("name").forGetter(MarkerSchema::name),Codec.STRING.optionalFieldOf("scope","PLANNING").forGetter(MarkerSchema::scope),
            Codec.STRING.listOf().optionalFieldOf("fields",List.of()).forGetter(MarkerSchema::fields),Codec.STRING.optionalFieldOf("icon","waypoint").forGetter(MarkerSchema::icon),Codec.STRING.optionalFieldOf("style","DEFAULT").forGetter(MarkerSchema::style),
            Codec.STRING.optionalFieldOf("notes","").forGetter(MarkerSchema::notes),Codec.LONG.optionalFieldOf("updatedAt",0L).forGetter(MarkerSchema::updatedAt)
    ).apply(i,MarkerSchema::new));

    private static final Codec<OceanCanvasP5PipelineData> CODEC=RecordCodecBuilder.create(i->i.group(
            Codec.INT.optionalFieldOf("schema",CURRENT_SCHEMA).forGetter(d->d.schema),
            TRANSFORM_CODEC.listOf().optionalFieldOf("transforms",List.of()).forGetter(d->new ArrayList<>(d.transforms.values())),
            RECIPE_CODEC.listOf().optionalFieldOf("recipes",List.of()).forGetter(d->new ArrayList<>(d.recipes.values())),
            PLACEMENT_CODEC.listOf().optionalFieldOf("placements",List.of()).forGetter(d->new ArrayList<>(d.placements.values())),
            QUARANTINE_CODEC.listOf().optionalFieldOf("quarantine",List.of()).forGetter(d->new ArrayList<>(d.quarantine.values())),
            MARKER_CODEC.listOf().optionalFieldOf("markerSchemas",List.of()).forGetter(d->new ArrayList<>(d.markerSchemas.values()))
    ).apply(i,OceanCanvasP5PipelineData::new));
    public static final SavedDataType<OceanCanvasP5PipelineData> TYPE=new SavedDataType<>(DATA_ID,OceanCanvasP5PipelineData::new,CODEC,null);

    private int schema;
    private final Map<String,TransformProfile> transforms=new LinkedHashMap<>();
    private final Map<String,ExportRecipe> recipes=new LinkedHashMap<>();
    private final Map<String,LitematicaPlacement> placements=new LinkedHashMap<>();
    private final Map<String,QuarantineItem> quarantine=new LinkedHashMap<>();
    private final Map<String,MarkerSchema> markerSchemas=new LinkedHashMap<>();

    public OceanCanvasP5PipelineData(){this(CURRENT_SCHEMA,List.of(),List.of(),List.of(),List.of(),List.of());}
    private OceanCanvasP5PipelineData(int schema,List<TransformProfile> transforms,List<ExportRecipe> recipes,List<LitematicaPlacement> placements,List<QuarantineItem> quarantine,List<MarkerSchema> markerSchemas){
        this.schema=Math.max(1,schema);if(transforms!=null)transforms.forEach(v->this.transforms.put(v.id(),v));if(recipes!=null)recipes.forEach(v->this.recipes.put(v.id(),v));
        if(placements!=null)placements.forEach(v->this.placements.put(v.id(),v));if(quarantine!=null)quarantine.forEach(v->this.quarantine.put(v.id(),v));if(markerSchemas!=null)markerSchemas.forEach(v->this.markerSchemas.put(v.id(),v));installDefaults();
    }
    private void installDefaults(){
        transforms.putIfAbsent("oc_worldpainter",new TransformProfile("oc_worldpainter","OC → WorldPainter","OCEAN_CANVAS","WORLDPAINTER",0,0,1D,0D,false,false,63,-25,"WORLD_XZ_NORTH_UP",0L));
        transforms.putIfAbsent("oc_gaea",new TransformProfile("oc_gaea","OC → Gaea","OCEAN_CANVAS","GAEA",0,0,1D,0D,false,true,63,-25,"IMAGE_X_RIGHT_Y_DOWN_TO_WORLD_XZ",0L));
        recipes.putIfAbsent("worldpainter_roundtrip",new ExportRecipe("worldpainter_roundtrip","WorldPainter Round Trip","WORLDPAINTER","oc_worldpainter","PROJECT",4096,4096,List.of("coastline","mountains","rivers","lakes","biomes"),"{project}-worldpainter-{timestamp}",List.of("HEIGHTMAP_CONTRACT","FINGERPRINT","SEAMS","ROUND_TRIP"),0L));
    }

    public static OceanCanvasP5PipelineData get(ServerLevel world){return world.getDataStorage().computeIfAbsent(TYPE);} public int schema(){return schema;}
    public List<TransformProfile> transforms(){return List.copyOf(transforms.values());}public TransformProfile transform(String id){return transforms.get(opt(id));}public void putTransform(TransformProfile v){transforms.put(v.id(),v);setDirty();}public boolean removeTransform(String id){boolean c=transforms.remove(opt(id))!=null;if(c)setDirty();return c;}
    public List<ExportRecipe> recipes(){return List.copyOf(recipes.values());}public ExportRecipe recipe(String id){return recipes.get(opt(id));}public void putRecipe(ExportRecipe v){recipes.put(v.id(),v);setDirty();}public boolean removeRecipe(String id){boolean c=recipes.remove(opt(id))!=null;if(c)setDirty();return c;}
    public List<LitematicaPlacement> placements(){return List.copyOf(placements.values());}public LitematicaPlacement placement(String id){return placements.get(opt(id));}public void putPlacement(LitematicaPlacement v){placements.put(v.id(),v);setDirty();}public boolean removePlacement(String id){boolean c=placements.remove(opt(id))!=null;if(c)setDirty();return c;}
    public List<QuarantineItem> quarantine(){return List.copyOf(quarantine.values());}public QuarantineItem quarantine(String id){return quarantine.get(opt(id));}public void putQuarantine(QuarantineItem v){quarantine.put(v.id(),v);setDirty();}public boolean removeQuarantine(String id){boolean c=quarantine.remove(opt(id))!=null;if(c)setDirty();return c;}
    public List<MarkerSchema> markerSchemas(){return List.copyOf(markerSchemas.values());}public MarkerSchema markerSchema(String id){return markerSchemas.get(opt(id));}public void putMarkerSchema(MarkerSchema v){markerSchemas.put(v.id(),v);setDirty();}public boolean removeMarkerSchema(String id){boolean c=markerSchemas.remove(opt(id))!=null;if(c)setDirty();return c;}
    public String newId(String prefix){return norm(prefix+"_"+UUID.randomUUID().toString().substring(0,8),prefix);}

    private static List<String> ids(List<String> in){if(in==null)return List.of();return in.stream().map(OceanCanvasP5PipelineData::opt).filter(s->!s.isBlank()).distinct().limit(128).toList();}
    private static List<String> textList(List<String> in,int max){if(in==null)return List.of();return in.stream().map(v->limit(v,128).trim()).filter(v->!v.isBlank()).distinct().limit(max).toList();}
    private static String hash(String v){String s=v==null?"":v.trim().toLowerCase(Locale.ROOT);return s.matches("[0-9a-f]{64}")?s:"";}
    private static String tool(String v,String d){return clean(v,d).toUpperCase(Locale.ROOT).replace(' ','_');}
    private static String opt(String v){return v==null?"":v.trim().toLowerCase(Locale.ROOT).replace(' ','_');}
    private static String norm(String v,String d){String s=opt(v).replaceAll("[^a-z0-9_.:-]","_");return s.isBlank()?d:s;}
    private static String clean(String v,String d){return v==null||v.isBlank()?d:v.trim();}
    private static String limit(String v,int max){String s=v==null?"":v;return s.substring(0,Math.min(max,s.length()));}
}
