package net.oceancanvas.mod.restore;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity;
import net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;

/** Tick-polled, one-column-at-a-time Restore-to-Vanilla job. */
public final class OceanCanvasRestoreManager {
    // Restore progress is deterministic and idempotent per chunk. Persisting the
    // complete BitSet word list after every regenerated chunk created O(total/64)
    // allocation and SavedData churn per completion. Bound crash replay instead:
    // checkpoint at least every 64 chunks or 5 seconds, and force one on stop/fail.
    private static final int RESTORE_PROGRESS_CHECKPOINT_CHUNKS = 64;
    private static final long RESTORE_PROGRESS_CHECKPOINT_NS = 5_000_000_000L;
    private static final class RuntimeState { Job active; }
    private OceanCanvasRestoreManager(){}
    private static RuntimeState state(MinecraftServer server){
        return OceanCanvasServerRuntime.get(server).state(RuntimeState.class, RuntimeState::new);
    }
    private static RuntimeState existingState(){
        OceanCanvasServerRuntime runtime=OceanCanvasServerRuntime.onlyActiveOrNull();
        return runtime==null?null:runtime.stateIfPresent(RuntimeState.class);
    }
    private static Job active(){ RuntimeState s=existingState(); return s==null?null:s.active; }
    public static void register(){
        ServerTickEvents.END_SERVER_TICK.register(OceanCanvasRestoreManager::tick);
        ServerLevelEvents.LOAD.register(OceanCanvasRestoreManager::onLevelLoad);
    }
    public static synchronized boolean running(){return active()!=null;}
    public static synchronized void onServerStopping(MinecraftServer server){
        RuntimeState state=OceanCanvasServerRuntime.get(server).stateIfPresent(RuntimeState.class);
        Job active=state==null?null:state.active;
        if(active!=null){
            active.persist();
            active.releaseGenerationTicket();
            active.stopBossBar();
            state.active=null;
        }
    }

    private static synchronized void onLevelLoad(MinecraftServer server, ServerLevel level){
        RuntimeState state=state(server);
        if(state.active!=null) return;
        OceanCanvasRestoreJobState.Snapshot snapshot=OceanCanvasRestoreJobState.get(level).get();
        if(snapshot==null) return;
        String compatibilityBlock=net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(level);
        if(!compatibilityBlock.isEmpty()){
            OceanCanvas.LOGGER.error("(Ocean Canvas) Holding persisted Restore checkpoint without resuming: {}",compatibilityBlock);
            return;
        }
        if(OceanCanvasTerrainOperationActivity.pregenRunning()){
            OceanCanvas.LOGGER.warn("(Ocean Canvas) Restore checkpoint exists but a Pregen/Rewipe/Expand job resumed first; Restore is held until explicitly restarted or cancelled.");
            return;
        }
        state.active=Job.resume(level,snapshot);
        OceanCanvas.LOGGER.info("(Ocean Canvas) Resumed Restore to Vanilla after restart: {}.",state.active.progress());
        net.oceancanvas.mod.operation.OceanCanvasActionLog.recordLifecycle(level,"restore","RESUMED",state.active.requesterPlayer(),state.active.progress());
    }

    public static synchronized String cancel(ServerLevel world){
        Job active=state(world.getServer()).active;
        if(active!=null) return active.requestCancel();
        OceanCanvasRestoreJobState state=OceanCanvasRestoreJobState.get(world);
        if(state.get()!=null){state.clear();return "Cleared the persisted Restore to Vanilla checkpoint; it will not resume on restart.";}
        return "No Restore to Vanilla job is running or waiting to resume.";
    }
    public record TicketOwnership(int chunkX,int chunkZ,long ageMillis,String owner,String purpose,String releaseCondition){}
    public static synchronized TicketOwnership ticketOwnershipSnapshot(){
        Job active=active();
        if(active==null||!active.generationTicketInstalled||active.current==null)return null;
        return new TicketOwnership(active.current.x(),active.current.z(),0L,"restore","native FULL regeneration residency","regeneration future completes or cancel");
    }

