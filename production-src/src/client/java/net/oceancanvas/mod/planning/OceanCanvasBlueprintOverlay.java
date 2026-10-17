package net.oceancanvas.mod.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry;
import net.oceancanvas.mod.network.OceanCanvasZoneClientCache;

/**
 * Client-only in-world Blueprint renderer.
 *
 * <p>Hard boundary: this class renders immutable copies of Plan data. It never places blocks, entities, particles,
 * tickets, or chunks. Minecraft 26.2's render pipeline is split into extraction and drawing phases, so all world/cache
 * access happens in {@link #extract(LevelExtractionContext)} and the drawing phase consumes only immutable render
 * segments. This keeps Blueprint compatible with the current Blaze3D OpenGL/Vulkan abstraction instead of depending
 * on removed raw-GL/ShapeRenderer paths.</p>
 */
public final class OceanCanvasBlueprintOverlay {
    public enum ProjectionMode { SEA_LEVEL, FIXED_Y, SURFACE, FLOATING }
    public enum DepthMode { SURFACE, XRAY, HYBRID }
    public enum PerformancePreset { PERFORMANCE, BALANCED, LONG_RANGE, CUSTOM }

    private record Segment(double x1,double y1,double z1,double x2,double y2,double z2,
                           double width1,double width2,int argb) { }
    private record ReferenceTile(String cacheId,int lod,int tileX,int tileY,double opacity,
                                 double x0,double z0,double x1,double z1,double y) { }
    private record FrameState(List<Segment> segments,List<ReferenceTile> referenceTiles, Vec3 camera) {
        static FrameState empty(){ return new FrameState(List.of(),List.of(),Vec3.ZERO); }
    }
    private record DrawPass(StagedVertexBuffer.Draw draw, RenderPipeline pipeline) { }
    private record TextureDraw(StagedVertexBuffer.Draw draw, RenderPipeline pipeline, Identifier texture) { }
    private record CachedVectorGeometry(String signature,List<int[]> points,double minX,double minZ,double maxX,double maxZ) { }

