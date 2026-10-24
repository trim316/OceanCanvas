package net.oceancanvas.mod.project;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;
import net.oceancanvas.mod.project.OceanCanvasPhysicalHealth.Cell;
import net.oceancanvas.mod.project.OceanCanvasPhysicalHealth.Report;
import net.oceancanvas.mod.project.OceanCanvasPhysicalHealth.Status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Read-only, server-thread sampling. Never adds tickets, generates chunks or writes any ledger. */
public final class OceanCanvasPhysicalHealthScanner {
    private static final LinkedHashMap<UUID, Job> REPORTS = new LinkedHashMap<>();
    private static Job active;
    private static final long TTL_NANOS = 10L * 60 * 1_000_000_000;
    private OceanCanvasPhysicalHealthScanner() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { active=null; REPORTS.clear(); });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now=System.nanoTime();
            REPORTS.values().removeIf(j -> j!=active && now-j.touched>TTL_NANOS);
            Job job=active;
            if (job==null || job.world.getServer()!=server) return;
            ServerPlayer player=server.getPlayerList().getPlayer(job.owner);
            if (player==null || player.level()!=job.world) {
                job.state="CANCELLED"; job.message="Stopped on disconnect or dimension change; partial results retained.";
                active=null; return;
            }
            if (OceanCanvasConfig.get()!=job.config) {
                job.state="CANCELLED";job.message="Settings changed during the scan. Partial results retained; rescan against the new settings.";
                active=null;return;
            }
            if (busy()) {
                // Never combine pre-Rewipe/Restore observations with post-operation ones.
                job.cells.clear(); job.state="PAUSED";
                job.message="Terrain job active: old samples cleared. Scan restarts after the job finishes."; return;
            }
            job.state="RUNNING"; job.message="Loaded chunks only; samples are observations, not a full integrity guarantee.";
            long deadline=now+2_000_000;
            // Soft 2 ms budget checked between chunks, hard maximum two chunks/tick.
            // Each chunk reads at most 16 columns with seven block and two biome samples each.
            for (int n=0;n<2 && job.cells.size()<job.keys.size();n++) {
                long key=job.keys.get(job.cells.size());
                try { job.cells.add(sample(job.world,key)); }
                catch (RuntimeException ex) {
                    OceanCanvas.LOGGER.warn("OC-H101: physical Health read failed at {},{}",
                            OceanCanvasPhysicalHealth.x(key),OceanCanvasPhysicalHealth.z(key),ex);
                    job.cells.add(new Cell(OceanCanvasPhysicalHealth.x(key),OceanCanvasPhysicalHealth.z(key),
                            Status.ERROR,"OC-H101: read failed. No repair attempted; inspect latest.log."));
                    job.state="FAILED"; job.message="Read failure stopped this scan; partial results retained.";
                    active=null; break;
                }
                if (System.nanoTime()>=deadline) break;
            }
            if (job.cells.size()==job.keys.size() && active==job) {
                job.state="COMPLETE"; job.message="Finished. Differences can be builds, preserved terrain or changed rules; no repairs performed.";
                active=null;
            }
            job.touched=System.nanoTime();
        });
    }

    private static boolean busy() {
        return OceanCanvasTerrainOperationActivity.pregenRunning()
                || OceanCanvasTerrainOperationActivity.restoreRunning()
                || OceanCanvasTerrainOperationActivity.undoRunning()
                || OceanCanvasTerrainOperationActivity.pendingRegenerationCount() > 0;
    }

    /** Requests are validated before expanding a rectangle or copying a region's exact mask. */
    public static String start(ServerPlayer player, String action) {
        if (!player.level().dimension().equals(Level.OVERWORLD)) return "Physical Canvas Health currently supports the Overworld only.";
        if (active!=null) return "A physical Health scan is already active. Finish it or have its owner cancel it.";
        Job existing=REPORTS.get(player.getUUID());
        if(existing!=null && "PAUSED".equals(existing.state) && existing.world==player.level())
            return "You have a paused physical Health scan. Resume or cancel it before starting a new selection.";
        if (busy()) return "Finish terrain jobs before starting physical Health.";
        try {
            List<Long> keys; String label;
            if (action.startsWith("physical_region:")) {
                String name=action.substring("physical_region:".length());
                var zone=OceanCanvasPlayerZones.get(player.level()).zoneByName(name);
                if (zone==null) return "That region no longer exists. Select it again.";
                if (zone.hasExplicitShape()) {
                    if (zone.chunks().size()>OceanCanvasPhysicalHealth.MAX_CHUNKS)
                        return "Region exceeds 1,024 chunks. Use a smaller rectangle or region.";
                    keys=zone.chunks().stream().sorted().toList();
                } else {
                    var b=zone.bounds();
                    keys=OceanCanvasPhysicalHealth.blocks(b.minX(),b.minZ(),b.maxX(),b.maxZ()).keys();
                }
                label="Region: "+name;
            } else if (action.startsWith("physical_rect:")) {
                String[] f=action.substring("physical_rect:".length()).split(",",-1);
                if (f.length!=4) return "Enter four whole-number block coordinates.";
                keys=OceanCanvasPhysicalHealth.blocks(Integer.parseInt(f[0]),Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]),Integer.parseInt(f[3])).keys(); label="Coordinate rectangle (whole chunks)";
            } else if ("physical_here".equals(action) || "physical_nearby".equals(action)) {
                int r="physical_here".equals(action)?0:4;
                int cx=Math.floorDiv(player.blockPosition().getX(),16),cz=Math.floorDiv(player.blockPosition().getZ(),16);
                keys=new OceanCanvasPhysicalHealth.Bounds(Math.max(-1875000,cx-r),Math.max(-1875000,cz-r),
                        Math.min(1875000,cx+r),Math.min(1875000,cz+r)).keys();
                label=r==0?"Current chunk":"Nearby 9 x 9 chunks";
            } else return "Unknown physical Health action.";
            if (keys.isEmpty()) return "No chunks in this selection.";
            for(long key:keys) {
                int x=OceanCanvasPhysicalHealth.x(key),z=OceanCanvasPhysicalHealth.z(key);
                if(x < -1875000 || x > 1875000 || z < -1875000 || z > 1875000)
                    return "Region contains out-of-range chunks; inspect its shape before scanning.";
            }
            REPORTS.remove(player.getUUID());
            while (REPORTS.size()>=8) REPORTS.remove(REPORTS.keySet().iterator().next());
            Job job=new Job(player.level(),player.getUUID(),keys,label);
            REPORTS.put(player.getUUID(),job); active=job;
            return "Physical Health started: "+keys.size()+" chunks. This does not load or generate terrain.";
        } catch (IllegalArgumentException ex) { return ex.getMessage()==null?"Invalid scan bounds.":ex.getMessage(); }
    }

    public static Report report(ServerPlayer player, int page) {
        Job job=REPORTS.get(player.getUUID());
        if (job==null || job.world!=player.level()) return new Report("IDLE",0,0,"","No physical scan in this dimension.",List.of());
        job.touched=System.nanoTime();
        return new Report(job.state,job.keys.size(),page,job.label,job.message,List.copyOf(job.cells));
    }

    public static void cancel(ServerPlayer player) {
        Job job=REPORTS.get(player.getUUID());
        if(job!=null && job.world==player.level()) {
            job.state="CANCELLED"; job.message="Cancelled by you. Partial results retained; no terrain changed.";
            job.touched=System.nanoTime(); if(active==job)active=null;
        }
    }

    /** Pause is local session control: it retains sampled evidence but does not survive a restart. */
    public static String pause(ServerPlayer player) {
        if(active==null || !active.owner.equals(player.getUUID()) || active.world!=player.level())
            return "You do not have an active physical Health scan to pause.";
        active.state="PAUSED";
        active.message="Paused after "+active.cells.size()+" of "+active.keys.size()+" chunks. Resume continues at the next unchecked chunk.";
        active.touched=System.nanoTime(); active=null;
        return activeMessage(player,"Physical Health scan paused.");
    }

    /** Resume is explicitly requested; it never resumes on reconnect/restart or while terrain work is active. */
    public static String resume(ServerPlayer player) {
        if(active!=null) return "A physical Health scan is already active.";
        Job job=REPORTS.get(player.getUUID());
        if(job==null || job.world!=player.level() || !"PAUSED".equals(job.state))
            return "You do not have a paused physical Health scan to resume.";
        if(OceanCanvasConfig.get()!=job.config) return "Settings changed since this scan began. Cancel it and start a new scan.";
        if(busy()) return "Finish terrain jobs before resuming physical Health.";
        job.state="RUNNING";job.message="Resumed at "+job.cells.size()+" of "+job.keys.size()+" chunks; completed samples were retained.";
        job.touched=System.nanoTime();active=job;
        return activeMessage(player,"Physical Health scan resumed.");
    }

    private static String activeMessage(ServerPlayer player,String prefix) {
        Job job=REPORTS.get(player.getUUID());
        return job==null?prefix:prefix+" "+job.cells.size()+" / "+job.keys.size()+" chunks sampled.";
    }

    private static Cell sample(ServerLevel world,long key) {
        int cx=OceanCanvasPhysicalHealth.x(key),cz=OceanCanvasPhysicalHealth.z(key);
        LevelChunk chunk=world.getChunkSource().getChunkNow(cx,cz);
        if (chunk==null) return new Cell(cx,cz,Status.UNLOADED,"OC-H001: not loaded. Not generated or checked; visit this area and rescan.");
        var config=OceanCanvasConfig.get(); var zones=OceanCanvasPlayerZones.get(world);
        var protection=OceanCanvasProtectedData.get(world);
        String intent=OceanCanvasTerrainStateData.get(world).get(key).name();
        var protectedBuild=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).protectedBuild(key);
        int starts=chunk.getAllStarts().size(),refs=chunk.getAllReferences().size();
        boolean structureContext=starts>0 || refs>0;
        int checked=0,skipped=0,differences=0,biomes=0,biomeDiff=0,inside=0;
        String first="";
        int waterTop=OceanCanvasConfig.WATER_SURFACE_Y;
        for (int dx=0;dx<16;dx+=4) for (int dz=0;dz<16;dz+=4) {
            int x=cx*16+dx,z=cz*16+dz;
            // A tapered floor depends on the pre-carve height, which this scanner cannot reconstruct.
            if (config.canvasZone(x,z)!=OceanCanvasConfig.CanvasZone.INSIDE) { skipped++; continue; }
            inside++;
            int floor=config.oceanFloorY()+net.oceancanvas.mod.worldgen.OceanCanvasFloorProfile.floorOffset(x,z,config.oceanFloorVariation());
            int top=Math.max(waterTop+1,chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z));
            int[] ys={floor-config.oceanFloorTransitionThickness(),floor-1,floor,(floor+waterTop)/2,waterTop,waterTop+1,top};
            for (int y:ys) {
                int section=chunk.getSectionIndex(y);
                if (section<0 || section>=chunk.getSections().length || structureContext
                        || zones.isProtected(x,y,z) || protection.isProtected(x,y,z)
                        || (y<floor && config.oceanFloorTransitionThickness()==0)) { skipped++; continue; }
                BlockPos pos=new BlockPos(x,y,z);
                BlockState block=chunk.getBlockState(pos);
                var fluid=chunk.getFluidState(pos);
                boolean expectedWater=y>=floor&&y<=waterTop;
                boolean fluidMatches=expectedWater ? (!fluid.isEmpty() && block.getFluidState().equals(fluid)) : fluid.isEmpty();
                boolean matches=y>waterTop?block.isAir():y>=floor?waterOrPlant(block):block.is(Blocks.STONE);
                checked++;
                if (!matches || !fluidMatches) { differences++; if (first.isEmpty()) first=(!fluidMatches?"first fluid-state difference at ":"first block difference at ")+x+","+y+","+z; }
            }
            String expected=zones.biomeOverrideAt(x,waterTop,z);
            if (expected==null && config.biomeMaskEnabled()) expected=config.biomeMaskBiome();
            if (expected!=null) for (int y:new int[]{floor,waterTop}) {
                int section=chunk.getSectionIndex(y);
                if (section<0 || section>=chunk.getSections().length) { skipped++; continue; }
                int colon=expected.indexOf(':');
                if (colon<1) { skipped++; continue; }
                var biomeKey=ResourceKey.<Biome>create(Registries.BIOME,
                        Identifier.fromNamespaceAndPath(expected.substring(0,colon),expected.substring(colon+1)));
                var holder=world.registryAccess().lookupOrThrow(Registries.BIOME).get(biomeKey);
                if (holder.isEmpty()) { skipped++; continue; }
                var actual=chunk.getSection(section).getBiomes().get((x>>2)&3,(y>>2)&3,(z>>2)&3);
                biomes++;
                if (!actual.equals(holder.get())) { biomeDiff++; if (first.isEmpty()) first="first biome difference at "+x+","+y+","+z; }
            }
        }
        if (inside==0) return new Cell(cx,cz,Status.EXCLUDED,"OC-H002: outside the interior canvas, or tapered edge; no flat-floor assumption applied.");
        Status status=OceanCanvasPhysicalHealth.classify(intent,checked,skipped,differences+biomeDiff);
        String detail=(protectedBuild!=null?"PROTECTED_BUILD: "+protectedBuild.label()+" · project "+protectedBuild.projectId()+" · ":intent+": ")+"blocks "+checked+" (diff "+differences+"), biomes "+biomes+" (diff "+biomeDiff+"); skipped "+skipped
                +"; starts/ref-types "+starts+"/"+refs+". "+first;
        if (structureContext) detail+=" Structure context: blocks not judged.";
        return new Cell(cx,cz,status,detail);
    }


    public static String protectCurrentBuild(ServerPlayer player,String projectId){
        int cx=Math.floorDiv(player.blockPosition().getX(),16),cz=Math.floorDiv(player.blockPosition().getZ(),16);
        long key=OceanCanvasPhysicalHealth.pack(cx,cz);
        net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(player.level()).protectBuild(key,projectId,"Authored build");
        return "Protected-build provenance recorded for chunk "+cx+", "+cz+". This marker can exclude the chunk from future destructive preflight.";
    }
    public static String unprotectCurrentBuild(ServerPlayer player){
        int cx=Math.floorDiv(player.blockPosition().getX(),16),cz=Math.floorDiv(player.blockPosition().getZ(),16);
        boolean removed=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(player.level()).unprotectBuild(OceanCanvasPhysicalHealth.pack(cx,cz));
        return removed?"Removed protected-build provenance from chunk "+cx+", "+cz+".":"This chunk has no protected-build provenance marker.";
    }

    private static boolean waterOrPlant(BlockState block) {
        return block.is(Blocks.WATER) || block.is(Blocks.SEAGRASS) || block.is(Blocks.TALL_SEAGRASS)
                || block.is(Blocks.KELP) || block.is(Blocks.KELP_PLANT);
    }

    private static final class Job {
        final ServerLevel world; final UUID owner; final List<Long> keys; final String label;
        final OceanCanvasConfig config=OceanCanvasConfig.get();
        final List<Cell> cells=new ArrayList<>();
        String state="RUNNING",message="Waiting for the next server tick.";
        long touched=System.nanoTime();
        Job(ServerLevel world,UUID owner,List<Long> keys,String label) {
            this.world=world; this.owner=owner; this.keys=List.copyOf(keys); this.label=label;
        }
    }
}
