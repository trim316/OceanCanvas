package net.oceancanvas.mod.project;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OC-F119: cheap physical canary samples on the one-chunk ring immediately outside a destructive scope.
 * No canary call force-loads or generates a chunk. Unloaded samples are explicit UNVERIFIED coverage.
 */
public final class OceanCanvasBoundaryCanaryService {
    private static final int MAX_SAMPLES=128;
    private OceanCanvasBoundaryCanaryService() {}

    public static OceanCanvasBoundaryCanaryData.Baseline captureChunks(ServerLevel world,String kind,String scopeId,Set<Long> affected){
        LinkedHashSet<Long> ring=new LinkedHashSet<>();
        if(affected!=null)for(long p:affected){
            int x=ChunkPos.getX(p),z=ChunkPos.getZ(p);
            addIfOutside(ring,affected,x-1,z);addIfOutside(ring,affected,x+1,z);addIfOutside(ring,affected,x,z-1);addIfOutside(ring,affected,x,z+1);
        }
        return captureRing(world,kind,scopeId,ring);
    }

    public static OceanCanvasBoundaryCanaryData.Baseline captureRect(ServerLevel world,String kind,String scopeId,
            int minX,int minZ,int maxX,int maxZ){
        LinkedHashSet<Long> ring=new LinkedHashSet<>();
        for(int x=minX;x<=maxX;x++){ring.add(ChunkPos.pack(x,minZ-1));ring.add(ChunkPos.pack(x,maxZ+1));}
        for(int z=minZ;z<=maxZ;z++){ring.add(ChunkPos.pack(minX-1,z));ring.add(ChunkPos.pack(maxX+1,z));}
        return captureRing(world,kind,scopeId,ring);
    }

    /** Annulus/Expand scope: outer outside plus the inner edge inside the excluded old rectangle. */
    public static OceanCanvasBoundaryCanaryData.Baseline captureRingRect(ServerLevel world,String kind,String scopeId,
            int minX,int minZ,int maxX,int maxZ,int exMinX,int exMinZ,int exMaxX,int exMaxZ){
        LinkedHashSet<Long> ring=new LinkedHashSet<>();
        for(int x=minX;x<=maxX;x++){ring.add(ChunkPos.pack(x,minZ-1));ring.add(ChunkPos.pack(x,maxZ+1));}
        for(int z=minZ;z<=maxZ;z++){ring.add(ChunkPos.pack(minX-1,z));ring.add(ChunkPos.pack(maxX+1,z));}
        if(exMinX<=exMaxX&&exMinZ<=exMaxZ){
            for(int x=exMinX;x<=exMaxX;x++){ring.add(ChunkPos.pack(x,exMinZ));ring.add(ChunkPos.pack(x,exMaxZ));}
            for(int z=exMinZ;z<=exMaxZ;z++){ring.add(ChunkPos.pack(exMinX,z));ring.add(ChunkPos.pack(exMaxX,z));}
        }
        return captureRing(world,kind,scopeId,ring);
    }

    private static void addIfOutside(Set<Long> out,Set<Long> affected,int x,int z){long p=ChunkPos.pack(x,z);if(!affected.contains(p))out.add(p);}

    private static OceanCanvasBoundaryCanaryData.Baseline captureRing(ServerLevel world,String kind,String scopeId,Set<Long> ring){
        List<Long> chosen=sampleKeys(ring,MAX_SAMPLES);List<OceanCanvasBoundaryCanaryData.Sample> samples=new ArrayList<>();int skipped=0;
        for(long p:chosen){LevelChunk chunk=world.getChunkSource().getChunkNow(ChunkPos.getX(p),ChunkPos.getZ(p));if(chunk==null){skipped++;continue;}samples.add(new OceanCanvasBoundaryCanaryData.Sample(p,fingerprint(world,chunk)));}
        var baseline=new OceanCanvasBoundaryCanaryData.Baseline(kind,scopeId,System.currentTimeMillis(),ring.size(),samples.size(),skipped,samples);
        OceanCanvasBoundaryCanaryData.get(world).setActive(baseline);return baseline;
    }

    public static OceanCanvasBoundaryCanaryData.Result verify(ServerLevel world,String terminalPhase){
        var data=OceanCanvasBoundaryCanaryData.get(world);var base=data.active();
        if(base==null)return null;
        int checked=0,changed=0,skipped=0;
        for(var s:base.samples()){
            LevelChunk chunk=world.getChunkSource().getChunkNow(ChunkPos.getX(s.chunkKey()),ChunkPos.getZ(s.chunkKey()));
            if(chunk==null){skipped++;continue;}checked++;if(fingerprint(world,chunk)!=s.fingerprint())changed++;
        }
        String state=changed>0?"FAIL":(checked==0?"UNVERIFIED":(skipped>0||base.skippedUnloaded()>0?"PARTIAL_PASS":"PASS"));
        String detail="Boundary canary "+state+" after "+safe(terminalPhase)+": "+checked+" sampled ring chunk(s) rechecked, "+changed+" changed, "+(skipped+base.skippedUnloaded())+" unavailable across before/after; ring candidates="+base.ringCandidates()+".";
        var result=new OceanCanvasBoundaryCanaryData.Result(System.currentTimeMillis(),base.kind(),base.scopeId(),state,checked,changed,skipped+base.skippedUnloaded(),detail);
        data.record(result);return result;
    }

    private static List<Long> sampleKeys(Set<Long> ring,int max){
        var all=new ArrayList<>(ring);all.sort(Comparator.naturalOrder());if(all.size()<=max)return all;
        var out=new ArrayList<Long>(max);double step=all.size()/(double)max;for(int i=0;i<max;i++)out.add(all.get(Math.min(all.size()-1,(int)Math.floor(i*step))));return out;
    }

    /** Sparse block-state fingerprint; lighting cannot change it. */
    private static long fingerprint(ServerLevel world,LevelChunk chunk){
        long h=0xcbf29ce484222325L;int cx=chunk.getPos().x(),cz=chunk.getPos().z();int min=world.getMinY(),max=world.getMaxY();
        int[] local={0,8,15};
        for(int y=min;y<max;y+=8)for(int lx:local)for(int lz:local){
            String state=chunk.getBlockState(new BlockPos(cx*16+lx,y,cz*16+lz)).toString();h^=state.hashCode();h*=0x100000001b3L;h^=((long)y<<32)^(lx<<8)^lz;h*=0x100000001b3L;
        }
        return h;
    }
    private static String safe(String s){return s==null?"":s;}
}
