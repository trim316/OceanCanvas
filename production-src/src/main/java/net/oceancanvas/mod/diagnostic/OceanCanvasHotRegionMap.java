package net.oceancanvas.mod.diagnostic;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OC-F220 hot-region map.
 *
 * <p>Aggregates Pregen target latency into 32x32-chunk cells. This is intentionally metadata-only:
 * admission and retirement hooks pass packed chunk coordinates and timestamps; the map never loads
 * or scans a chunk. Cells are bounded with LRU eviction so a forever world cannot grow diagnostics
 * without limit.</p>
 */
public final class OceanCanvasHotRegionMap {
    private static final int CELL_SHIFT=5; // 32 chunks = 512 blocks per cell
    private static final int MAX_CELLS=2048;
    private static final int MAX_ACTIVE=512;
    private static final LinkedHashMap<Long,Long> ACTIVE=new LinkedHashMap<>(64,0.75f,true);
    private static final LinkedHashMap<Long,MutableCell> CELLS=new LinkedHashMap<>(128,0.75f,true);
    private OceanCanvasHotRegionMap(){}

    public record HotCell(int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ,long completed,
                          long active,long deferred,double averageLatencyMs,long maxLatencyMs,double heatScore){ }

    public static synchronized void onAdmission(long packed,long nowMillis){
        ACTIVE.put(packed,Math.max(0L,nowMillis));
        trim(ACTIVE,MAX_ACTIVE);
        cellFor(packed).active++;
    }

    public static synchronized void onRetirement(long packed,long nowMillis){
        Long started=ACTIVE.remove(packed);
        MutableCell c=cellFor(packed);
        if(c.active>0)c.active--;
        c.completed++;
        if(started!=null){
            long latency=Math.max(0L,nowMillis-started);
            c.totalLatencyMs+=latency;
            c.maxLatencyMs=Math.max(c.maxLatencyMs,latency);
        }
    }

    public static synchronized void onDeferral(long packed){
        MutableCell c=cellFor(packed);c.deferred++;
        if(ACTIVE.remove(packed)!=null&&c.active>0)c.active--;
    }

    public static synchronized List<HotCell> hottest(int limit){
        int safe=Math.max(1,Math.min(64,limit));
        ArrayList<HotCell> out=new ArrayList<>();
        for(var e:CELLS.entrySet()){
            int cx=ChunkPos.getX(e.getKey()),cz=ChunkPos.getZ(e.getKey());
            MutableCell c=e.getValue();
            double avg=c.completed==0?0.0D:c.totalLatencyMs/(double)c.completed;
            // Deferred/active debt dominates; historical latency is secondary.
            double score=c.active*20.0D+c.deferred*10.0D+Math.min(120.0D,avg/250.0D)+Math.min(120.0D,c.maxLatencyMs/1000.0D);
            int minX=cx<<CELL_SHIFT,minZ=cz<<CELL_SHIFT;
            out.add(new HotCell(minX,minZ,minX+(1<<CELL_SHIFT)-1,minZ+(1<<CELL_SHIFT)-1,
                    c.completed,c.active,c.deferred,avg,c.maxLatencyMs,score));
        }
        out.sort(Comparator.comparingDouble(HotCell::heatScore).reversed());
        if(out.size()>safe)out.subList(safe,out.size()).clear();
        return List.copyOf(out);
    }

    public static synchronized void clear(){ACTIVE.clear();CELLS.clear();}

    private static MutableCell cellFor(long packed){
        int cellX=ChunkPos.getX(packed)>>CELL_SHIFT,cellZ=ChunkPos.getZ(packed)>>CELL_SHIFT;
        long key=ChunkPos.pack(cellX,cellZ);
        MutableCell c=CELLS.get(key);
        if(c==null){c=new MutableCell();CELLS.put(key,c);trim(CELLS,MAX_CELLS);}
        return c;
    }
    private static <K,V> void trim(LinkedHashMap<K,V> map,int max){while(map.size()>max){var it=map.entrySet().iterator();if(!it.hasNext())break;it.next();it.remove();}}
    private static final class MutableCell {long completed,active,deferred,totalLatencyMs,maxLatencyMs;}
}