    public record OwnerSnapshot(String kind, UUID requesterId, String requesterDisplay){}
    public static synchronized OwnerSnapshot ownerSnapshot(){
        Job active=active();
        if(active==null)return null;
        UUID id=active.requesterId;
        String display="console/system";
        if(id!=null){
            ServerPlayer p=active.world.getServer().getPlayerList().getPlayer(id);
            display=p==null?id.toString():p.getGameProfile().name();
        }
        return new OwnerSnapshot("restore",id,display);
    }

    public record Overlay(String kind,int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ,int cursorChunkX,int cursorChunkZ,long completed,long total){}
    public static synchronized Overlay overlaySnapshot(){
        Job active=active();
        if(active==null) return null;
        ChunkPos c=active.current;
        int cursorX=c==null?active.minChunkX:c.x(), cursorZ=c==null?active.minChunkZ:c.z();
        return new Overlay("restore",active.minChunkX,active.minChunkZ,active.maxChunkX,active.maxChunkZ,cursorX,cursorZ,active.completedCount,active.scope.total);
    }

    public static synchronized String restoreRegion(ServerLevel world, OceanCanvasPlayerZones.Zone zone, ServerPlayer requester){
        RuntimeState state=state(world.getServer());
        Job active=state.active;
        String compatibilityBlock=net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
        if(!compatibilityBlock.isEmpty())return compatibilityBlock;
        var meta=net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).regionMeta(zone.name());
        if(meta!=null && meta.parsedStage()==net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.ARCHIVED)return "Region '"+zone.name()+"' is Archived. Move it out of Archived before Restore to Vanilla.";
        if(active!=null)return "A Restore to Vanilla job is already running ("+active.progress()+").";
        if(OceanCanvasTerrainOperationActivity.pregenRunning())return "A pregen/rewipe/expand job is already running; finish or cancel it before Restore.";
        String scopeBlock=restoreScopeBlockReason(zone);
        if(!scopeBlock.isEmpty())return scopeBlock;
        Set<Long> selected=exactChunks(zone,OceanCanvasConfig.get());if(selected.isEmpty())return "That region has no chunks inside the configured canvas.";
        String foreverBlock=net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.blockChunks(world,"RESTORE",selected,zone.name(),net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.projectForRegion(world,zone.name()),"");
        if(!foreverBlock.isEmpty())return foreverBlock;
        String backup = net.oceancanvas.mod.backup.OceanCanvasBackupManager.maybeBackup(world, "restore", selected.size());
        net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.captureChunks(world,"RESTORE",zone.name(),selected);
        state.active=new Job(world,selected,requester,zone.name());
        active=state.active;
        OceanCanvas.LOGGER.info("(Ocean Canvas) v94 Restore engine selected: protected prune + native chunk regeneration path (boss-bar + inside-only rectangular footprint).");
        active.persist();
        net.oceancanvas.mod.operation.OceanCanvasActionLog.record(world,"restore",requester,
                selected.size()+" chunk(s) in region '"+zone.name()+"'");
        return (backup == null || backup.isBlank() ? "" : backup + " ") + "Started Restore to Vanilla: "+selected.size()+" chunk(s) in region '"+zone.name()+"'. Player-built blocks in those chunks will be replaced by regenerated vanilla terrain.";
    }


    /**
     * Native Anvil pruning is chunk-granular. Never pretend a block-exact polygon or
     * non-aligned rectangle was restored when that engine can only replace whole chunks.
     * v253.77 fails closed instead of silently leaving edge strips or spilling outside the
     * selection. Block-exact shadow regeneration remains a separate roadmap item.
     */
    private static String restoreScopeBlockReason(OceanCanvasPlayerZones.Zone zone){
        if(zone==null)return "No region was selected for Restore to Vanilla.";
        if(zone.shapeVertices()!=null && zone.shapeVertices().size()>=6){
            return "Restore to Vanilla currently requires a chunk-aligned region. Polygon regions are block-exact and cannot be safely restored by whole-chunk pruning yet; use a chunk-aligned rectangle or wait for shadow-regeneration Restore.";
        }
        if(zone.hasExplicitShape())return ""; // explicit chunk masks are already whole-chunk exact
        var b=zone.bounds();
        var blockBounds=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.blockBounds(b.minX(),b.minZ(),b.maxX(),b.maxZ());
        boolean aligned=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.isChunkAligned(blockBounds);
        return aligned?"":"Restore to Vanilla currently requires region edges on chunk boundaries (min X/Z multiple of 16; max X/Z ending in 15). Ocean Canvas refuses this Restore rather than silently leaving or changing blocks outside the selected bounds.";
    }

    private static Set<Long> exactChunks(OceanCanvasPlayerZones.Zone zone,OceanCanvasConfig config){
        // v253.125.42: large Restore scopes keep primitive insertion-ordered
        // membership instead of one boxed Long/hash node per selected chunk.
        LongLinkedOpenHashSet out=new LongLinkedOpenHashSet();
        int radius=config.radius();
        int minCanvasX=Math.floorDiv(config.centerX()-radius,16),maxCanvasX=Math.floorDiv(config.centerX()+radius,16);
        int minCanvasZ=Math.floorDiv(config.centerZ()-radius,16),maxCanvasZ=Math.floorDiv(config.centerZ()+radius,16);
        if(zone.chunks()!=null&&!zone.chunks().isEmpty()){
            for(long p:zone.chunks()){
                int x=ChunkPos.getX(p),z=ChunkPos.getZ(p);
                if(x>=minCanvasX&&x<=maxCanvasX&&z>=minCanvasZ&&z<=maxCanvasZ)out.add(p);
            }
            return out;
        }

        // A prune can only operate on a whole Anvil chunk. The old code used every
        // chunk that merely INTERSECTED a rectangular region, which meant up to 15
        // blocks beyond each selected edge could be restored. For legacy rectangular
        // regions, v94 uses only chunks whose entire 16x16 footprint is inside the
        // selected rectangle. This makes "Restore this region" a strict no-spill
        // operation. Explicit chunk-mask regions are already exact and use the branch
        // above unchanged.
        var inside=net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.wholeChunksInsideBlocks(
                zone.bounds().minX(),zone.bounds().minZ(),zone.bounds().maxX(),zone.bounds().maxZ());
        var canvas=new net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.ChunkBounds(
                minCanvasX,maxCanvasX,minCanvasZ,maxCanvasZ);
        var clipped=inside.intersect(canvas);
        for(long packed:net.oceancanvas.mod.geometry.OceanCanvasRegionGeometry.rectangularChunkSet(clipped))out.add(packed);
        return out;
    }

    private static void tick(MinecraftServer server){
        Job job;
        RuntimeState state=state(server);
        synchronized(OceanCanvasRestoreManager.class){job=state.active;}
        if(job==null||job.world.getServer()!=server)return;
        try{
            if(job.tick()){
                job.releaseGenerationTicket();
                OceanCanvasRestoreJobState.get(job.world).clear();
                String phase=job.cancelRequested?"CANCELLED":"COMPLETED";
                net.oceancanvas.mod.operation.OceanCanvasActionLog.recordLifecycle(job.world,"restore",phase,job.requesterPlayer(),job.progress());
                net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.verify(job.world,phase);
                if("COMPLETED".equals(phase)){
                    net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship.recordCompletedChunkRuns(job.world,"restore",job.scope.asZoneRuns(),"");
                    net.oceancanvas.mod.project.OceanCanvasHarnessService.recordOperationObservation(job.world,"restore",job.progress());
                }
                net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(job.world)
                        .capture(job.world,"after-restore-"+phase.toLowerCase(java.util.Locale.ROOT),"Restore lifecycle "+phase);
                job.stopBossBar();
                synchronized(OceanCanvasRestoreManager.class){state.active=null;}
            }
        }catch(Throwable t){
            OceanCanvas.LOGGER.error("Restore to Vanilla failed",t);
            job.tell("Restore to Vanilla failed: "+t.getMessage());
            job.persist();
            net.oceancanvas.mod.operation.OceanCanvasActionLog.recordLifecycle(job.world,"restore","FAILED",job.requesterPlayer(),
                    t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));
            net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryService.verify(job.world,"FAILED");
            net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(job.world)
                    .capture(job.world,"after-restore-failure","Restore failed; checkpoint retained");
            job.releaseGenerationTicket();
            job.stopBossBar();
            synchronized(OceanCanvasRestoreManager.class){state.active=null;}
        }
    }

    /** Immutable, compact restore scope. One run represents adjacent chunks on one Z row. */
    private static final class RestoreScope {
        final List<OceanCanvasRestoreJobState.ChunkRun> runs;
        final int[] prefixEnds;
        final int total;
        final int minX,minZ,maxX,maxZ;

        private RestoreScope(List<OceanCanvasRestoreJobState.ChunkRun> source){
            ArrayList<OceanCanvasRestoreJobState.ChunkRun> clean=new ArrayList<>();
            long count=0L;int loX=Integer.MAX_VALUE,loZ=Integer.MAX_VALUE,hiX=Integer.MIN_VALUE,hiZ=Integer.MIN_VALUE;
            for(var r:source){
                if(r==null)continue;
                int a=Math.min(r.minX(),r.maxX()),b=Math.max(r.minX(),r.maxX());
                long width=(long)b-a+1L;
                if(width<=0L)continue;
                count+=width;
                if(count>Integer.MAX_VALUE)throw new IllegalArgumentException("Restore scope exceeds supported chunk count: "+count);
                clean.add(new OceanCanvasRestoreJobState.ChunkRun(r.z(),a,b));
                loX=Math.min(loX,a);hiX=Math.max(hiX,b);loZ=Math.min(loZ,r.z());hiZ=Math.max(hiZ,r.z());
            }
            runs=List.copyOf(clean);total=(int)count;prefixEnds=new int[runs.size()];
            int at=0;for(int i=0;i<runs.size();i++){at+=Math.toIntExact(runs.get(i).size());prefixEnds[i]=at;}
            minX=loX;minZ=loZ;maxX=hiX;maxZ=hiZ;
        }

        static RestoreScope fromChunks(Collection<Long> chunks){
            TreeMap<Integer,TreeSet<Integer>> rows=new TreeMap<>();
            if(chunks!=null)for(long packed:chunks)rows.computeIfAbsent(ChunkPos.getZ(packed),__->new TreeSet<>()).add(ChunkPos.getX(packed));
            ArrayList<OceanCanvasRestoreJobState.ChunkRun> out=new ArrayList<>();
            for(var e:rows.entrySet()){
                Integer first=null,last=null;
                for(int x:e.getValue()){
                    if(first==null){first=last=x;continue;}
                    if(x==last+1){last=x;continue;}
                    out.add(new OceanCanvasRestoreJobState.ChunkRun(e.getKey(),first,last));first=last=x;
                }
                if(first!=null)out.add(new OceanCanvasRestoreJobState.ChunkRun(e.getKey(),first,last));
            }
            return new RestoreScope(out);
        }
        static RestoreScope fromRuns(List<OceanCanvasRestoreJobState.ChunkRun> runs){return new RestoreScope(runs==null?List.of():runs);}
        long packedAt(int index){
            if(index<0||index>=total)throw new IndexOutOfBoundsException(index);
            int lo=0,hi=prefixEnds.length-1;
            while(lo<hi){int mid=(lo+hi)>>>1;if(index<prefixEnds[mid])hi=mid;else lo=mid+1;}
            var r=runs.get(lo);int prior=lo==0?0:prefixEnds[lo-1];return ChunkPos.pack(r.minX()+(index-prior),r.z());
        }
        int indexOfPacked(long packed){
            int x=ChunkPos.getX(packed),z=ChunkPos.getZ(packed);
            int lo=0,hi=runs.size();
            while(lo<hi){int mid=(lo+hi)>>>1;if(runs.get(mid).z()<z)lo=mid+1;else hi=mid;}
            for(int i=lo;i<runs.size()&&runs.get(i).z()==z;i++){var r=runs.get(i);if(x<r.minX())break;if(x<=r.maxX()){int prior=i==0?0:prefixEnds[i-1];return prior+(x-r.minX());}}
            return -1;
        }
        List<OceanCanvasPlayerZones.ChunkRunView> asZoneRuns(){
            ArrayList<OceanCanvasPlayerZones.ChunkRunView> out=new ArrayList<>(runs.size());
            for(var r:runs)out.add(new OceanCanvasPlayerZones.ChunkRunView(r.z(),r.minX(),r.maxX()));
            return List.copyOf(out);
        }
    }

    private static final class Job{
        final ServerLevel world;
        final RestoreScope scope;
        final BitSet completed;
        final ServerPlayer requester;
        final UUID requesterId;
        final String regionName;
        final int minChunkX,minChunkZ,maxChunkX,maxChunkZ;
        int completedCount;
        int cursor;
        int currentScopeIndex=-1;
        ChunkPos current;
        CompletableFuture<Void> pruneFuture;
        CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> regenFuture;
        boolean generationTicketInstalled;
        boolean cancelRequested;
        int unloadWaitTicks;
        int lastCheckpointCompletedCount;
        long lastCheckpointNs;
        final ServerBossEvent bossEvent;

        Job(ServerLevel w,Collection<Long> chunks,ServerPlayer r,String n){
            this(w,RestoreScope.fromChunks(chunks),new BitSet(),0,r,r==null?null:r.getUUID(),n);
        }
        Job(ServerLevel w,RestoreScope scope,BitSet completed,int cursor,ServerPlayer r,UUID requesterId,String n){
            world=w;this.scope=scope;this.completed=(BitSet)completed.clone();requester=r;this.requesterId=requesterId;regionName=n;
            this.completed.clear(scope.total,Integer.MAX_VALUE);completedCount=this.completed.cardinality();
            this.cursor=scope.total==0?0:Math.floorMod(cursor,scope.total);
            lastCheckpointCompletedCount=completedCount;
            lastCheckpointNs=System.nanoTime();
            minChunkX=scope.minX;minChunkZ=scope.minZ;maxChunkX=scope.maxX;maxChunkZ=scope.maxZ;
            bossEvent=new ServerBossEvent(UUID.randomUUID(),Component.literal("Ocean Canvas - Restore to Vanilla"),BossEvent.BossBarColor.BLUE,BossEvent.BossBarOverlay.PROGRESS);
            updateBossBar("Starting");
        }
        static Job resume(ServerLevel world,OceanCanvasRestoreJobState.Snapshot snapshot){
            ServerPlayer player=snapshot.requestedByUuid()==null?null:world.getServer().getPlayerList().getPlayer(snapshot.requestedByUuid());
            RestoreScope scope;
            BitSet done;
            int cursor;
            if(snapshot.runs()!=null&&!snapshot.runs().isEmpty()){
                scope=RestoreScope.fromRuns(snapshot.runs());
                List<Long> persistedWords=snapshot.completedWords();
                long[] words;
                if(persistedWords instanceof LongList primitiveWords)words=primitiveWords.toLongArray();
                else{words=new long[persistedWords.size()];for(int i=0;i<words.length;i++)words[i]=persistedWords.get(i);}
                done=BitSet.valueOf(words);cursor=snapshot.cursor();
            }else{
                // v253.77-and-earlier migration: the persisted list was mutated by swaps,
                // and [0,nextIndex) is exactly the completed prefix.
                scope=RestoreScope.fromChunks(snapshot.chunks());done=new BitSet(scope.total);
                int legacyDone=Math.min(snapshot.nextIndex(),snapshot.chunks().size());
                for(int i=0;i<legacyDone;i++){int idx=scope.indexOfPacked(snapshot.chunks().get(i));if(idx>=0)done.set(idx);}
                cursor=0;while(cursor<scope.total&&done.get(cursor))cursor++;if(cursor>=scope.total)cursor=0;
            }
            return new Job(world,scope,done,cursor,player,snapshot.requestedByUuid(),snapshot.regionName());
        }

        boolean tick(){
            updateBossBar(current==null?"Preparing":pruneFuture!=null?"Pruning":regenFuture!=null?"Regenerating":"Waiting for unload");
            persistProgressIfDue();
            if(cancelRequested && current==null){
                tell("Restore to Vanilla cancelled after "+completedCount+" completed chunk(s).");
                return true;
            }
            if(completedCount>=scope.total){
                updateBossBar("Complete");
                tell("Restore to Vanilla complete: "+scope.total+" chunk(s) restored in '"+regionName+"'.");
                return true;
            }

            if(current==null){selectNextCandidate();unloadWaitTicks=0;}
            if(current==null)return false;

            if(pruneFuture==null && regenFuture==null){
                if(!isSafeToPrune(current)){
                    unloadWaitTicks++;
                    if(unloadWaitTicks==200)tell("Restore is waiting for one remaining live chunk to leave memory. You do not need to move farther away; Ocean Canvas will continue automatically when Minecraft releases it.");
                    return false;
                }

                // Crash guard first, provenance commit last. The processed seal prevents
                // normal gameplay flattening while this transaction owns the chunk, but
                // VANILLA is not recorded until native FULL regeneration succeeds.
                OceanCanvasProtectedData protection=OceanCanvasProtectedData.get(world);
                protection.prepareRegionRewipe(java.util.Set.of(ChunkPos.pack(current.x(),current.z())));
                protection.markChunkProcessed(current);
                pruneFuture=net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.pruneStoredChunk(world,current);
                return false;
            }

            if(pruneFuture!=null){
                if(!pruneFuture.isDone()) return false;
                pruneFuture.join();pruneFuture=null;
                net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.addForcedTicket(world,current,0);
                generationTicketInstalled=true;
                regenFuture=world.getChunkSource().getChunkFuture(current.x(),current.z(),ChunkStatus.FULL,false);
                return false;
            }

            if(regenFuture!=null){
                if(!regenFuture.isDone()) return false;
                net.minecraft.server.level.ChunkResult<ChunkAccess> result=regenFuture.join();
                ChunkAccess access=result.orElse(null);
                if(!(access instanceof LevelChunk live))throw new IllegalStateException("Native Restore regeneration did not produce a FULL LevelChunk at "+current.x()+","+current.z()+": "+result.getError());
                releaseGenerationTicket();regenFuture=null;
                OceanCanvasProtectedData.get(world).markChunkProcessed(current);
                net.oceancanvas.mod.project.OceanCanvasTerrainStateData.get(world).set(current,net.oceancanvas.mod.project.OceanCanvasTerrainStateData.TerrainState.VANILLA);
                net.oceancanvas.mod.project.OceanCanvasP1W3Service.recordRestoredChunk(world,live);
                net.oceancanvas.mod.compat.OceanCanvasTerrainChangeBus.publish(new net.oceancanvas.mod.compat.OceanCanvasTerrainChange(world,current,net.oceancanvas.mod.compat.OceanCanvasTerrainChange.Kind.RESTORE_TO_VANILLA));

                completed.set(currentScopeIndex);completedCount++;
                cursor=scope.total==0?0:(currentScopeIndex+1)%scope.total;
                currentScopeIndex=-1;current=null;persistProgressIfDue();
                if(cancelRequested){tell("Restore to Vanilla cancelled after finishing the active chunk; "+completedCount+" chunk(s) are fully restored.");return true;}
            }
            return false;
        }

        /** Prefer an unloaded incomplete target without mutating/persisting a million-entry order list. */
        private void selectNextCandidate(){
            if(scope.total==0)return;
            int first=-1,safe=-1;
            for(int step=0;step<scope.total;step++){
                int idx=(cursor+step)%scope.total;if(completed.get(idx))continue;
                if(first<0)first=idx;
                long packed=scope.packedAt(idx);ChunkPos candidate=new ChunkPos(ChunkPos.getX(packed),ChunkPos.getZ(packed));
                if(isSafeToPrune(candidate)){safe=idx;break;}
            }
            int chosen=safe>=0?safe:first;if(chosen<0)return;
            long packed=scope.packedAt(chosen);currentScopeIndex=chosen;current=new ChunkPos(ChunkPos.getX(packed),ChunkPos.getZ(packed));
        }

        private boolean isSafeToPrune(ChunkPos pos){return world.getChunkSource().getChunkNow(pos.x(),pos.z())==null;}

        private void updateBossBar(String phase){
            ServerPlayer player=requesterPlayer();
            if(player!=null){
                bossEvent.addPlayer(player);long total=Math.max(1,scope.total);
                float progress=(float)Math.max(0.0,Math.min(1.0,(double)completedCount/(double)total));
                bossEvent.setProgress(progress);String count=completedCount+" / "+scope.total;
                bossEvent.setName(Component.literal("Ocean Canvas - Restore: "+phase+" ("+count+")"));bossEvent.setVisible(true);
            }
        }
        void stopBossBar(){bossEvent.setVisible(false);bossEvent.removeAllPlayers();}
        ServerPlayer requesterPlayer(){return requesterId==null?null:world.getServer().getPlayerList().getPlayer(requesterId);}
        private void persistProgressIfDue(){
            if(completedCount==lastCheckpointCompletedCount)return;
            long now=System.nanoTime();
            if(completedCount-lastCheckpointCompletedCount<RESTORE_PROGRESS_CHECKPOINT_CHUNKS
                    && now-lastCheckpointNs<RESTORE_PROGRESS_CHECKPOINT_NS)return;
            persist();
        }
        void persist(){
            long[] raw=completed.toLongArray();LongArrayList words=new LongArrayList(raw.length);for(long v:raw)words.add(v);
            OceanCanvasRestoreJobState.get(world).save(new OceanCanvasRestoreJobState.Snapshot(regionName,List.of(),0,requesterId==null?List.of():List.of(requesterId.toString()),scope.runs,words,cursor));
            lastCheckpointCompletedCount=completedCount;
            lastCheckpointNs=System.nanoTime();
        }
        void releaseGenerationTicket(){
            if(!generationTicketInstalled)return;ChunkPos owned=current;generationTicketInstalled=false;
            if(owned!=null)net.oceancanvas.mod.compat.OceanCanvasChunkRuntimeCompat.removeForcedTicket(world,owned,0);
        }
        String requestCancel(){cancelRequested=true;return "Restore cancellation requested; Ocean Canvas will finish the active chunk, then stop before pruning another chunk.";}
        String progress(){return completedCount+"/"+scope.total+" chunks";}
        void tell(String s){ServerPlayer target=requester;if(target==null&&requesterId!=null)target=world.getServer().getPlayerList().getPlayer(requesterId);if(target!=null&&target.isAlive())target.sendSystemMessage(Component.literal("[Ocean Canvas] "+s));else OceanCanvas.LOGGER.info("(Ocean Canvas) {}",s);}
    }
}
