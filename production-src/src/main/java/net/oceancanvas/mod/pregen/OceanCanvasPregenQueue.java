package net.oceancanvas.mod.pregen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.project.OceanCanvasProjectData;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Singleplayer-oriented Pregen-only queue; Run authorization never survives a server restart. */
public final class OceanCanvasPregenQueue {
    private static boolean armed;
    private static int ticks;
    private static String message="Queue paused. Review entries, then Run queue to begin.";
    private OceanCanvasPregenQueue(){}

    public static void register(){
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{armed=false;ticks=0;message="Queue paused after restart; review and Run to resume.";});
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(++ticks%20!=0)return;
            ServerLevel world=server.overworld();if(world==null)return;
            var data=OceanCanvasPregenQueueData.get(world);var model=data.model();
            if(model.readOnly()){armed=false;message="Queue schema/content is incompatible. Read-only; no jobs dispatched.";return;}
            var head=model.head();
            if(head==null){armed=false;return;}
            if(head.state().equals("RUNNING")){
                if(PregenManager.isQueuedJob(head.id()))return;
                model.state(head.id(),"INTERRUPTED","Previous run stopped. Run queue replays this Pregen region safely from its beginning.");
                data.changed();armed=false;message="Interrupted queue entry retained. Review before resuming.";return;
            }
            if(!armed)return;
            if(busy()){message="Queue waiting for the current terrain operation and owned targets to drain.";return;}
            if(!OceanCanvasConfig.get().pregenEnabled()){block(world,head.id(),"Pregen is disabled. Enable it in Settings, then Run queue.");return;}
            try{
                var snapshot=OceanCanvasJobState.get(world).get();
                if(snapshot!=null&&!snapshot.queueEntryId().equals(head.id())){
                    block(world,head.id(),"A different saved job awaits recovery; it was not overwritten.");return;
                }
                ServerPlayer owner=server.getPlayerList().getPlayer(UUID.fromString(head.owner()));
                if(owner==null||owner.level()!=world||!Permissions.require("oceancanvas.protect",2).test(owner.createCommandSourceStack())){
                    block(world,head.id(),"Requester must be online in the Overworld and still have permission. Run again when ready.");return;
                }
                var zone=OceanCanvasPlayerZones.get(world).zoneByName(head.region());
                if(zone==null){block(world,head.id(),"Region was renamed or deleted. Remove this entry and queue the intended region again.");return;}
                validate(world,zone);
                if(!head.shape().equals(shape(zone))||!head.canvas().equals(canvas())){
                    block(world,head.id(),"Region shape or canvas bounds changed. Remove and requeue to approve the new scope.");return;
                }
                model.state(head.id(),"RUNNING","Pregen only; current region rules and current performance profile apply.");data.changed();
                String result=PregenManager.startQueuedRegion(world,zone,owner,head.id());
                if(!PregenManager.isQueuedJob(head.id())){block(world,head.id(),result);return;}
                message="Running queued Pregen: "+head.region();
            }catch(RuntimeException ex){
                OceanCanvas.LOGGER.warn("OC-Q101: queued Pregen dispatch stopped",ex);
                if(PregenManager.isQueuedJob(head.id()))PregenManager.cancel();
                block(world,head.id(),"OC-Q101: "+(ex.getMessage()==null?"Dispatch failed; inspect latest.log.":ex.getMessage()));
            }
        });
    }
    private static boolean busy(){return OceanCanvasTerrainOperationActivity.queuedTerrainWorkInFlight();}

    public static String action(ServerPlayer player,String action,String id,String argument){
        if(!player.level().dimension().equals(Level.OVERWORLD))return "Pregen Queue is Overworld-only.";
        var world=player.level();var data=OceanCanvasPregenQueueData.get(world);var model=data.model();
        if(action.equals("queue_status"))return message;
        if(model.readOnly())return "Queue is read-only because its schema/content is incompatible.";
        try{
            switch(action){
                case "queue_add" -> {
                    var zone=OceanCanvasPlayerZones.get(world).zoneByName(argument);
                    if(zone==null)return "Select an existing region on the map first.";
                    long chunks=validate(world,zone);
                    model.add(new OceanCanvasPregenQueueModel.Entry(UUID.randomUUID().toString(),zone.name(),player.getUUID().toString(),
                            shape(zone),canvas(),chunks,"WAITING","Scope frozen; rules/profile are evaluated when the job starts."));
                    // Adding never authorizes work, including into a queue that was already armed.
                    armed=false;message="Region queued; queue paused. Review the full list before Run.";
                }
                case "queue_run" -> {
                    if(Long.parseLong(argument)!=model.revision())return "Queue changed since confirmation. Review the current list and confirm again.";
                    if(model.head()==null)return "Queue is empty.";
                    if(!model.head().state().equals("RUNNING"))model.state(model.head().id(),"WAITING","Approved for dispatch; scope will be rechecked.");
                    armed=true;model.touch();message="Queue armed for this session. Next entry starts only after current work drains.";
                }
                case "queue_pause" -> {armed=false;model.touch();message="Queue paused after the current job; no further entries will start.";}
                case "queue_stop" -> {
                    armed=false;model.touch();
                    if(model.head()!=null&&PregenManager.isQueuedJob(model.head().id()))PregenManager.cancel();
                    message="Queue stopped. Current queued entry retained for review/retry; already-issued Minecraft requests may finish.";
                }
                case "queue_remove" -> {
                    model.remove(id);armed=false;
                    var saved=OceanCanvasJobState.get(world).get();
                    if(!PregenManager.isRunning()&&saved!=null&&saved.queueEntryId().equals(id))OceanCanvasJobState.get(world).clear();
                    message="Entry removed; queue paused. Completed terrain was not rolled back.";
                }
                case "queue_up", "queue_down" -> {model.move(id,action.equals("queue_up")?-1:1);armed=false;message="Order updated; review and Run queue.";}
                case "queue_discard_cursor" -> {
                    var saved=OceanCanvasJobState.get(world).get();
                    if(PregenManager.isRunning()||saved==null||saved.queueEntryId().isBlank()||!saved.queueEntryId().equals(id))return "That saved queued cursor is not available to discard.";
                    OceanCanvasJobState.get(world).clear();armed=false;model.touch();
                    message="Saved queued cursor discarded. Queue entries remain; no terrain rolled back.";
                }
                default -> {return "Unknown queue action.";}
            }
            data.changed();return message;
        }catch(IllegalArgumentException|IllegalStateException ex){return ex.getMessage();}
    }

    public static String packed(ServerLevel world){
        if(!world.dimension().equals(Level.OVERWORLD))return new OceanCanvasPregenQueueModel(1,0,java.util.List.of())
                .view(false,"Pregen Queue is Overworld-only.","");
        var saved=OceanCanvasJobState.get(world).get();
        String recovery=!PregenManager.isRunning()&&saved!=null?saved.queueEntryId():"";
        return OceanCanvasPregenQueueData.get(world).model().view(armed,message,recovery);
    }
    private static void block(ServerLevel world,String id,String reason){
        armed=false;message=reason;var data=OceanCanvasPregenQueueData.get(world);
        if(!data.model().readOnly()&&data.model().contains(id)){data.model().state(id,"BLOCKED",reason);data.changed();}
    }
    /** Invoked only after actual engine completion or explicit cancellation/disable, never inferred from idle. */
    public static void finished(ServerLevel world,String id,boolean success){
        if(id==null||id.isBlank())return;
        var data=OceanCanvasPregenQueueData.get(world);
        try{
            if(success){data.model().complete(id);message="Queued Pregen finished and drained.";if(data.model().head()==null)armed=false;}
            else{armed=false;data.model().state(id,"INTERRUPTED","Stopped before completion. Run queue to replay, or remove this entry.");message="Queued Pregen interrupted; queue paused.";}
            data.changed();
        }catch(RuntimeException ex){armed=false;message="OC-Q102: queue completion mismatch; review recovery before continuing.";OceanCanvas.LOGGER.warn(message,ex);}
    }

    private static String canvas(){var c=OceanCanvasConfig.get();return c.centerX()+","+c.centerZ()+","+c.canvasSize();}
    private static long validate(ServerLevel world,OceanCanvasPlayerZones.Zone zone){
        var meta=OceanCanvasProjectData.get(world).regionMeta(zone.name());
        if(meta!=null&&meta.parsedStage()==OceanCanvasProjectData.RegionStage.ARCHIVED)throw new IllegalArgumentException("Region is Archived; queue stopped without modifying it.");
        var b=zone.bounds();
        for(int v:new int[]{b.minX(),b.maxX(),b.minZ(),b.maxZ()})if(v < -30_000_000||v > 30_000_000)throw new IllegalArgumentException("Region coordinates are out of range.");
        int minX=Math.floorDiv(b.minX(),16),maxX=Math.floorDiv(b.maxX(),16),minZ=Math.floorDiv(b.minZ(),16),maxZ=Math.floorDiv(b.maxZ(),16);
        long envelope=((long)maxX-minX+1)*((long)maxZ-minZ+1);
        if(envelope<1||envelope>OceanCanvasPregenQueueModel.MAX_REGION_CHUNKS||zone.chunks().size()>OceanCanvasPregenQueueModel.MAX_REGION_CHUNKS)
            throw new IllegalArgumentException("Queued regions are limited to a 65,536-chunk envelope. Split large/sparse regions first.");
        for(long key:zone.chunks())if(ChunkPos.getX(key)<minX||ChunkPos.getX(key)>maxX||ChunkPos.getZ(key)<minZ||ChunkPos.getZ(key)>maxZ)
            throw new IllegalArgumentException("Region mask extends outside its bounds. Repair the region before queueing.");
        var c=OceanCanvasConfig.get();
        long ax=Math.floorDiv((long)c.centerX()-c.radius(),16),bx=Math.floorDiv((long)c.centerX()+c.radius()-1,16);
        long az=Math.floorDiv((long)c.centerZ()-c.radius(),16),bz=Math.floorDiv((long)c.centerZ()+c.radius()-1,16);
        long count;
        if(zone.hasExplicitShape())count=zone.chunks().stream().filter(p->ChunkPos.getX(p)>=ax&&ChunkPos.getX(p)<=bx&&ChunkPos.getZ(p)>=az&&ChunkPos.getZ(p)<=bz).count();
        else count=Math.max(0,Math.min(maxX,bx)-Math.max(minX,ax)+1)*Math.max(0,Math.min(maxZ,bz)-Math.max(minZ,az)+1);
        if(count==0)throw new IllegalArgumentException("Region has no chunks inside the current canvas.");return count;
    }
    private static String shape(OceanCanvasPlayerZones.Zone zone){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");var b=zone.bounds();
            digest.update(ByteBuffer.allocate(24).putInt(b.minX()).putInt(b.minY()).putInt(b.minZ()).putInt(b.maxX()).putInt(b.maxY()).putInt(b.maxZ()).array());
            long[] keys=zone.chunks().stream().mapToLong(Long::longValue).sorted().toArray();
            ByteBuffer bytes=ByteBuffer.allocate(8);for(long key:keys){bytes.clear();bytes.putLong(key);digest.update(bytes.array());}
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable",ex);}
    }
}
