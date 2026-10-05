package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** OC-F208 read-only inventory of Ocean Canvas-owned tickets and logical leases. */
public final class OceanCanvasTicketOwnershipInspector {
    private OceanCanvasTicketOwnershipInspector() {}
    public record Entry(String pool,int chunkX,int chunkZ,long ageMillis,String owner,String purpose,String releaseCondition) {}
    public record Snapshot(int total,long oldestMillis,List<Entry> entries) {
        public String summary(){return total==0?"No Ocean Canvas tickets/leases are currently owned.":total+" Ocean Canvas ticket/lease owner(s); oldest "+oldestMillis+" ms.";}
    }
    public static Snapshot snapshot(ServerLevel world,int limit){
        ArrayList<Entry> all=new ArrayList<>();
        for(var e:OceanCanvasTerrainRuntimeDiagnostics.ticketOwnershipSnapshot(world))all.add(new Entry(e.pool(),e.chunkX(),e.chunkZ(),e.ageMillis(),e.owner(),e.purpose(),e.releaseCondition()));
        all.sort(Comparator.comparingLong(Entry::ageMillis).reversed().thenComparing(Entry::pool));long oldest=all.stream().mapToLong(Entry::ageMillis).max().orElse(0L);
        int n=Math.max(0,Math.min(Math.max(1,limit),all.size()));return new Snapshot(all.size(),oldest,List.copyOf(all.subList(0,n)));
    }
}