    /** Vanilla depth-tested translucent debug-filled pipeline. */
    private static final RenderPipeline SURFACE_PIPELINE = RenderPipelines.DEBUG_FILLED_BOX;
    /** Same Blaze3D pipeline family, but deliberately without a depth/stencil attachment for X-Ray guides. */
    private static final RenderPipeline XRAY_PIPELINE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"pipeline/blueprint_xray"))
            .withDepthStencilState(Optional.empty())
            .build();
    private static final StagedVertexBuffer STAGED_BUFFER = new StagedVertexBuffer(() -> "Ocean Canvas Blueprint", RenderType.SMALL_BUFFER_SIZE);
    private static final StagedVertexBuffer TEXTURE_BUFFER = new StagedVertexBuffer(() -> "Ocean Canvas Blueprint References", RenderType.SMALL_BUFFER_SIZE);
    private static final Map<String,CachedVectorGeometry> VECTOR_GEOMETRY_CACHE = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String,CachedVectorGeometry>(256,0.75f,true){
                @Override protected boolean removeEldestEntry(Map.Entry<String,CachedVectorGeometry> eldest){return size()>512;}
            });
    private static final int MAX_REFERENCE_TILES_PER_FRAME = 96;
    private static final int MAX_VECTOR_SEGMENTS_PER_FRAME = 12000;
    private static final int SPATIAL_CELL_BLOCKS = 512;
    private static volatile long spatialGeneration = Long.MIN_VALUE;
    private static volatile Map<Long,List<OceanCanvasZoneClientCache.PlanningVector>> spatialVectors = Map.of();
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1F,1F,1F,1F);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private static volatile boolean enabled;
    private static volatile boolean comparisonHidden;
    private static volatile ProjectionMode projectionMode = ProjectionMode.SEA_LEVEL;
    private static volatile DepthMode depthMode = DepthMode.HYBRID;
    private static volatile PerformancePreset performancePreset = PerformancePreset.BALANCED;
    private static volatile int fixedY = 64;
    private static volatile int floatingOffset = 4;
    private static volatile double renderDistance = 768.0D;
    private static volatile float opacity = 0.72F;
    private static volatile FrameState frameState = FrameState.empty();
    private static volatile boolean registered;

    private static volatile String operationPreviewKind = "";
    private static volatile int operationMinX, operationMinZ, operationMaxX, operationMaxZ;
    private static volatile List<Long> operationChunks = List.of();

    private OceanCanvasBlueprintOverlay() { }

    /** Registers the 26.2 extraction/drawing pair exactly once. */
    public static synchronized void register() {
        if(registered)return;
        registered=true;
        LevelExtractionEvents.END_EXTRACTION.register(OceanCanvasBlueprintOverlay::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(OceanCanvasBlueprintOverlay::draw);
    }

    private static void extract(LevelExtractionContext context){
        if(!enabled||comparisonHidden){ frameState=FrameState.empty(); return; }
        Vec3 camera=context.levelState().cameraRenderState.pos;
        double maxDistance=effectiveRenderDistance();
        double maxDistanceSq=maxDistance*maxDistance;
        var groups=OceanCanvasZoneClientCache.planGroups();
        Map<String,OceanCanvasZoneClientCache.PlanGroup> byGroup=new LinkedHashMap<>();
        for(var group:groups)byGroup.put(group.id(),group);
        String activeScenario=OceanCanvasZoneClientCache.activeScenario();
        List<Segment> out=new ArrayList<>();
        List<ReferenceTile> referenceTiles=new ArrayList<>();
        extractReferenceTiles(context,camera,maxDistanceSq,referenceTiles);

        var vectors=querySpatialVectors(camera.x,camera.z,maxDistance);
        vectors.sort(Comparator.comparingInt(OceanCanvasZoneClientCache.PlanningVector::drawOrder).thenComparing(OceanCanvasZoneClientCache.PlanningVector::id));
        for(var vector:vectors){
            if(!vector.visible()||vector.points()==null||vector.points().isBlank())continue;
            double groupOpacity=groupOpacity(vector.parentId(),byGroup,new java.util.HashSet<>());
            if(groupOpacity<=0.001D)continue;
            if(vector.scenarioId()!=null&&!vector.scenarioId().isBlank()&&!vector.scenarioId().equals(activeScenario))continue;
            CachedVectorGeometry cached=vectorGeometry(vector);
            if(cached.points().isEmpty())continue;
            double boundsDistanceSq=distanceToBoundsSq(camera.x,camera.z,cached.minX(),cached.minZ(),cached.maxX(),cached.maxZ());
            if(boundsDistanceSq>maxDistanceSq)continue;
            List<int[]> points=lodPoints(cached.points(),Math.sqrt(boundsDistanceSq));
            if(points.isEmpty())continue;
            if(points.size()==1){
                addPointTerrainPrimitive(context,camera,out,vector,points.get(0),groupOpacity);
                continue;
            }
            double[] variableWidth=variableWidth(vector.guideData(),vector.widthBlocks());
            boolean closed=isClosed(vector.type());
            double[] along=pathFractions(points,closed);
            if(closed) addAreaFillSegments(context,camera,out,vector,points,vector.fillArgb(),vector.strokeArgb(),groupOpacity);
            int segmentCount=closed?points.size():points.size()-1;
            for(int i=0;i<segmentCount;i++){
                int[] a=points.get(i),b=points.get((i+1)%points.size());
                double t1=along[i],t2=closed&&i==points.size()-1?1D:along[(i+1)%points.size()];
                double widthA=Math.max(0.8D,variableWidth[0]+(variableWidth[1]-variableWidth[0])*t1);
                double widthB=Math.max(0.8D,variableWidth[0]+(variableWidth[1]-variableWidth[0])*t2);
                double y1=authoredY(context,vector,t1,a[0],a[1],camera.y), y2=authoredY(context,vector,t2,b[0],b[1],camera.y);
                int argb=applyAlpha(vector.strokeArgb(),(float)(opacity*groupOpacity));
                out.add(new Segment(a[0],y1,a[1],b[0],y2,b[1],widthA,widthB,argb));
                if(out.size()>=MAX_VECTOR_SEGMENTS_PER_FRAME)break;
            }
            addSemanticEnvelope(context,camera,out,vector,points,along,groupOpacity);
            if(out.size()>=MAX_VECTOR_SEGMENTS_PER_FRAME)break;
        }
        addViewpointMarkers(out,camera,maxDistanceSq);
        addOperationPreviewSegments(context,camera,out);
        frameState=new FrameState(List.copyOf(out),List.copyOf(referenceTiles),camera);
    }

    private static void draw(LevelRenderContext context){
        FrameState frame=frameState;
        if(!enabled||comparisonHidden||(frame.segments().isEmpty()&&frame.referenceTiles().isEmpty()))return;
        drawReferenceTiles(context,frame);
        List<DrawPass> passes=new ArrayList<>(2);
        switch(depthMode){
            case SURFACE -> passes.add(buildDraw(context,frame,SURFACE_PIPELINE,1.0F));
            case XRAY -> passes.add(buildDraw(context,frame,XRAY_PIPELINE,0.72F));
            case HYBRID -> {
                passes.add(buildDraw(context,frame,SURFACE_PIPELINE,1.0F));
                passes.add(buildDraw(context,frame,XRAY_PIPELINE,0.23F));
            }
        }
        passes.removeIf(java.util.Objects::isNull);
        if(passes.isEmpty()){STAGED_BUFFER.endFrame();return;}
        STAGED_BUFFER.upload();
        Minecraft mc=Minecraft.getInstance();
        for(DrawPass pass:passes){
            StagedVertexBuffer.ExecuteInfo info=STAGED_BUFFER.getExecuteInfo(pass.draw());
            if(info!=null)execute(mc,info,pass.pipeline());
        }
        STAGED_BUFFER.endFrame();
    }

    private static DrawPass buildDraw(LevelRenderContext context,FrameState frame,RenderPipeline pipeline,float alphaScale){
        VertexFormat format=pipeline.getVertexFormatBinding(0);
        if(format==null)return null;
        PrimitiveTopology primitive=pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw=STAGED_BUFFER.appendDraw(format,primitive,primitive==PrimitiveTopology.QUADS?RenderSystem.getProjectionType().vertexSorting():null);
        VertexConsumer builder=STAGED_BUFFER.getVertexBuilder(draw);
        PoseStack matrices=context.poseStack();
        matrices.pushPose();
        Vec3 camera=context.levelState().cameraRenderState.pos;
        matrices.translate(-camera.x,-camera.y,-camera.z);
        Matrix4fc pose=matrices.last().pose();
        for(Segment segment:frame.segments())emitRibbon(pose,builder,segment,alphaScale);
        matrices.popPose();
        return new DrawPass(draw,pipeline);
    }

    /** Extracts only the visible reference tiles for this frame. Image decoding/GPU registration remains in drawing. */
    private static void extractReferenceTiles(LevelExtractionContext context,Vec3 camera,double maxDistanceSq,List<ReferenceTile> out){
        var refs=new ArrayList<>(OceanCanvasZoneClientCache.planningReferences());
        refs.sort(Comparator.comparingInt(OceanCanvasZoneClientCache.PlanningReference::drawOrder).thenComparing(OceanCanvasZoneClientCache.PlanningReference::id));
        for(var ref:refs){
            if(out.size()>=MAX_REFERENCE_TILES_PER_FRAME)break;
            if(!ref.visible()||ref.opacity()<=0.01D||!OceanCanvasReferenceAssetStore.exists(ref.assetId()))continue;
            try{
                double[] world=referenceWorldBounds(ref);
                if(distanceToBoundsSq(camera.x,camera.z,world[0],world[1],world[2],world[3])>maxDistanceSq)continue;
                var renderAsset=OceanCanvasReferenceRegistration.parse(ref.registrationPoints()).size()>=2
                        ? OceanCanvasReferenceAssetStore.affineRegisteredAsset(ref.assetId(),ref.registrationPoints())
                        : OceanCanvasReferenceAssetStore.rotatedAsset(ref.assetId(),ref.rotation());
                double worldPixels=Math.max(1.0,Math.max(Math.abs(world[2]-world[0]),Math.abs(world[3]-world[1])));
                double distance=Math.sqrt(distanceToBoundsSq(camera.x,camera.z,world[0],world[1],world[2],world[3]));
                double desiredPixels=Math.max(96.0,worldPixels/(1.0+distance/320.0));
                double pixelsPerSource=desiredPixels/Math.max(renderAsset.width(),renderAsset.height());
                int lod=0;while(lod+1<renderAsset.levels()&&pixelsPerSource*Math.pow(2,lod)<0.75D)lod++;
                int[] size=OceanCanvasReferenceAssetStore.levelSize(renderAsset.cacheId(),lod);int lw=size[0],lh=size[1];
                int tilesX=(lw+OceanCanvasReferenceAssetStore.TILE_SIZE-1)/OceanCanvasReferenceAssetStore.TILE_SIZE;
                int tilesY=(lh+OceanCanvasReferenceAssetStore.TILE_SIZE-1)/OceanCanvasReferenceAssetStore.TILE_SIZE;
                for(int ty=0;ty<tilesY&&out.size()<MAX_REFERENCE_TILES_PER_FRAME;ty++)for(int tx=0;tx<tilesX&&out.size()<MAX_REFERENCE_TILES_PER_FRAME;tx++){
                    double u0=(tx*OceanCanvasReferenceAssetStore.TILE_SIZE)/(double)lw,v0=(ty*OceanCanvasReferenceAssetStore.TILE_SIZE)/(double)lh;
                    double u1=Math.min(1D,((tx+1)*OceanCanvasReferenceAssetStore.TILE_SIZE)/(double)lw),v1=Math.min(1D,((ty+1)*OceanCanvasReferenceAssetStore.TILE_SIZE)/(double)lh);
                    double x0=world[0]+(world[2]-world[0])*u0,x1=world[0]+(world[2]-world[0])*u1;
                    double z0=world[1]+(world[3]-world[1])*v0,z1=world[1]+(world[3]-world[1])*v1;
                    double cx=(x0+x1)*0.5,cz=(z0+z1)*0.5;
                    if(distanceToBoundsSq(camera.x,camera.z,Math.min(x0,x1),Math.min(z0,z1),Math.max(x0,x1),Math.max(z0,z1))>maxDistanceSq)continue;
                    double y=projectionY(context,cx,cz,camera.y)+0.018D;
                    out.add(new ReferenceTile(renderAsset.cacheId(),lod,tx,ty,Math.max(0.05D,Math.min(1D,ref.opacity()*opacity)),x0,z0,x1,z1,y));
                }
            }catch(Exception ignored){ }
        }
    }

    private static double[] referenceWorldBounds(OceanCanvasZoneClientCache.PlanningReference ref)throws java.io.IOException{
        var registration=OceanCanvasReferenceRegistration.parse(ref.registrationPoints());
        if(registration.size()>=2){
            var transform=OceanCanvasReferenceRegistration.solve(registration);
            var asset=OceanCanvasReferenceAssetStore.assetInfo(ref.assetId());
            return OceanCanvasReferenceRegistration.worldBounds(transform,asset.width(),asset.height());
        }
        double minX=Math.min(ref.minX(),ref.maxX()),maxX=Math.max(ref.minX(),ref.maxX());
        double minZ=Math.min(ref.minZ(),ref.maxZ()),maxZ=Math.max(ref.minZ(),ref.maxZ());
        double cx=(minX+maxX)*0.5,cz=(minZ+maxZ)*0.5,w=maxX-minX,h=maxZ-minZ;
        double r=Math.toRadians(ref.rotation()),c=Math.abs(Math.cos(r)),ss=Math.abs(Math.sin(r));
        double rw=w*c+h*ss,rh=h*c+w*ss;return new double[]{cx-rw*0.5,cz-rh*0.5,cx+rw*0.5,cz+rh*0.5};
    }

    private static void drawReferenceTiles(LevelRenderContext context,FrameState frame){
        if(frame.referenceTiles().isEmpty())return;
        List<TextureDraw> draws=new ArrayList<>(frame.referenceTiles().size()*(depthMode==DepthMode.HYBRID?2:1));
        PoseStack matrices=context.poseStack();Vec3 camera=context.levelState().cameraRenderState.pos;
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);Matrix4fc pose=matrices.last().pose();
        for(ReferenceTile tile:frame.referenceTiles()){
            Identifier texture=OceanCanvasReferenceTextureCache.texture(tile.cacheId(),tile.lod(),tile.tileX(),tile.tileY(),tile.opacity());
            if(texture==null)continue;
            switch(depthMode){
                case SURFACE -> appendTexturedTile(draws,pose,tile,RenderPipelines.END_SKY,texture,1F);
                case XRAY -> appendTexturedTile(draws,pose,tile,RenderPipelines.GUI_TEXTURED,texture,0.72F);
                case HYBRID -> {
                    appendTexturedTile(draws,pose,tile,RenderPipelines.END_SKY,texture,0.88F);
                    appendTexturedTile(draws,pose,tile,RenderPipelines.GUI_TEXTURED,texture,0.16F);
                }
            }
        }
        matrices.popPose();
        if(draws.isEmpty()){TEXTURE_BUFFER.endFrame();return;}
        TEXTURE_BUFFER.upload();Minecraft mc=Minecraft.getInstance();
        for(TextureDraw draw:draws){var info=TEXTURE_BUFFER.getExecuteInfo(draw.draw());if(info!=null)executeTextured(mc,info,draw.pipeline(),draw.texture());}
        TEXTURE_BUFFER.endFrame();
    }
    private static void appendTexturedTile(List<TextureDraw> draws,Matrix4fc pose,ReferenceTile tile,RenderPipeline pipeline,Identifier texture,float alpha){
        VertexFormat format=pipeline.getVertexFormatBinding(0);if(format==null)return;
        PrimitiveTopology primitive=pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw=TEXTURE_BUFFER.appendDraw(format,primitive,primitive==PrimitiveTopology.QUADS?RenderSystem.getProjectionType().vertexSorting():null);
        VertexConsumer b=TEXTURE_BUFFER.getVertexBuilder(draw);
        textureVertex(b,pose,tile.x0(),tile.y(),tile.z0(),0F,0F,1F,1F,1F,alpha);
        textureVertex(b,pose,tile.x1(),tile.y(),tile.z0(),1F,0F,1F,1F,1F,alpha);
        textureVertex(b,pose,tile.x1(),tile.y(),tile.z1(),1F,1F,1F,1F,1F,alpha);
        textureVertex(b,pose,tile.x0(),tile.y(),tile.z1(),0F,1F,1F,1F,1F,alpha);
        draws.add(new TextureDraw(draw,pipeline,texture));
    }
    private static void textureVertex(VertexConsumer out,Matrix4fc pose,double x,double y,double z,float u,float v,float r,float g,float b,float a){
        out.addVertex(pose,(float)x,(float)y,(float)z).setUv(u,v).setColor(r,g,b,a);
    }
    private static void executeTextured(Minecraft client,StagedVertexBuffer.ExecuteInfo info,RenderPipeline pipeline,Identifier textureId){
        GpuBufferSlice transforms=RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(),COLOR_MODULATOR,MODEL_OFFSET,TEXTURE_MATRIX);
        RenderTarget target=client.gameRenderer.mainRenderTarget();GpuTextureView color=target.getColorTextureView();if(color==null)return;
        AbstractTexture texture=client.getTextureManager().getTexture(textureId);if(texture==null)return;
        try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Ocean Canvas Blueprint Reference",color,Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
            pass.setPipeline(pipeline);RenderSystem.bindDefaultUniforms(pass);pass.setUniform("DynamicTransforms",transforms);
            pass.bindTexture("Sampler0",texture.getTextureView(),texture.getSampler());
            pass.setVertexBuffer(0,info.vertexBuffer().slice());pass.setIndexBuffer(info.indexBuffer(),info.indexType());
            pass.drawIndexed(info.indexCount(),1,info.firstIndex(),info.baseVertex(),0);
        }
    }

    private static CachedVectorGeometry vectorGeometry(OceanCanvasZoneClientCache.PlanningVector vector){
        String signature=vector.points()+"|"+vector.guideData()+"|"+vector.smooth()+"|"+vector.bezier();
        CachedVectorGeometry existing=VECTOR_GEOMETRY_CACHE.get(vector.id());if(existing!=null&&existing.signature().equals(signature))return existing;
        List<int[]> raw=parsePoints(vector.points());
        List<int[]> points=vector.bezier()?bezierPoints(raw,parseBezierHandles(vector.guideData())):(vector.smooth()?smoothPoints(raw):raw);
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(int[] p:points){minX=Math.min(minX,p[0]);minZ=Math.min(minZ,p[1]);maxX=Math.max(maxX,p[0]);maxZ=Math.max(maxZ,p[1]);}
        if(points.isEmpty()){minX=minZ=maxX=maxZ=0;}
        CachedVectorGeometry made=new CachedVectorGeometry(signature,List.copyOf(points),minX,minZ,maxX,maxZ);VECTOR_GEOMETRY_CACHE.put(vector.id(),made);return made;
    }

    private static double distanceToBoundsSq(double x,double z,double minX,double minZ,double maxX,double maxZ){
        double dx=x<minX?minX-x:(x>maxX?x-maxX:0),dz=z<minZ?minZ-z:(z>maxZ?z-maxZ:0);return dx*dx+dz*dz;
    }

    private static void emitRibbon(Matrix4fc pose,VertexConsumer out,Segment s,float alphaScale){
        double dx=s.x2()-s.x1(),dz=s.z2()-s.z1(),len=Math.hypot(dx,dz);
        if(len<1.0e-5)return;
        double nx=-dz/len,nz=dx/len;
        double h1=Math.max(0.4,s.width1()/2.0),h2=Math.max(0.4,s.width2()/2.0);
        float r=((s.argb()>>>16)&255)/255F,g=((s.argb()>>>8)&255)/255F,b=(s.argb()&255)/255F;
        float a=Math.max(0F,Math.min(1F,((s.argb()>>>24)&255)/255F*alphaScale));
        float yLift=0.035F;
        vertex(out,pose,s.x1()+nx*h1,s.y1()+yLift,s.z1()+nz*h1,r,g,b,a);
        vertex(out,pose,s.x2()+nx*h2,s.y2()+yLift,s.z2()+nz*h2,r,g,b,a);
        vertex(out,pose,s.x2()-nx*h2,s.y2()+yLift,s.z2()-nz*h2,r,g,b,a);
        vertex(out,pose,s.x1()-nx*h1,s.y1()+yLift,s.z1()-nz*h1,r,g,b,a);
    }
    private static void vertex(VertexConsumer out,Matrix4fc pose,double x,double y,double z,float r,float g,float b,float a){
        out.addVertex(pose,(float)x,(float)y,(float)z).setColor(r,g,b,a);
    }

    private static void execute(Minecraft client,StagedVertexBuffer.ExecuteInfo info,RenderPipeline pipeline){
        GpuBufferSlice transforms=RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(),COLOR_MODULATOR,MODEL_OFFSET,TEXTURE_MATRIX);
        RenderTarget target=client.gameRenderer.mainRenderTarget();
        GpuTextureView color=target.getColorTextureView();
        if(color==null)return;
        try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Ocean Canvas Blueprint",color,Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms",transforms);
            pass.setVertexBuffer(0,info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(),info.indexType());
            pass.drawIndexed(info.indexCount(),1,info.firstIndex(),info.baseVertex(),0);
        }
    }


    /** Render-only 3D context for semantic footprints. Authored elevation profiles win over provisional semantic heights. */
    private static void addSemanticEnvelope(LevelExtractionContext context,Vec3 camera,List<Segment> out,
                                            OceanCanvasZoneClientCache.PlanningVector vector,List<int[]> points,double[] along,double groupOpacity){
        if(points.size()<2||out.size()>=MAX_VECTOR_SEGMENTS_PER_FRAME)return;
        boolean authored=hasElevationProfile(vector);
        double height=semanticEnvelopeHeight(vector.type());
        double[] cliff=semanticPair(vector,"CLIFF_Y");
        if(!authored&&height<=0D&&cliff==null)return;
        int argb=applyAlpha(vector.strokeArgb(),(float)(opacity*groupOpacity*0.42D));
        boolean closed=isClosed(vector.type());
        int step=Math.max(1,points.size()/48);
        for(int i=0;i<points.size()&&out.size()<MAX_VECTOR_SEGMENTS_PER_FRAME;i+=step){
            int[] a=points.get(i);
            double base=cliff!=null?cliff[0]:projectionY(context,a[0],a[1],camera.y)+0.12D;
            double top=cliff!=null?cliff[1]:(authored?elevationAt(vector.elevationProfile(),along[Math.min(i,along.length-1)],base):base+height);
            out.add(new Segment(a[0],base,a[1],a[0],top,a[1],1.15D,1.15D,argb));
        }
        int edgeCount=closed?points.size():points.size()-1;
        for(int i=0;i<edgeCount&&out.size()<MAX_VECTOR_SEGMENTS_PER_FRAME;i+=step){
            int[] a=points.get(i);
            int bi=Math.min(points.size()-1,i+step);
            if(closed)bi=(i+step)%points.size();
            int[] b=points.get(bi);
            double base1=cliff!=null?cliff[0]:projectionY(context,a[0],a[1],camera.y)+0.12D,base2=cliff!=null?cliff[0]:projectionY(context,b[0],b[1],camera.y)+0.12D;
            double t1=along[Math.min(i,along.length-1)],t2=closed&&bi==0&&i>0?1D:along[Math.min(bi,along.length-1)];
            double y1=cliff!=null?cliff[1]:(authored?elevationAt(vector.elevationProfile(),t1,base1):base1+height);
            double y2=cliff!=null?cliff[1]:(authored?elevationAt(vector.elevationProfile(),t2,base2):base2+height);
            out.add(new Segment(a[0],y1,a[1],b[0],y2,b[1],1.1D,1.1D,argb));
        }
    }

    private static void addPointTerrainPrimitive(LevelExtractionContext context,Vec3 camera,List<Segment> out,OceanCanvasZoneClientCache.PlanningVector vector,int[] point,double groupOpacity){
        if(out.size()>=MAX_VECTOR_SEGMENTS_PER_FRAME)return;
        double base=projectionY(context,point[0],point[1],camera.y)+0.12D;
        double[] peak=semanticPair(vector,"PEAK_Y");
        if(peak!=null){base=peak[0];}
        double top=peak!=null?peak[1]:(hasElevationProfile(vector)?elevationAt(vector.elevationProfile(),1D,base):base+semanticEnvelopeHeight(vector.type()));
        if(top<=base+0.01D&&semanticEnvelopeHeight(vector.type())<=0D)return;
        int argb=applyAlpha(vector.strokeArgb(),(float)(opacity*groupOpacity));
        out.add(new Segment(point[0],base,point[1],point[0],top,point[1],1.8D,1.8D,argb));
        double arm=Math.max(4D,Math.min(24D,Math.abs(top-base)*0.08D));
        out.add(new Segment(point[0]-arm,top,point[1],point[0]+arm,top,point[1],1.2D,1.2D,argb));
        out.add(new Segment(point[0],top,point[1]-arm,point[0],top,point[1]+arm,1.2D,1.2D,argb));
    }

    private static Double semanticSingle(OceanCanvasZoneClientCache.PlanningVector vector,String key){
        if(vector==null||vector.guideData()==null)return null;for(String token:vector.guideData().split(";")){if(!token.startsWith(key+"="))continue;try{return Double.parseDouble(token.substring(key.length()+1).split(",",-1)[0]);}catch(Exception ignored){return null;}}return null;
    }
    private static double[] semanticPair(OceanCanvasZoneClientCache.PlanningVector vector,String key){
        if(vector==null||vector.guideData()==null)return null;for(String token:vector.guideData().split(";")){if(!token.startsWith(key+"="))continue;String[] f=token.substring(key.length()+1).split(",",-1);if(f.length!=2)return null;try{return new double[]{Double.parseDouble(f[0]),Double.parseDouble(f[1])};}catch(Exception ignored){return null;}}return null;
    }

    private static double semanticEnvelopeHeight(String raw){
        return switch(raw==null?"":raw.toUpperCase(java.util.Locale.ROOT)){
            case "BUILD" -> 24D;
            case "LANDMARK","BRIDGE","PORT","HARBOR" -> 18D;
            case "SETTLEMENT","DISTRICT" -> 12D;
            case "PEAK" -> 120D;
            case "MOUNTAIN_RANGE","RIDGELINE" -> 80D;
            case "PLATEAU","CLIFF","TERRAIN_ZONE" -> 40D;
            case "FOREST" -> 10D;
            default -> 0D;
        };
    }

    /** Adds a translucent scanline footprint for closed Plan types while staying on the same QUADS pipeline. */
    private static void addAreaFillSegments(LevelExtractionContext context,Vec3 camera,List<Segment> out,OceanCanvasZoneClientCache.PlanningVector vector,List<int[]> points,int fillArgb,int strokeArgb,double groupOpacity){
        if(points.size()<3)return;
        int minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
        for(int[] p:points){minZ=Math.min(minZ,p[1]);maxZ=Math.max(maxZ,p[1]);}
        int spacing=Math.max(3,Math.min(16,(int)Math.round(4D+Math.sqrt(Math.max(0,distanceToBoundsSq(camera.x,camera.z,points)))/180D)));
        int source=((fillArgb>>>24)&255)==0?(strokeArgb&0x00FFFFFF)|0x55000000:fillArgb;
        int color=applyAlpha(source,(float)(opacity*groupOpacity*0.34D));
        for(int z=minZ;z<=maxZ;z+=spacing){
            List<Double> xs=new ArrayList<>();
            for(int i=0,j=points.size()-1;i<points.size();j=i++){
                int[] a=points.get(j),b=points.get(i);
                if((a[1]>z)!=(b[1]>z)){double x=a[0]+(z-a[1])*(b[0]-a[0])/(double)(b[1]-a[1]);xs.add(x);}
            }
            xs.sort(Double::compareTo);
            for(int i=0;i+1<xs.size();i+=2){
                double x1=xs.get(i),x2=xs.get(i+1);if(x2-x1<0.25D)continue;
                double projected=projectionY(context,(x1+x2)*0.5D,z,camera.y);
                Double semantic=semanticSingle(vector,"LAKE_Y");if(semantic==null)semantic=semanticSingle(vector,"SURFACE_Y");
                double baseY=semantic!=null?semantic:(hasElevationProfile(vector)?elevationAt(vector.elevationProfile(),0D,projected):projected);
                out.add(new Segment(x1,baseY,z,x2,baseY,z,spacing+0.35D,spacing+0.35D,color));
            }
        }
    }

    private static ArrayList<OceanCanvasZoneClientCache.PlanningVector> querySpatialVectors(double x,double z,double radius){
        rebuildSpatialIndexIfNeeded();
        int minCx=(int)Math.floor((x-radius)/SPATIAL_CELL_BLOCKS),maxCx=(int)Math.floor((x+radius)/SPATIAL_CELL_BLOCKS);
        int minCz=(int)Math.floor((z-radius)/SPATIAL_CELL_BLOCKS),maxCz=(int)Math.floor((z+radius)/SPATIAL_CELL_BLOCKS);
        LinkedHashMap<String,OceanCanvasZoneClientCache.PlanningVector> unique=new LinkedHashMap<>();
        Map<Long,List<OceanCanvasZoneClientCache.PlanningVector>> index=spatialVectors;
        for(int cx=minCx;cx<=maxCx;cx++)for(int cz=minCz;cz<=maxCz;cz++)for(var v:index.getOrDefault(cellKey(cx,cz),List.of()))unique.putIfAbsent(v.id(),v);
        return new ArrayList<>(unique.values());
    }
    private static synchronized void rebuildSpatialIndexIfNeeded(){
        long generation=OceanCanvasZoneClientCache.planningGeneration();if(generation==spatialGeneration)return;
        Map<Long,List<OceanCanvasZoneClientCache.PlanningVector>> next=new LinkedHashMap<>();
        for(var vector:OceanCanvasZoneClientCache.planningVectors()){
            CachedVectorGeometry g=vectorGeometry(vector);if(g.points().isEmpty())continue;
            double pad=Math.max(8D,vector.widthBlocks()*0.5D+8D);
            int minCx=(int)Math.floor((g.minX()-pad)/SPATIAL_CELL_BLOCKS),maxCx=(int)Math.floor((g.maxX()+pad)/SPATIAL_CELL_BLOCKS);
            int minCz=(int)Math.floor((g.minZ()-pad)/SPATIAL_CELL_BLOCKS),maxCz=(int)Math.floor((g.maxZ()+pad)/SPATIAL_CELL_BLOCKS);
            for(int cx=minCx;cx<=maxCx;cx++)for(int cz=minCz;cz<=maxCz;cz++)next.computeIfAbsent(cellKey(cx,cz),k->new ArrayList<>()).add(vector);
        }
        Map<Long,List<OceanCanvasZoneClientCache.PlanningVector>> frozen=new LinkedHashMap<>();for(var e:next.entrySet())frozen.put(e.getKey(),List.copyOf(e.getValue()));
        spatialVectors=Map.copyOf(frozen);spatialGeneration=generation;
    }
    private static long cellKey(int cx,int cz){return ((long)cx<<32)^(cz&0xffffffffL);}
    public static int spatialCellBlocks(){return SPATIAL_CELL_BLOCKS;}
    public static int spatialIndexedCells(){rebuildSpatialIndexIfNeeded();return spatialVectors.size();}

    private static void addViewpointMarkers(List<Segment> out,Vec3 camera,double maxDistanceSq){
        for(var v:OceanCanvasZoneClientCache.blueprintViewpoints()){
            if(out.size()+4>=MAX_VECTOR_SEGMENTS_PER_FRAME)break;
            double dx=v.x()-camera.x,dz=v.z()-camera.z;if(dx*dx+dz*dz>maxDistanceSq)continue;
            int c=applyAlpha(0xFF55D8FF,opacity);double y=v.y();double r=3.5D;
            out.add(new Segment(v.x()-r,y,v.z(),v.x()+r,y,v.z(),.55D,.55D,c));
            out.add(new Segment(v.x(),y,v.z()-r,v.x(),y,v.z()+r,.55D,.55D,c));
            out.add(new Segment(v.x(),y-2.5D,v.z(),v.x(),y+2.5D,v.z(),.55D,.55D,c));
            double rad=Math.toRadians(v.yaw());double fx=-Math.sin(rad)*7D,fz=Math.cos(rad)*7D;
            out.add(new Segment(v.x(),y,v.z(),v.x()+fx,y-Math.sin(Math.toRadians(v.pitch()))*4D,v.z()+fz,.8D,.8D,c));
        }
    }

    public static String captureViewpointPayload(){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null)return "";
        return String.format(java.util.Locale.ROOT,"%.3f,%.3f,%.3f,%.3f,%.3f,%s,%s,%.3f",mc.player.getX(),mc.player.getEyeY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot(),projectionMode.name(),depthMode.name(),opacity);
    }
    public static void applyViewpointState(OceanCanvasZoneClientCache.BlueprintViewpoint v){
        if(v==null)return;try{projectionMode=ProjectionMode.valueOf(v.projection());}catch(Exception ignored){}try{depthMode=DepthMode.valueOf(v.depth());}catch(Exception ignored){}setOpacity(v.opacity());enabled=true;
    }
    public record InspectHit(String objectId,String name,String type,double distanceBlocks,boolean reference) { }
    /** Finds the visible Plan object or registered reference under/nearest the in-world inspection point. */
    public static InspectHit inspectNearest(double x,double z){
        String activeScenario=OceanCanvasZoneClientCache.activeScenario();
        Map<String,OceanCanvasZoneClientCache.PlanGroup> byGroup=new LinkedHashMap<>();
        for(var group:OceanCanvasZoneClientCache.planGroups())byGroup.put(group.id(),group);
        InspectHit best=null;
        for(var vector:querySpatialVectors(x,z,64D)){
            if(!vector.visible()||vector.points()==null||vector.points().isBlank())continue;
            if(groupOpacity(vector.parentId(),byGroup,new java.util.HashSet<>())<=0.001D)continue;
            if(vector.scenarioId()!=null&&!vector.scenarioId().isBlank()&&!vector.scenarioId().equals(activeScenario))continue;
            List<int[]> raw=parsePoints(vector.points());if(raw.size()<2)continue;
            List<int[]> pts=vector.bezier()?bezierPoints(raw,parseBezierHandles(vector.guideData())):(vector.smooth()?smoothPoints(raw):raw);
            double d=isClosed(vector.type())&&pointInPolygon(x,z,pts)?0D:distanceToPath(x,z,pts,isClosed(vector.type()));
            double tolerance=Math.max(10D,vector.widthBlocks()*0.5D+6D);
            if(d>tolerance)continue;
            if(best==null||d<best.distanceBlocks())best=new InspectHit(vector.id(),vector.name(),vector.type(),d,false);
        }
        for(var ref:OceanCanvasZoneClientCache.planningReferences()){
            if(!ref.visible()||ref.opacity()<=0.01D)continue;
            try{
                double[] b=referenceWorldBounds(ref);
                if(x<b[0]||x>b[2]||z<b[1]||z>b[3])continue;
                double d=0D;
                if(best==null||d<=best.distanceBlocks())best=new InspectHit(ref.id(),ref.name(),"REFERENCE",d,true);
            }catch(Exception ignored){}
        }
        return best;
    }
    private static double distanceToPath(double x,double z,List<int[]> pts,boolean closed){
        double best=Double.POSITIVE_INFINITY;int n=closed?pts.size():pts.size()-1;
        for(int i=0;i<n;i++){int[] a=pts.get(i),b=pts.get((i+1)%pts.size());double dx=b[0]-a[0],dz=b[1]-a[1],den=dx*dx+dz*dz;double t=den<=1e-9?0:((x-a[0])*dx+(z-a[1])*dz)/den;t=Math.max(0,Math.min(1,t));best=Math.min(best,Math.hypot(x-(a[0]+dx*t),z-(a[1]+dz*t)));}
        return best;
    }
    private static boolean pointInPolygon(double x,double z,List<int[]> pts){
        return OceanCanvasRegionGeometry.pointInPolygonPairs(x,z,pts);
    }

    private static boolean hasElevationProfile(OceanCanvasZoneClientCache.PlanningVector vector){
        return vector!=null&&vector.elevationProfile()!=null&&!vector.elevationProfile().isBlank();
    }
    private static double authoredY(LevelExtractionContext context,OceanCanvasZoneClientCache.PlanningVector vector,double along,double x,double z,double playerY){
        double fallback=projectionY(context,x,z,playerY);
        return hasElevationProfile(vector)?elevationAt(vector.elevationProfile(),along,fallback):fallback;
    }
    private static double elevationAt(String packed,double along,double fallback){
        if(packed==null||packed.isBlank())return fallback;
        double prevA=0D,prevY=fallback;boolean have=false;
        for(String raw:packed.split(";")){String[] f=raw.split(",",-1);if(f.length!=2)continue;try{double a=Math.max(0D,Math.min(1D,Double.parseDouble(f[0]))),y=Double.parseDouble(f[1]);if(!have){prevA=a;prevY=y;have=true;if(along<=a)return y;continue;}if(along<=a){double span=Math.max(1.0e-9D,a-prevA),t=Math.max(0D,Math.min(1D,(along-prevA)/span));return prevY+(y-prevY)*t;}prevA=a;prevY=y;}catch(NumberFormatException ignored){}}
        return have?prevY:fallback;
    }
    private static double[] pathFractions(List<int[]> points,boolean closed){
        double[] out=new double[points.size()];if(points.size()<2)return out;double total=0D;double[] seg=new double[Math.max(1,points.size()-1)];
        for(int i=0;i<points.size()-1;i++){seg[i]=Math.hypot(points.get(i+1)[0]-points.get(i)[0],points.get(i+1)[1]-points.get(i)[1]);total+=seg[i];}
        if(closed)total+=Math.hypot(points.get(0)[0]-points.get(points.size()-1)[0],points.get(0)[1]-points.get(points.size()-1)[1]);
        if(total<=1.0e-9D){for(int i=0;i<out.length;i++)out[i]=i/(double)Math.max(1,out.length-1);return out;}
        double sum=0D;for(int i=1;i<out.length;i++){sum+=seg[i-1];out[i]=Math.min(1D,sum/total);}return out;
    }

    private static double projectionY(LevelExtractionContext context,double x,double z,double playerY){
        return switch(projectionMode){
            case FIXED_Y -> fixedY;
            case SEA_LEVEL -> context.level().getSeaLevel()+1.05D;
            case FLOATING -> playerY+floatingOffset;
            case SURFACE -> {
                int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
                if(!context.level().hasChunk(Math.floorDiv(bx,16),Math.floorDiv(bz,16)))yield context.level().getSeaLevel()+1.05D;
                yield context.level().getHeight(Heightmap.Types.WORLD_SURFACE,bx,bz)+1.05D;
            }
        };
    }

    private static void addOperationPreviewSegments(LevelExtractionContext context,Vec3 camera,List<Segment> out){
        if(!hasOperationPreview())return;
        int color=0xE6FFAA33;
        int minX=operationMinX,minZ=operationMinZ,maxX=operationMaxX,maxZ=operationMaxZ;
        int[][] p={{minX,minZ},{maxX,minZ},{maxX,maxZ},{minX,maxZ}};
        for(int i=0;i<4;i++){
            int[] a=p[i],b=p[(i+1)%4];
            out.add(new Segment(a[0],projectionY(context,a[0],a[1],camera.y),a[1],b[0],projectionY(context,b[0],b[1],camera.y),b[1],2.2,2.2,color));
        }
    }

    private static double effectiveRenderDistance(){
        return switch(performancePreset){
            case PERFORMANCE -> Math.min(renderDistance,384D);
            case BALANCED -> Math.min(renderDistance,768D);
            case LONG_RANGE -> Math.max(renderDistance,1536D);
            case CUSTOM -> renderDistance;
        };
    }
    private static List<int[]> lodPoints(List<int[]> points,double distance){
        int stride=distance>1200?8:(distance>700?4:(distance>350?2:1));
        if(stride<=1||points.size()<5)return points;
        List<int[]> out=new ArrayList<>();
        for(int i=0;i<points.size();i+=stride)out.add(points.get(i));
        int[] last=points.get(points.size()-1);
        if(out.get(out.size()-1)!=last)out.add(last);
        return out;
    }
    private static double distanceToBoundsSq(double x,double z,List<int[]> points){
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
        for(int[] p:points){minX=Math.min(minX,p[0]);maxX=Math.max(maxX,p[0]);minZ=Math.min(minZ,p[1]);maxZ=Math.max(maxZ,p[1]);}
        double dx=x<minX?minX-x:(x>maxX?x-maxX:0),dz=z<minZ?minZ-z:(z>maxZ?z-maxZ:0);
        return dx*dx+dz*dz;
    }
    private static double groupOpacity(String id,Map<String,OceanCanvasZoneClientCache.PlanGroup> groups,java.util.Set<String> seen){
        if(id==null||id.isBlank())return 1D;
        if(!seen.add(id))return 1D;
        var g=groups.get(id);if(g==null)return 1D;
        if(!g.visible())return 0D;
        return Math.max(0,Math.min(1,g.opacity()))*groupOpacity(g.parentId(),groups,seen);
    }
    private static int applyAlpha(int argb,float multiplier){
        int a=(argb>>>24)&255;if(a==0)a=255;
        a=Math.max(0,Math.min(255,Math.round(a*multiplier)));
        return (argb&0x00FFFFFF)|(a<<24);
    }
    private static boolean isClosed(String type){
        if(type==null)return false;
        return switch(type.toUpperCase(java.util.Locale.ROOT)){case "LANDMASS","CONTINENT","ISLAND","LAKE","CATCHMENT","FOREST","BIOME","SETTLEMENT","DISTRICT","BUILD","BORDER","AREA","MOUNTAIN","PLATEAU","BASIN","TERRAIN_ZONE" -> true;default -> false;};
    }
    private static double[] variableWidth(String guide,double fallback){
        double base=fallback>0?fallback:1D,a=base,b=base;
        if(guide!=null)for(String token:guide.split(";"))if(token.startsWith("VARWIDTH=")){
            String[] f=token.substring(9).split(",",-1);if(f.length==2)try{a=Math.max(0.8,Double.parseDouble(f[0]));b=Math.max(0.8,Double.parseDouble(f[1]));}catch(NumberFormatException ignored){}
        }
        return new double[]{a,b};
    }
    private static List<int[]> parsePoints(String packed){
        List<int[]> out=new ArrayList<>();if(packed==null||packed.isBlank())return out;
        for(String raw:packed.split(";")){String[] p=raw.split(",",-1);if(p.length!=2)continue;try{out.add(new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1])});}catch(NumberFormatException ignored){}}
        return out;
    }
    private static List<int[]> smoothPoints(List<int[]> pts){
        if(pts.size()<3)return pts;List<int[]> out=new ArrayList<>();
        for(int i=0;i<pts.size()-1;i++){int[] p0=pts.get(Math.max(0,i-1)),p1=pts.get(i),p2=pts.get(i+1),p3=pts.get(Math.min(pts.size()-1,i+2));for(int step=0;step<12;step++){double u=step/12.0,u2=u*u,u3=u2*u;double x=0.5*((2*p1[0])+(-p0[0]+p2[0])*u+(2*p0[0]-5*p1[0]+4*p2[0]-p3[0])*u2+(-p0[0]+3*p1[0]-3*p2[0]+p3[0])*u3);double z=0.5*((2*p1[1])+(-p0[1]+p2[1])*u+(2*p0[1]-5*p1[1]+4*p2[1]-p3[1])*u2+(-p0[1]+3*p1[1]-3*p2[1]+p3[1])*u3);out.add(new int[]{(int)Math.round(x),(int)Math.round(z)});}}
        out.add(pts.get(pts.size()-1));return out;
    }
    private static Map<Integer,int[]> parseBezierHandles(String guide){
        Map<Integer,int[]> out=new LinkedHashMap<>();if(guide==null)return out;
        for(String token:guide.split(";")){if(!token.startsWith("BEZIER="))continue;for(String raw:token.substring(7).split("/")){String[] f=raw.split(",",-1);if(f.length!=5)continue;try{out.put(Integer.parseInt(f[0]),new int[]{Integer.parseInt(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4])});}catch(NumberFormatException ignored){}}}
        return out;
    }
    private static List<int[]> bezierPoints(List<int[]> pts,Map<Integer,int[]> handles){
        if(pts.size()<2||handles.isEmpty())return pts;List<int[]> out=new ArrayList<>();
        for(int i=0;i<pts.size()-1;i++){int[] a=pts.get(i),b=pts.get(i+1),ha=handles.getOrDefault(i,new int[4]),hb=handles.getOrDefault(i+1,new int[4]);double c1x=a[0]+ha[2],c1z=a[1]+ha[3],c2x=b[0]+hb[0],c2z=b[1]+hb[1];for(int step=0;step<16;step++){double t=step/16.0,u=1-t;double x=u*u*u*a[0]+3*u*u*t*c1x+3*u*t*t*c2x+t*t*t*b[0];double z=u*u*u*a[1]+3*u*u*t*c1z+3*u*t*t*c2z+t*t*t*b[1];out.add(new int[]{(int)Math.round(x),(int)Math.round(z)});}}
        out.add(pts.get(pts.size()-1));return out;
    }

    /** Client-only safety preview. This never changes terrain or server state. */
    public static void previewOperation(String kind,int minX,int minZ,int maxX,int maxZ,List<Long> chunks){operationPreviewKind=kind==null?"":kind;operationMinX=minX;operationMinZ=minZ;operationMaxX=maxX;operationMaxZ=maxZ;operationChunks=chunks==null?List.of():List.copyOf(chunks);enabled=true;}
    public static void clearOperationPreview(){operationPreviewKind="";operationChunks=List.of();}
    public static boolean hasOperationPreview(){return !operationPreviewKind.isBlank();}
    public static String operationPreviewKind(){return operationPreviewKind;}
    public record OperationPreview(String kind,int minX,int minZ,int maxX,int maxZ,List<Long> chunks) { }
    public static OperationPreview operationPreview(){return hasOperationPreview()?new OperationPreview(operationPreviewKind,operationMinX,operationMinZ,operationMaxX,operationMaxZ,List.copyOf(operationChunks)):null;}

    public static boolean enabled(){return enabled;}
    public static boolean comparisonHidden(){return comparisonHidden;}
    public static void setComparisonHidden(boolean value){comparisonHidden=value;}
    public static void toggle(){enabled=!enabled;}
    public static void setEnabled(boolean value){enabled=value;}
    public static ProjectionMode projectionMode(){return projectionMode;}
    public static void cycleProjection(){ProjectionMode[] v=ProjectionMode.values();projectionMode=v[(projectionMode.ordinal()+1)%v.length];}
    public static void setProjectionMode(ProjectionMode value){if(value!=null)projectionMode=value;}
    public static DepthMode depthMode(){return depthMode;}
    public static void cycleDepthMode(){DepthMode[] v=DepthMode.values();depthMode=v[(depthMode.ordinal()+1)%v.length];}
    public static void setDepthMode(DepthMode value){if(value!=null)depthMode=value;}
    public static PerformancePreset performancePreset(){return performancePreset;}
    public static void cyclePerformancePreset(){PerformancePreset[] v=PerformancePreset.values();performancePreset=v[(performancePreset.ordinal()+1)%v.length];}
    public static void setFixedY(int value){fixedY=Math.max(-2048,Math.min(4096,value));}
    public static void setRenderDistance(double value){renderDistance=Math.max(64,Math.min(4096,value));performancePreset=PerformancePreset.CUSTOM;}
    public static void setOpacity(float value){opacity=Math.max(0.05F,Math.min(1F,value));}
    public static float opacity(){return opacity;}
}
