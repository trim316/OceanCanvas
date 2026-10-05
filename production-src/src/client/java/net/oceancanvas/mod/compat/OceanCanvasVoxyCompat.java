package net.oceancanvas.mod.compat;

import java.lang.reflect.Method;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.network.OceanCanvasTerrainChangedPayload;
import net.oceancanvas.mod.network.OceanCanvasTerrainAckPayload;
import net.oceancanvas.mod.network.OceanCanvasTerrainRepairRequestPayload;

/**
 * Soft Voxy bridge.
 *
 * <p>No Voxy type appears in this class's signatures or imports. The current
 * bridge reflectively uses Voxy's internal VoxelIngestService only when Voxy is
 * actually installed and the expected method exists. If Voxy changes that
 * internal API, this adapter disables itself and Ocean Canvas continues normally.
 * This is intentional until Voxy exposes a stable public invalidation API.</p>
 */
public final class OceanCanvasVoxyCompat {
    private static final int MAX_PENDING = 8192;
    // v253.46: keep the Minecraft 26.2 LevelExtractor dirty across enough distinct client scheduling
    // boundaries that Sodium cannot reuse a mesh queued before the final light packet.
    private static final int RENDER_READY_STREAK_REQUIRED = 4;
    // v253.125.7: the fixed x~-749,z~399 repro remained visibly broken >40 s after
    // every sampled client light array had reached the canonical 15/14 field and
    // LevelExtractor.setSectionDirty had run more than a thousand times. Minecraft
    // 26.2 split the old allChanged() path into LevelRenderer.invalidateCompiledGeometry.
    // Use that stronger compiled-geometry generation barrier only for player-visible
    // lighting repairs/resyncs, coalesced into one global invalidation per wave.
    private static final int STRONG_GEOMETRY_INVALIDATE_DELAY_TICKS = 10;
    private static final int STRONG_GEOMETRY_INVALIDATE_MIN_INTERVAL_TICKS = 160;
    // v253.73.6/73.7 debounced the narrow publication-time 15/15 surface race.
    // v253.125.12 then supplied a stronger proof for the dark side of the same race:
    // all 175 client SURFACE_SKYLIGHT_STALE 0/0 requests were served from an already
    // healthy server field (deferredServerRepair=0, fluid repair=0), and 174 converged
    // after one resend. Keep deep/physical failures immediate, but require four real
    // END_CLIENT_TICK observations before resending an exact surface-only 0/0 field.
    // This delays no success certificate: readiness remains fail-closed throughout.
    private static final int RESYNC_FAILURE_STREAK_REQUIRED_URGENT = 1;
    private static final int RESYNC_FAILURE_STREAK_REQUIRED_SURFACE_0_0 = 4;
    private static final int RESYNC_FAILURE_STREAK_REQUIRED_SURFACE_15_15 = 8;
    private static final int RESYNC_BASE_COOLDOWN_TICKS = 20;
    private static final int RESYNC_MAX_COOLDOWN_TICKS = 100;
    // v253.125.33: a rejoin can expose hundreds of stale client chunks at once.
    // Keep exact repair semantics, but bound both network burst size and synchronous
    // log I/O on the render thread. Detail is sampled; suppressed counts are emitted
    // as periodic aggregate evidence.
    private static final int RESYNC_SEND_CHUNKS_PER_CLIENT_TICK = 4;
    private static final int RESYNC_DETAIL_LOGS_PER_WINDOW = 8;
    private static final long RESYNC_LOG_WINDOW_TICKS = 100L;
    // Voxy's private tryAutoIngestChunk hook is explicitly best-effort. A false
    // return means "not accepted now", not a successful refresh. v253.125.12 dropped
    // that chunk from PENDING immediately. Retain it for a small bounded retry window
    // so transient Voxy backpressure cannot leave the distant cache stale forever.
    private static final int VOXY_REINGEST_MAX_ATTEMPTS = 3;
    private static final int VOXY_REINGEST_BASE_RETRY_TICKS = 20;
    // v253.72.6: deep zero-tail overbright is a correctness failure, not a
    // renderer-tolerable fixed point. The server repairs authoritative deep SKY
    // storage explicitly; the client must hold until corrected arrays arrive.
    // v253.72.3: a full chunk+light packet can make the queried light field look
    // canonical before Sodium has discarded the mesh built from the old arrays.
    // Hold several real client ticks after each resync request and dirty a wider
    // radius before any readiness streak can begin.
    private static final int POST_RESYNC_QUIET_TICKS = 4;
    // v253.73.14: the custom terrain publication follows a vanilla full chunk+light
    // packet on the same connection. Give that packet two END_CLIENT_TICK boundaries
    // to populate client light storage before classifying publication-time lag.
    private static final int PUBLICATION_SETTLE_TICKS = 2;
    private static final int RESYNC_REBUILD_RADIUS_CHUNKS = 2;
    // v253.72.8 visual safety net: a server-side threaded light publication can
    // arrive after a correct packet and restore the same lower-ocean positive SKY
    // cache. For the exact broad overbright-only signature, repair only whole deep
    // sections whose loaded 3x3 neighborhood is uninterrupted canonical water.
    // This never edits blocks/biomes and still requests authoritative server repair.
    private static final int MAX_LOCAL_DEEP_ZERO_REPAIR_ATTEMPTS = 2;
    // v253.61.10: a rejoin republish can reach this handler one or more client ticks
    // before ClientChunkCache has installed the matching vanilla LevelChunk. v253.61.9
    // dropped those notifications because remember(packed) lived only inside lc != null.
    // Keep only near-player unloaded notifications for a bounded window. Far Pregen
    // publications are still ignored, preserving v253.45's anti-starvation behavior.
    private static final int AWAIT_CLIENT_LOAD_TICKS = 200;
    private static final int AWAIT_CLIENT_LOAD_RADIUS_CHUNKS = 16;
    private static final int[] DEEP_SKY_SAMPLE_DEPTHS = new int[]{2, 4, 6, 8, 10, 12};
    // v253.72.4: surface+12 was not enough to prove the floor-visible field.
    // In uninterrupted ocean water, SKY must have reached zero by depth 16.
    private static final int[] DEEP_SKY_ZERO_TAIL_DEPTHS = new int[]{16, 24, 32, 48, 64};
    // v253.125.47: client-visible repair state can contain thousands of chunks after
    // rejoin. Keep insertion order where scheduling depends on it, but avoid one boxed
    // Long plus collection node per coordinate and one boxed Integer/Long per scalar.
    private static final long NO_LONG_STATE = Long.MIN_VALUE;
    private static final int NO_INT_STATE = Integer.MIN_VALUE;
    private static final LongLinkedOpenHashSet PENDING = new LongLinkedOpenHashSet();
    private static final LongLinkedOpenHashSet RESYNC_REQUEST_QUEUE = new LongLinkedOpenHashSet();
    private static final Long2LongOpenHashMap AWAIT_CLIENT_LOAD_DEADLINE = longStateMap();
    private static final Long2IntOpenHashMap READY_STREAK = intStateMap();
    private static final Long2IntOpenHashMap FAILURE_STREAK = intStateMap();
    private static final Long2IntOpenHashMap RESYNC_ATTEMPTS = intStateMap();
    private static final Long2LongOpenHashMap NEXT_RESYNC_TICK = longStateMap();
    private static final Long2LongOpenHashMap LAST_RESYNC_REQUEST_TICK = longStateMap();
    private static final Long2LongOpenHashMap PUBLICATION_SETTLE_UNTIL_TICK = longStateMap();
    private static final Long2IntOpenHashMap VOXY_REINGEST_ATTEMPTS = intStateMap();
    private static final Long2LongOpenHashMap VOXY_REINGEST_NOT_BEFORE_TICK = longStateMap();
    private static final Long2IntOpenHashMap LOCAL_DEEP_ZERO_REPAIR_ATTEMPTS = intStateMap();
    private static final LongLinkedOpenHashSet STRONG_GEOMETRY_REQUIRED = new LongLinkedOpenHashSet();
    private static final Long2LongOpenHashMap STRONG_GEOMETRY_TARGET_EPOCH = longStateMap();
    private static final Long2LongOpenHashMap STRONG_GEOMETRY_NOT_BEFORE_TICK = longStateMap();
    private static final LongArrayList PENDING_ITERATION_SCRATCH = new LongArrayList(128);

    private static Long2LongOpenHashMap longStateMap() {
        Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(NO_LONG_STATE);
        return map;
    }

    private static Long2IntOpenHashMap intStateMap() {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        map.defaultReturnValue(NO_INT_STATE);
        return map;
    }
    private static long clientTickCounter;
    private static long strongGeometryEpoch;
    private static long lastStrongGeometryInvalidateTick = Long.MIN_VALUE / 4L;
    private static long nearFieldRebuilds;
    private static long fullClientLightScans;
    private static long fullClientLightFailures;
    private static long deepClientLightScans;
    private static long deepClientLightFailures;
    private static long deepClientOverbrightFailures;
    private static long floorBandClientScans;
    private static long floorBandClientFailures;
    private static long localDeepZeroRepairs;
    private static long localDeepZeroSections;
    private static long localDeepZeroInconclusive;
    private static long clientLightResyncRequests;
    private static long clientLightResyncChunks;
    private static long clientLightRecoveries;
    private static long clientPostResyncConvergenceHolds;
    private static long clientWideSectionRebuilds;
    private static long clientTintCacheResets;
    private static long clientAwaitLoadQueued;
    private static long clientAwaitLoadActivated;
    private static long clientAwaitLoadExpired;
    private static long clientStrongGeometryInvalidations;
    private static long clientStrongGeometryWaits;
    private static long clientVoxyRefreshAccepted;
    private static long clientVoxyRefreshRejected;
    private static long resyncRequestLogWindowStartTick;
    private static int resyncRequestDetailedLogs;
    private static long resyncRequestSuppressedLogs;
    private static long resyncConvergedLogWindowStartTick;
    private static int resyncConvergedDetailedLogs;
    private static long resyncConvergedSuppressedLogs;
    private static boolean tintCacheResetPending;
    private static Method ingestMethod;
    private static boolean enabled;
    private static boolean permanentlyDisabled;

    private OceanCanvasVoxyCompat() {}

    public static void register() {
        OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-RENDERER-DIAG build={} sodium={} iris={} sspb={} voxy={}",
                net.oceancanvas.mod.OceanCanvas.VERSION,
                FabricLoader.getInstance().isModLoaded("sodium"),
                FabricLoader.getInstance().isModLoaded("iris"),
                FabricLoader.getInstance().isModLoaded("sspb"),
                FabricLoader.getInstance().isModLoaded("voxy"));
        // Register Ocean Canvas's own payload receiver regardless of whether Voxy
        // exists. With no Voxy it is a cheap no-op; this avoids making network
        // protocol handling itself conditional on an optional mod.
        ClientPlayNetworking.registerGlobalReceiver(OceanCanvasTerrainChangedPayload.TYPE,
                (payload, context) -> context.client().execute(() -> onTerrainChanged(payload)));

        // v253.34: near-field repair is a vanilla/Sodium correctness path, not a
        // Voxy feature. v253.28 accidentally registered the drain tick only after
        // Voxy's reflective hook succeeded, so the renderer rebuild could silently
        // disappear when Voxy was absent or its private API changed. Keep it alive
        // unconditionally; optional Voxy ingest happens only after the same barrier.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTickCounter++;
            flushClientResyncLogSummaries(false);
            runDueStrongCompiledGeometryInvalidation(client);
            drainPending(client, 64);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearClientRepairState());

        if (!FabricLoader.getInstance().isModLoaded("voxy")) return;
        try {
            Class<?> service = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService", false,
                    OceanCanvasVoxyCompat.class.getClassLoader());
            ingestMethod = service.getMethod("tryAutoIngestChunk", LevelChunk.class);
            enabled = true;
            OceanCanvas.LOGGER.info("(Ocean Canvas) Voxy detected; optional terrain re-ingest compatibility enabled.");
        } catch (Throwable t) {
            permanentlyDisable("Voxy was detected but its compatible ingest hook was not found", t);
            return;
        }
    }

    private static void onTerrainChanged(OceanCanvasTerrainChangedPayload payload) {
        // v253.78: the map cache is another consumer of the authoritative terrain-change
        // bus. Invalidate it before optional renderer/Voxy work so a closed or absent
        // compatibility adapter can never leave Ground Zero showing stale terrain.
        net.oceancanvas.mod.gui.OceanCanvasMapTerrain.invalidateActive(payload.chunks(), payload.kind());
        Minecraft client = Minecraft.getInstance();
        int loaded = 0, ingested = 0, pending = 0;
        int skySamples = 0, skyAboveMin = 16, skyAboveMax = -1, skyWaterMin = 16, skyWaterMax = -1, skyAboveNot15 = 0;
        int profileSamples = 0, profileMismatches = 0, readyChunks = 0;
        String firstProfileMismatch = "";
        long skyHash = 0xcbf29ce484222325L;
        boolean voxyActive = enabled && !permanentlyDisabled;
        if (client.level != null) {
            final int waterTop = net.oceancanvas.mod.config.OceanCanvasConfig.WATER_SURFACE_Y;
            for (long packed : payload.chunks()) {
                int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed);
                LevelChunk lc = getActuallyLoadedClientChunk(client, x, z);
                if (lc != null) {
                    loaded++;
                    int baseX = lc.getPos().getMinBlockX(), baseZ = lc.getPos().getMinBlockZ();
                    boolean chunkReady = true;
                    ClientReadiness immediateFailure = null;
                    int chunkCanonicalPairs = 0;
                    for (int lx : new int[]{2, 6, 10, 14}) for (int lz : new int[]{2, 6, 10, 14}) {
                        int bx = baseX + lx, bz = baseZ + lz;
                        BlockPos wp = new BlockPos(bx, waterTop, bz), ap = new BlockPos(bx, waterTop + 1, bz);
                        var ws = lc.getBlockState(wp);
                        var as = lc.getBlockState(ap);
                        profileSamples++;
                        boolean pureAboveFluid = as.is(Blocks.WATER) || as.is(Blocks.LAVA);
                        boolean flowingSurfaceWater = ws.is(Blocks.WATER) && !ws.getFluidState().isSource();
                        if (!ws.is(Blocks.WATER) || flowingSurfaceWater || !as.isAir()) {
                            profileMismatches++;
                            chunkReady = false;
                            if (immediateFailure == null) {
                                immediateFailure = ClientReadiness.fail(
                                        pureAboveFluid ? "ABOVE_SURFACE_FLUID_SURVIVOR"
                                                : (flowingSurfaceWater ? "FLOWING_SURFACE_WATER_SURVIVOR" : "BLOCK_STATE_STALE"),
                                        pureAboveFluid ? ap : wp, -1, -1, -1, -1);
                            }
                            if (firstProfileMismatch.isEmpty()) {
                                firstProfileMismatch = "chunk=" + x + "," + z + " pos=" + bx + "," + waterTop + "," + bz
                                        + " waterState=" + ws + " aboveState=" + as;
                            }
                            continue;
                        }
                        chunkCanonicalPairs++;
                        int a = client.level.getBrightness(LightLayer.SKY, ap);
                        int w = client.level.getBrightness(LightLayer.SKY, wp);
                        skySamples++;
                        skyAboveMin = Math.min(skyAboveMin, a); skyAboveMax = Math.max(skyAboveMax, a);
                        skyWaterMin = Math.min(skyWaterMin, w); skyWaterMax = Math.max(skyWaterMax, w);
                        if (a != 15) { skyAboveNot15++; chunkReady = false; }
                        // Canonical open surface water is exactly one skylight step
                        // below the unobstructed air above it. v253.33 telemetry
                        // repeatedly shows the healthy server pair as 15/14. Treat a
                        // stale water light value as client-not-ready even when the air
                        // sample has already reached 15.
                        if (w != Math.max(0, a - 1)) chunkReady = false;
                        if ((a != 15 || w != Math.max(0, a - 1)) && immediateFailure == null) {
                            immediateFailure = ClientReadiness.fail("SURFACE_SKYLIGHT_STALE", wp, a, w, 15, 14);
                        }
                        skyHash ^= (((long)bx) << 32) ^ (bz & 0xffffffffL) ^ ((long)a << 8) ^ w;
                        skyHash *= 0x100000001b3L;
                    }
                    if (chunkCanonicalPairs == 0) chunkReady = false;
                    if (chunkReady) readyChunks++;
                    // v253.45: only resident chunks can own a stale vanilla/Sodium
                    // near-field mesh. Far Pregen chunks previously filled this queue
                    // even though vanillaLoaded=0; when they later enter tracking range
                    // vanilla sends the then-current authoritative chunk/light packet.
                    // Restrict the repair queue to actual client residents so visible
                    // failures cannot be diluted by thousands of irrelevant far chunks.
                    remember(packed);
                    if ("VISIBLE_LIGHT_REPAIR".equals(payload.kind())) {
                        STRONG_GEOMETRY_REQUIRED.add(packed);
                    }
                    // v253.73.14 publication barrier. The server sends a complete
                    // chunk+light packet before this custom notification, but 73.13
                    // still observed hundreds of 3..14/2..13 samples in this handler
                    // followed by one-attempt convergence. Those are delivery/apply
                    // races, not evidence that another network resend is already
                    // required. Hold diagnosis/rebuild for two client ticks; the normal
                    // strict drain then resyncs immediately if the field is still bad.
                    // v253.73.15: do not extend the settle deadline when repeated publications
                    // arrive for a chunk that is already pending. v253.73.14 could keep a
                    // visibly black chunk permanently inside the two-tick grace period if
                    // the server republished it faster than the deadline could expire.
                    PUBLICATION_SETTLE_UNTIL_TICK.putIfAbsent(packed, clientTickCounter + PUBLICATION_SETTLE_TICKS);
                    // Biome data and its rendered water tint are independently cached
                    // client-side. Every authoritative terrain publication can carry a
                    // biome rewrite, so invalidate the tint cache once on the next
                    // client tick before section meshes are rebuilt.
                    tintCacheResetPending = true;
                    pending++;
                } else if (isNearClientPlayer(client, x, z)) {
                    // v253.61.10: do not lose the rejoin repair merely because the
                    // custom invalidation raced a few ticks ahead of vanilla's chunk
                    // installation. The END_CLIENT_TICK drain will wait without
                    // consuming its loaded-work budget, then prove light and dirty
                    // the renderer as soon as this exact chunk becomes resident.
                    rememberAwaitingClientLoad(packed);
                    pending++;
                }
            }
        }
        // v253.73.14: do not flush a publication-time resync here. The already-sent
        // vanilla packet owns the short settle window; drainPending performs the first
        // authoritative diagnosis after that barrier and flushes only persistent faults.
        if (skySamples == 0) { skyAboveMin = skyAboveMax = skyWaterMin = skyWaterMax = -1; }
        if (loaded > 0 && (profileMismatches > 0 || skyAboveNot15 > 0 || readyChunks < loaded)) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-ROOT-CAUSE build={} kind={} loadedChunks={} readyChunks={} profileSamples={} profileMismatches={} skySamples={} skyAboveNot15={} firstProfileMismatch={} classification={} action=hold-voxy-ingest-until-client-converges",
                    net.oceancanvas.mod.OceanCanvas.VERSION, payload.kind(), loaded, readyChunks, profileSamples, profileMismatches,
                    skySamples, skyAboveNot15, firstProfileMismatch.isEmpty() ? "none" : firstProfileMismatch,
                    profileMismatches > 0 ? "CLIENT_BLOCK_STATE_LAG" : (skyAboveNot15 > 0 ? "CLIENT_SKYLIGHT_LAG" : "CLIENT_NOT_YET_CANONICAL"));
        }
        // Telemetry-only acknowledgement. This proves the final invalidation reached
        // the client and whether Voxy could immediately ingest the now-authoritative
        // client LevelChunk. Server completion never depends on this acknowledgement.
        try {
            ClientPlayNetworking.send(new OceanCanvasTerrainAckPayload(
                    payload.kind(), payload.chunks().size(), loaded, ingested, pending, voxyActive,
                    skySamples, skyAboveMin, skyAboveMax, skyWaterMin, skyWaterMax, skyAboveNot15, skyHash));
        } catch (Throwable t) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-FINALIZE-ACK send failed: {}", t.toString());
        }
        OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-FINALIZE build={} kind={} received={} vanillaLoaded={} clientReady={} profileSamples={} profileMismatches={} voxyEnabled={} voxyIngested={} voxyPending={} skySamples={} skyAbove={}..{} skyWater={}..{} skyAboveNot15={} skyHash={}",
                net.oceancanvas.mod.OceanCanvas.VERSION, payload.kind(), payload.chunks().size(), loaded, readyChunks, profileSamples, profileMismatches,
                voxyActive, ingested, pending, skySamples, skyAboveMin, skyAboveMax, skyWaterMin, skyWaterMax, skyAboveNot15, Long.toUnsignedString(skyHash));
    }

    private static void drainPending(Minecraft client, int budget) {
        if (client.level == null) return;
        if (tintCacheResetPending) {
            try {
                client.level.clearTintCaches();
                clientTintCacheResets++;
                tintCacheResetPending = false;
            } catch (Throwable t) {
                // Keep the flag armed. A transient client lifecycle race must not
                // permanently preserve stale biome-water colors.
                OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-TINT-CACHE-RESET failed; will retry: {}", t.toString());
            }
        }
        if (PENDING.isEmpty()) {
            flushResyncRequests();
            return;
        }

        // v253.45 closes the second starvation mode exposed by v253.44. v253.34
        // correctly stopped unloaded entries from consuming budget, but a loaded
        // chunk whose client skylight never converged still occupied one of the
        // first budget slots every tick forever. Rotate unfinished loaded work to
        // the tail so every resident chunk receives repair opportunities.
        int worked = 0;
        // v253.125.47: preserve the exact one-pass snapshot semantics without allocating
        // an ArrayList<Long> every client tick. The scratch buffer grows only when a
        // session reaches a new high-water mark, then is reused. Mutating PENDING while
        // processing the scratch remains safe and keeps unfinished work rotating.
        PENDING_ITERATION_SCRATCH.clear();
        for (LongIterator it = PENDING.iterator(); it.hasNext();) PENDING_ITERATION_SCRATCH.add(it.nextLong());
        for (int pendingIndex = 0; pendingIndex < PENDING_ITERATION_SCRATCH.size(); pendingIndex++) {
            if (worked >= budget) break;
            long packed = PENDING_ITERATION_SCRATCH.getLong(pendingIndex);
            int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed);
            LevelChunk loadedChunk = getActuallyLoadedClientChunk(client, x, z);
            if (loadedChunk == null) {
                long deadline = AWAIT_CLIENT_LOAD_DEADLINE.get(packed);
                if (deadline != NO_LONG_STATE && clientTickCounter >= deadline) {
                    clientAwaitLoadExpired++;
                    forget(packed);
                }
                continue;
            }
            if (AWAIT_CLIENT_LOAD_DEADLINE.remove(packed) != NO_LONG_STATE) {
                clientAwaitLoadActivated++;
                OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-REJOIN-CHUNK-READY build={} chunk={},{} waitedQueueRemaining={} action=prove-client-light-and-dirty-renderer",
                        net.oceancanvas.mod.OceanCanvas.VERSION, x, z, AWAIT_CLIENT_LOAD_DEADLINE.size());
            }
            worked++;
            if (tryConvergeRebuildAndIngest(client, packed, x, z, loadedChunk)) {
                forget(packed);
            } else {
                defer(packed);
            }
        }
        flushResyncRequests();
    }

    /**
     * v253.28 near-field renderer barrier. The v253.27 runtime proved the server
     * field was clean (zero skylight anomalies) while the visual defect existed
     * only inside vanilla/Sodium render distance and vanished when Voxy took over.
     * That signature points at a stale near-field section mesh/light bake, not the
     * physical ocean floor and not the authoritative server light arrays.
     *
     * v253.34 introduced a multi-tick readiness/rebuild barrier; v253.46 uses eight consecutive ticks and
     * validates deep open-water skylight as well as the surface. The remaining
     * spots are floor-visible, so surface-only readiness could release a section
     * while its lower water-column light was still stale. Voxy, when present, is
     * handed the chunk only after the same renderer barrier.
     */
    private static boolean tryConvergeRebuildAndIngest(Minecraft client, long packed, int x, int z, LevelChunk chunk) {
        try {
            if (client.level == null || chunk == null) {
                READY_STREAK.remove(packed);
                return false;
            }

            long publicationSettleUntil = PUBLICATION_SETTLE_UNTIL_TICK.get(packed);
            if (publicationSettleUntil != NO_LONG_STATE && clientTickCounter < publicationSettleUntil) {
                READY_STREAK.remove(packed);
                return false;
            }
            if (publicationSettleUntil != NO_LONG_STATE) PUBLICATION_SETTLE_UNTIL_TICK.remove(packed);

            // v253.125.13: a private Voxy refresh that explicitly declined the
            // canonical snapshot keeps this near-field repair pending, but it must
            // not force another full readiness scan/renderer rebuild every client
            // tick while the bounded Voxy retry delay is still active. Re-proof the
            // vanilla/Sodium field when the retry becomes due.
            long voxyRetryNotBefore = VOXY_REINGEST_NOT_BEFORE_TICK.get(packed);
            if (voxyRetryNotBefore != NO_LONG_STATE && clientTickCounter < voxyRetryNotBefore) {
                return false;
            }

            ClientReadiness readiness = inspectClientChunkReadiness(client, chunk);
            // v253.72.6: never convert DEEP_SKYLIGHT_OVERBRIGHT into READY.
            // v253.72.5 proved that accepted positive deep SKY is the visible slab
            // defect. Keep renderer/Voxy blocked and keep requesting authoritative
            // light until the strict readiness proof actually passes.
            if (!readiness.ready()) {
                READY_STREAK.remove(packed);
                int failures = FAILURE_STREAK.getOrDefault(packed, 0) + 1;
                FAILURE_STREAK.put(packed, failures);

                // v253.73.15 supersession note: direct client SKY storage repair is
                // intentionally disabled. The legacy hook below returns zero and can
                // never certify success; authoritative resync, strict readiness/seam
                // proof, and renderer invalidation own recovery on current builds.
                if ("DEEP_SKYLIGHT_OVERBRIGHT".equals(readiness.reason())) {
                    int localAttempt = LOCAL_DEEP_ZERO_REPAIR_ATTEMPTS.getOrDefault(packed, 0);
                    if (localAttempt < MAX_LOCAL_DEEP_ZERO_REPAIR_ATTEMPTS) {
                        int repairedSections = repairClientDeterministicDeepZeroSkyStorage(client, chunk);
                        LOCAL_DEEP_ZERO_REPAIR_ATTEMPTS.put(packed, localAttempt + 1);
                        if (repairedSections > 0) {
                            localDeepZeroRepairs++;
                            localDeepZeroSections += repairedSections;
                            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-DEEP-ZERO-REPAIR build={} chunk={},{} attempt={} sections={} action=legacy-local-repair-hook-reported-work-dirty-radius2; authoritative-server-repair-still-requested",
                                    net.oceancanvas.mod.OceanCanvas.VERSION, x, z, localAttempt + 1, repairedSections);
                            forceNearFieldSectionRebuild(client, chunk, RESYNC_REBUILD_RADIUS_CHUNKS);
                        } else {
                            localDeepZeroInconclusive++;
                        }
                    }
                }

                // v253.72.3: invalidate the stale mesh immediately, not only after
                // light happens to read canonical. Keep the cheap radius-1 dirty on
                // every bad tick; expand to radius 2 only when this tick actually
                // emits a network resync request, avoiding a 25-chunk rebuild storm.
                forceNearFieldSectionRebuild(client, chunk, 1);
                maybeRequestAuthoritativeLightResync(packed, x, z, failures, readiness);
                long justRequested = LAST_RESYNC_REQUEST_TICK.get(packed);
                if (justRequested != NO_LONG_STATE && justRequested == clientTickCounter) {
                    forceNearFieldSectionRebuild(client, chunk, RESYNC_REBUILD_RADIUS_CHUNKS);
                }
                return false;
            }

            FAILURE_STREAK.remove(packed);
            LOCAL_DEEP_ZERO_REPAIR_ATTEMPTS.remove(packed);
            int resyncAttempts = RESYNC_ATTEMPTS.get(packed);
            boolean needsStrongGeometry = STRONG_GEOMETRY_REQUIRED.contains(packed)
                    || (resyncAttempts != NO_INT_STATE && resyncAttempts > 0);
            long lastResyncTick = LAST_RESYNC_REQUEST_TICK.get(packed);
            if (resyncAttempts != NO_INT_STATE && resyncAttempts > 0 && lastResyncTick != NO_LONG_STATE
                    && clientTickCounter - lastResyncTick < POST_RESYNC_QUIET_TICKS) {
                READY_STREAK.remove(packed);
                clientPostResyncConvergenceHolds++;
                forceNearFieldSectionRebuild(client, chunk, RESYNC_REBUILD_RADIUS_CHUNKS);
                return false;
            }

            long strongTargetEpoch = STRONG_GEOMETRY_TARGET_EPOCH.get(packed);
            if (needsStrongGeometry && strongTargetEpoch != NO_LONG_STATE
                    && strongGeometryEpoch < strongTargetEpoch) {
                clientStrongGeometryWaits++;
                READY_STREAK.put(packed, 0);
                forceNearFieldSectionRebuild(client, chunk, RESYNC_REBUILD_RADIUS_CHUNKS);
                return false;
            }

            // Do not count a readiness tick unless the renderer was actually dirtied.
            // Resynced chunks use radius 2 because the observed glitch crossed chunk
            // edges; ordinary healthy publication retains the cheaper 3x3 radius-1 path.
            int rebuildRadius = resyncAttempts != NO_INT_STATE && resyncAttempts > 0 ? RESYNC_REBUILD_RADIUS_CHUNKS : 1;
            if (!forceNearFieldSectionRebuild(client, chunk, rebuildRadius)) {
                READY_STREAK.remove(packed);
                return false;
            }

            int streak = READY_STREAK.getOrDefault(packed, 0) + 1;
            READY_STREAK.put(packed, streak);

            // Eight CONSECUTIVE canonical scans happen after the post-resync quiet
            // window. A one-tick 15/14 value can no longer clear the repair state.
            if (streak < RENDER_READY_STREAK_REQUIRED) return false;

            if (needsStrongGeometry && strongTargetEpoch == NO_LONG_STATE) {
                long targetEpoch = strongGeometryEpoch + 1L;
                STRONG_GEOMETRY_TARGET_EPOCH.put(packed, targetEpoch);
                STRONG_GEOMETRY_NOT_BEFORE_TICK.put(packed,
                        clientTickCounter + STRONG_GEOMETRY_INVALIDATE_DELAY_TICKS);
                READY_STREAK.put(packed, 0);
                clientStrongGeometryWaits++;
                return false;
            }
            if (needsStrongGeometry) {
                // Reaching this point means the requested global compiled-geometry
                // generation has happened and another full canonical streak has passed.
                STRONG_GEOMETRY_REQUIRED.remove(packed);
                STRONG_GEOMETRY_TARGET_EPOCH.remove(packed);
                STRONG_GEOMETRY_NOT_BEFORE_TICK.remove(packed);
            }

            if (resyncAttempts != NO_INT_STATE && resyncAttempts > 0) {
                long recovered = ++clientLightRecoveries;
                logClientResyncConverged(x, z, resyncAttempts, streak, recovered);
                RESYNC_ATTEMPTS.remove(packed);
                NEXT_RESYNC_TICK.remove(packed);
                LAST_RESYNC_REQUEST_TICK.remove(packed);
                RESYNC_REQUEST_QUEUE.remove(packed);
            }

            if (enabled && !permanentlyDisabled && ingestMethod != null) {
                try {
                    Object result = ingestMethod.invoke(null, chunk);
                    boolean accepted = !(result instanceof Boolean b) || b.booleanValue();
                    if (accepted) {
                        clientVoxyRefreshAccepted++;
                        VOXY_REINGEST_ATTEMPTS.remove(packed);
                        VOXY_REINGEST_NOT_BEFORE_TICK.remove(packed);
                    } else {
                        clientVoxyRefreshRejected++;
                        int voxyAttempt = VOXY_REINGEST_ATTEMPTS.getOrDefault(packed, 0) + 1;
                        VOXY_REINGEST_ATTEMPTS.put(packed, voxyAttempt);
                        if (voxyAttempt < VOXY_REINGEST_MAX_ATTEMPTS) {
                            long retryTicks = VOXY_REINGEST_BASE_RETRY_TICKS << Math.min(2, voxyAttempt - 1);
                            VOXY_REINGEST_NOT_BEFORE_TICK.put(packed, clientTickCounter + retryTicks);
                            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-VOXY-REINGEST-DEFERRED build={} chunk={},{} attempt={}/{} retryTicks={} action=retain-nearfield-repair-until-bounded-voxy-retry",
                                    net.oceancanvas.mod.OceanCanvas.VERSION, x, z, voxyAttempt, VOXY_REINGEST_MAX_ATTEMPTS, retryTicks);
                            return false;
                        }
                        // Voxy is optional and uses a private, version-fragile API. After
                        // bounded retries, release the vanilla/Sodium repair rather than
                        // pinning this chunk forever; the authoritative client field is
                        // already strictly canonical at this point.
                        VOXY_REINGEST_ATTEMPTS.remove(packed);
                        VOXY_REINGEST_NOT_BEFORE_TICK.remove(packed);
                        OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-VOXY-REINGEST-REJECTED build={} chunk={},{} attempts={} action=bounded-retries-exhausted-release-vanilla-correctness; voxy-cache-refresh-not-proven",
                                net.oceancanvas.mod.OceanCanvas.VERSION, x, z, voxyAttempt);
                    }
                } catch (Throwable t) {
                    permanentlyDisable("Voxy terrain re-ingest failed", t);
                }
            }
            return true;
        } catch (Throwable t) {
            // Never convert a repair exception into success. v253.44 did exactly
            // that and silently removed the chunk from PENDING after a failed rebuild.
            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-NEARFIELD-REBUILD failed for chunk {},{}; retaining and rotating repair entry: {}",
                    x, z, t.toString());
            READY_STREAK.remove(packed);
            return false;
        }
    }

    private static int requiredResyncFailureStreak(ClientReadiness readiness) {
        if (readiness != null && "SURFACE_SKYLIGHT_STALE".equals(readiness.reason())
                && readiness.expectedAbove() == 15 && readiness.expectedWater() == 14) {
            if (readiness.actualAbove() == 15 && readiness.actualWater() == 15) {
                return RESYNC_FAILURE_STREAK_REQUIRED_SURFACE_15_15;
            }
            if (readiness.actualAbove() == 0 && readiness.actualWater() == 0) {
                return RESYNC_FAILURE_STREAK_REQUIRED_SURFACE_0_0;
            }
        }
        return RESYNC_FAILURE_STREAK_REQUIRED_URGENT;
    }

    /**
     * v253.125.15 log-pressure containment. A persistent deep-light island is still
     * checked every client tick, but the 125.14 runtime emitted more than thirteen
     * thousand identical WARN lines for two chunks. The resync request itself already
     * carries the same sample/value evidence. Emit the root-cause detail only when the
     * chunk is eligible for another bounded resync wave.
     */
    private static boolean shouldLogDeepRootCause(long packed) {
        long lastRequest = LAST_RESYNC_REQUEST_TICK.get(packed);
        return lastRequest == NO_LONG_STATE || clientTickCounter - lastRequest >= RESYNC_MAX_COOLDOWN_TICKS;
    }

    private static void flushClientResyncLogSummaries(boolean force) {
        if ((resyncRequestSuppressedLogs > 0L)
                && (force || clientTickCounter - resyncRequestLogWindowStartTick >= RESYNC_LOG_WINDOW_TICKS)) {
            OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-LIGHT-RESYNC-REQUEST-SUMMARY build={} suppressedDetails={} windowTicks={} queuedNow={} action=retain-aggregate-evidence-without-render-thread-log-storm",
                    net.oceancanvas.mod.OceanCanvas.VERSION, resyncRequestSuppressedLogs,
                    Math.max(0L, clientTickCounter - resyncRequestLogWindowStartTick), RESYNC_REQUEST_QUEUE.size());
            resyncRequestSuppressedLogs = 0L;
            resyncRequestDetailedLogs = 0;
            resyncRequestLogWindowStartTick = clientTickCounter;
        }
        if ((resyncConvergedSuppressedLogs > 0L)
                && (force || clientTickCounter - resyncConvergedLogWindowStartTick >= RESYNC_LOG_WINDOW_TICKS)) {
            OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-LIGHT-RESYNC-CONVERGED-SUMMARY build={} suppressedDetails={} windowTicks={} totalRecoveries={} pending={} action=retain-aggregate-evidence-without-render-thread-log-storm",
                    net.oceancanvas.mod.OceanCanvas.VERSION, resyncConvergedSuppressedLogs,
                    Math.max(0L, clientTickCounter - resyncConvergedLogWindowStartTick), clientLightRecoveries, PENDING.size());
            resyncConvergedSuppressedLogs = 0L;
            resyncConvergedDetailedLogs = 0;
            resyncConvergedLogWindowStartTick = clientTickCounter;
        }
    }

    private static void logClientResyncRequest(int x, int z, int attempt, int failures, ClientReadiness readiness, int requiredFailures, long cooldown) {
        if (resyncRequestLogWindowStartTick == 0L) resyncRequestLogWindowStartTick = clientTickCounter;
        if (clientTickCounter - resyncRequestLogWindowStartTick >= RESYNC_LOG_WINDOW_TICKS) {
            if (resyncRequestSuppressedLogs > 0L) {
                OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-LIGHT-RESYNC-REQUEST-SUMMARY build={} suppressedDetails={} windowTicks={} queuedNow={} action=retain-aggregate-evidence-without-render-thread-log-storm",
                        net.oceancanvas.mod.OceanCanvas.VERSION, resyncRequestSuppressedLogs,
                        Math.max(0L, clientTickCounter - resyncRequestLogWindowStartTick), RESYNC_REQUEST_QUEUE.size());
            }
            resyncRequestSuppressedLogs = 0L;
            resyncRequestDetailedLogs = 0;
            resyncRequestLogWindowStartTick = clientTickCounter;
        }
        boolean detailed = attempt > 1 || resyncRequestDetailedLogs < RESYNC_DETAIL_LOGS_PER_WINDOW;
        if (!detailed) {
            resyncRequestSuppressedLogs++;
            return;
        }
        resyncRequestDetailedLogs++;
        OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-LIGHT-RESYNC-REQUEST build={} chunk={},{} attempt={} consecutiveFailures={} reason={} sample={} actual={}/{} expected={}/{} requiredFailures={} cooldownTicks={} action=request-full-authoritative-chunk-light",
                net.oceancanvas.mod.OceanCanvas.VERSION, x, z, attempt, failures, readiness.reason(),
                readiness.sample() == null ? "none" : (readiness.sample().getX() + "," + readiness.sample().getY() + "," + readiness.sample().getZ()),
                readiness.actualAbove(), readiness.actualWater(), readiness.expectedAbove(), readiness.expectedWater(), requiredFailures, cooldown);
    }

    private static void logClientResyncConverged(int x, int z, int attempts, int streak, long recovered) {
        if (resyncConvergedLogWindowStartTick == 0L) resyncConvergedLogWindowStartTick = clientTickCounter;
        if (clientTickCounter - resyncConvergedLogWindowStartTick >= RESYNC_LOG_WINDOW_TICKS) {
            if (resyncConvergedSuppressedLogs > 0L) {
                OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-LIGHT-RESYNC-CONVERGED-SUMMARY build={} suppressedDetails={} windowTicks={} totalRecoveries={} pending={} action=retain-aggregate-evidence-without-render-thread-log-storm",
                        net.oceancanvas.mod.OceanCanvas.VERSION, resyncConvergedSuppressedLogs,
                        Math.max(0L, clientTickCounter - resyncConvergedLogWindowStartTick), clientLightRecoveries, PENDING.size());
            }
            resyncConvergedSuppressedLogs = 0L;
            resyncConvergedDetailedLogs = 0;
            resyncConvergedLogWindowStartTick = clientTickCounter;
        }
        if (resyncConvergedDetailedLogs >= RESYNC_DETAIL_LOGS_PER_WINDOW) {
            resyncConvergedSuppressedLogs++;
            return;
        }
        resyncConvergedDetailedLogs++;
        OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-LIGHT-RESYNC-CONVERGED build={} chunk={},{} attempts={} canonicalStreak={} quietTicks={} recoveries={} action=release-after-post-resync-proof-compiled-geometry-generation-and-wide-renderer-rebuild",
                net.oceancanvas.mod.OceanCanvas.VERSION, x, z, attempts, streak, POST_RESYNC_QUIET_TICKS, recovered);
    }

    private static void maybeRequestAuthoritativeLightResync(long packed, int x, int z, int failures, ClientReadiness readiness) {
        int requiredFailures = requiredResyncFailureStreak(readiness);
        if (failures < requiredFailures) return;
        long nextAllowed = NEXT_RESYNC_TICK.getOrDefault(packed, 0L);
        if (clientTickCounter < nextAllowed) return;

        int attempt = RESYNC_ATTEMPTS.getOrDefault(packed, 0) + 1;
        RESYNC_ATTEMPTS.put(packed, attempt);
        long cooldown = Math.min(RESYNC_MAX_COOLDOWN_TICKS,
                RESYNC_BASE_COOLDOWN_TICKS * (1L << Math.min(3, Math.max(0, attempt - 1))));
        NEXT_RESYNC_TICK.put(packed, clientTickCounter + cooldown);
        LAST_RESYNC_REQUEST_TICK.put(packed, clientTickCounter);
        RESYNC_REQUEST_QUEUE.add(packed);
        logClientResyncRequest(x, z, attempt, failures, readiness, requiredFailures, cooldown);
    }

    private static void flushResyncRequests() {
        if (RESYNC_REQUEST_QUEUE.isEmpty()) return;
        java.util.List<Long> batch = new java.util.ArrayList<>(OceanCanvasTerrainRepairRequestPayload.MAX_CHUNKS_PER_PACKET);
        LongIterator it = RESYNC_REQUEST_QUEUE.iterator();
        while (it.hasNext() && batch.size() < Math.min(RESYNC_SEND_CHUNKS_PER_CLIENT_TICK, OceanCanvasTerrainRepairRequestPayload.MAX_CHUNKS_PER_PACKET)) {
            batch.add(Long.valueOf(it.nextLong()));
            it.remove();
        }
        try {
            ClientPlayNetworking.send(new OceanCanvasTerrainRepairRequestPayload(batch));
            clientLightResyncRequests++;
            clientLightResyncChunks += batch.size();
        } catch (Throwable t) {
            // Restore the batch so a transient networking failure cannot discard the
            // only active repair path. Cooldowns still bound repeated requests.
            for (Long packed : batch) RESYNC_REQUEST_QUEUE.add(packed.longValue());
            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-LIGHT-RESYNC-REQUEST send failed; retained {} chunks for retry: {}",
                    batch.size(), t.toString());
        }
    }

    /**
     * v253.125.7 strong client renderer barrier. Section dirtying only queues replacement
     * meshes and can race an older async compile back into the visible set. 26.2 exposes
     * invalidateCompiledGeometry as the replacement for the old allChanged() path; one
     * coalesced call creates a new compiled-geometry generation for every visible repair
     * in the wave, after authoritative light arrays have already proved canonical.
     */
    private static int[] refreshVoxyAroundStrongGeometryWave(Minecraft client, LongLinkedOpenHashSet wave, int radiusChunks) {
        if (!enabled || permanentlyDisabled || ingestMethod == null || client.level == null || wave == null || wave.isEmpty()) {
            return new int[]{0, 0, 0};
        }
        LongLinkedOpenHashSet refresh = new LongLinkedOpenHashSet();
        int radius = Math.max(0, Math.min(2, radiusChunks));
        outer:
        for (LongIterator waveIt = wave.iterator(); waveIt.hasNext();) {
            long packed = waveIt.nextLong();
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    refresh.add(ChunkPos.pack(cx + dx, cz + dz));
                    if (refresh.size() >= 96) break outer;
                }
            }
        }
        int loaded = 0, accepted = 0, rejected = 0;
        for (LongIterator refreshIt = refresh.iterator(); refreshIt.hasNext();) {
            long packed = refreshIt.nextLong();
            LevelChunk lc = getActuallyLoadedClientChunk(client, ChunkPos.getX(packed), ChunkPos.getZ(packed));
            if (lc == null) continue;
            loaded++;
            try {
                Object result = ingestMethod.invoke(null, lc);
                boolean ok = !(result instanceof Boolean b) || b.booleanValue();
                if (ok) { accepted++; clientVoxyRefreshAccepted++; }
                else { rejected++; clientVoxyRefreshRejected++; }
            } catch (Throwable ex) {
                rejected++; clientVoxyRefreshRejected++;
                permanentlyDisable("Voxy strong-geometry wave re-ingest failed", ex);
                break;
            }
        }
        return new int[]{loaded, accepted, rejected};
    }

    private static void runDueStrongCompiledGeometryInvalidation(Minecraft client) {
        if (STRONG_GEOMETRY_NOT_BEFORE_TICK.isEmpty() || client.level == null
                || client.levelRenderer == null || client.gameRenderer == null) return;
        long earliestDue = Long.MAX_VALUE;
        long nextTargetEpoch = Long.MAX_VALUE;
        int waiting = 0;
        LongLinkedOpenHashSet dueWave = new LongLinkedOpenHashSet();
        for (var entry : STRONG_GEOMETRY_NOT_BEFORE_TICK.long2LongEntrySet()) {
            long packed = entry.getLongKey();
            long target = STRONG_GEOMETRY_TARGET_EPOCH.get(packed);
            if (target == NO_LONG_STATE || target <= strongGeometryEpoch) continue;
            waiting++;
            dueWave.add(packed);
            earliestDue = Math.min(earliestDue, entry.getLongValue());
            nextTargetEpoch = Math.min(nextTargetEpoch, target);
        }
        if (waiting == 0 || earliestDue == Long.MAX_VALUE || clientTickCounter < earliestDue) return;
        if (clientTickCounter - lastStrongGeometryInvalidateTick < STRONG_GEOMETRY_INVALIDATE_MIN_INTERVAL_TICKS) return;
        try {
            client.levelRenderer.invalidateCompiledGeometry(
                    client.level, client.options, client.gameRenderer.mainCamera(), client.getBlockColors());
            strongGeometryEpoch = Math.max(strongGeometryEpoch + 1L, nextTargetEpoch);
            lastStrongGeometryInvalidateTick = clientTickCounter;
            clientStrongGeometryInvalidations++;
            tintCacheResetPending = true;
            // v253.125.9: the fixed repro survives vanilla/Sodium's full compiled-geometry
            // invalidation while Voxy is active. Refresh Voxy from the same canonical
            // client light arrays for the repaired chunks plus one loaded neighbor ring so
            // parent LOD mips cannot retain the pre-repair black-light snapshot.
            int[] voxyRefresh = refreshVoxyAroundStrongGeometryWave(client, dueWave, 1);
            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-COMPILED-GEOMETRY-INVALIDATE build={} epoch={} waitingChunks={} pending={} tick={} voxyRefreshLoaded={} voxyRefreshAccepted={} voxyRefreshRejected={} action=26.2-invalidateCompiledGeometry-plus-canonical-voxy-neighbor-reingest",
                    net.oceancanvas.mod.OceanCanvas.VERSION, strongGeometryEpoch, waiting, PENDING.size(), clientTickCounter, voxyRefresh[0], voxyRefresh[1], voxyRefresh[2]);
        } catch (Throwable t) {
            // Fail closed: chunks keep their target epoch and remain pending. A later
            // client tick retries rather than declaring the visual repair complete.
            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-COMPILED-GEOMETRY-INVALIDATE failed; retaining {} visible repairs for retry: {}",
                    waiting, t.toString());
        }
    }

    private static boolean forceNearFieldSectionRebuild(Minecraft client, LevelChunk chunk, int radiusChunks) {
        if (client.level == null || client.levelExtractor == null) return false;
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        var cfg = net.oceancanvas.mod.config.OceanCanvasConfig.get();
        int floorY = cfg.oceanFloorY();
        int floorVariation = Math.max(0, cfg.oceanFloorVariation());
        int waterTop = net.oceancanvas.mod.config.OceanCanvasConfig.WATER_SURFACE_Y;
        int minSection = SectionPos.blockToSectionCoord(floorY - floorVariation - 16);
        int maxSection = SectionPos.blockToSectionCoord(waterTop + 16);
        int radius = Math.max(1, Math.min(RESYNC_REBUILD_RADIUS_CHUNKS, radiusChunks));
        if (radius > 1) clientWideSectionRebuilds++;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int sy = minSection; sy <= maxSection; sy++) {
                    client.levelExtractor.setSectionDirty(cx + dx, sy, cz + dz);
                }
            }
        }
        long n = ++nearFieldRebuilds;
        if ((n & 127L) == 0L) {
            OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-NEARFIELD-REBUILD build={} rebuilds={} wideRebuilds={} convergenceHolds={} pending={} awaitingLoad={} awaitQueued={} awaitActivated={} awaitExpired={} surfaceScans={} surfaceFailures={} deepScans={} deepFailures={} deepOverbrightFailures={} floorBandScans={} floorBandFailures={} localDeepZeroRepairs={} localDeepZeroSections={} localDeepZeroInconclusive={} resyncRequests={} resyncChunks={} recoveredAfterResync={} tintCacheResets={} strongGeometryInvalidations={} strongGeometryWaits={} strongGeometryEpoch={} voxyRefreshAccepted={} voxyRefreshRejected={} rendererDirtyApi=LevelExtractor+LevelRenderer action=dirty-sections-hold-visible-repairs-and-refresh-voxy-after-26.2-compiled-geometry-generation",
                    net.oceancanvas.mod.OceanCanvas.VERSION, n, clientWideSectionRebuilds, clientPostResyncConvergenceHolds, PENDING.size(), AWAIT_CLIENT_LOAD_DEADLINE.size(), clientAwaitLoadQueued, clientAwaitLoadActivated, clientAwaitLoadExpired,
                    fullClientLightScans, fullClientLightFailures, deepClientLightScans, deepClientLightFailures, deepClientOverbrightFailures, floorBandClientScans, floorBandClientFailures, localDeepZeroRepairs, localDeepZeroSections, localDeepZeroInconclusive, clientLightResyncRequests, clientLightResyncChunks, clientLightRecoveries, clientTintCacheResets,
                    clientStrongGeometryInvalidations, clientStrongGeometryWaits, strongGeometryEpoch, clientVoxyRefreshAccepted, clientVoxyRefreshRejected);
        }
        return true;
    }

    /**
     * v253.27 cache-publication gate. A LevelChunk being present in ClientLevel is
     * insufficient; the authoritative block/light packet can have arrived while
     * renderer/cache work is still catching up. Requiring canonical water/air and
     * sky=15 at a deterministic 4x4 sample prevents Voxy from snapshotting the exact
     * stale intermediate state that produced black rectangles in v253.26.
     */
    private record ClientReadiness(boolean ready, String reason, BlockPos sample,
                                   int actualAbove, int actualWater, int expectedAbove, int expectedWater) {
        private static ClientReadiness ok() {
            return new ClientReadiness(true, "READY", null, -1, -1, -1, -1);
        }

        private static ClientReadiness fail(String reason, BlockPos sample, int actualAbove, int actualWater,
                                            int expectedAbove, int expectedWater) {
            return new ClientReadiness(false, reason, sample, actualAbove, actualWater, expectedAbove, expectedWater);
        }
    }

    private static ClientReadiness inspectClientChunkReadiness(Minecraft client, LevelChunk chunk) {
        if (client.level == null) return ClientReadiness.fail("NO_CLIENT_LEVEL", null, -1, -1, -1, -1);
        final int waterTop = net.oceancanvas.mod.config.OceanCanvasConfig.WATER_SURFACE_Y;
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        fullClientLightScans++;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int bx = baseX + lx, bz = baseZ + lz;
                BlockPos wp = new BlockPos(bx, waterTop, bz), ap = new BlockPos(bx, waterTop + 1, bz);
                var waterState = chunk.getBlockState(wp);
                var aboveState = chunk.getBlockState(ap);
                // v253.72.3: a pure fluid immediately above sea level is a physical
                // waterfall survivor, not a reason to skip this column. Reporting it
                // through the existing targeted repair request lets the server perform
                // the fluid-only active-Pregen repair before the next authoritative send.
                // Waterlogged/solid structure blocks remain outside this client heuristic.
                if (aboveState.is(Blocks.WATER) || aboveState.is(Blocks.LAVA)) {
                    fullClientLightFailures++;
                    return ClientReadiness.fail("ABOVE_SURFACE_FLUID_SURVIVOR", ap, -1, -1, 15, 14);
                }
                if (waterState.is(Blocks.WATER) && !waterState.getFluidState().isSource()) {
                    fullClientLightFailures++;
                    return ClientReadiness.fail("FLOWING_SURFACE_WATER_SURVIVOR", wp, -1, -1, 15, 14);
                }
                // Do not let kelp/seagrass/structures veto renderer convergence for
                // an entire chunk. Only canonical open-water columns participate in
                // the light proof after the fluid-survivor checks above.
                if (!waterState.is(Blocks.WATER) || !aboveState.isAir()) continue;
                int aboveSky = client.level.getBrightness(LightLayer.SKY, ap);
                int waterSky = client.level.getBrightness(LightLayer.SKY, wp);
                int expectedWater = Math.max(0, aboveSky - 1);
                if (aboveSky != 15 || waterSky != expectedWater) {
                    fullClientLightFailures++;
                    return ClientReadiness.fail("SURFACE_SKYLIGHT_STALE", wp, aboveSky, waterSky, 15, 14);
                }
            }
        }

        // A structure-heavy chunk can legitimately have few or no open-water
        // surface columns. v253.44's arbitrary >=64 requirement made such chunks
        // permanently unfinishable. A resident FULL chunk still needs its renderer
        // dirtied even when there is no open-water surface column to certify.

        // v253.37: surface light can be correct while the renderer-visible lower
        // water column is stale, but a loaded neighboring chunk is not a safe
        // correctness oracle at a Pregen edge. Prove only uninterrupted plain-water
        // shafts against the deterministic sequence implied by the certified 15/14
        // surface pair: requiredMin=max(0,14-depth); brighter lateral skylight is valid.
        deepClientLightScans++;
        for (int depth : DEEP_SKY_SAMPLE_DEPTHS) {
            int y = waterTop - depth;
            int samples = 0;
            int requiredMin = minimumClientPlainWaterSkyAtDepth(depth);
            BlockPos firstMismatch = null;
            int firstMismatchSky = -1;
            for (int lx = 1; lx < 16; lx += 2) {
                for (int lz = 1; lz < 16; lz += 2) {
                    int bx = baseX + lx, bz = baseZ + lz;
                    if (!clientHasDirectWaterShaft(chunk, bx, bz, y, waterTop)) continue;
                    BlockPos sample = new BlockPos(bx, y, bz);
                    int sky = client.level.getBrightness(LightLayer.SKY, sample);
                    samples++;
                    if (sky < requiredMin && firstMismatch == null) {
                        firstMismatch = sample;
                        firstMismatchSky = sky;
                    }
                }
            }
            // Vegetation/structures intentionally opt out. If too few plain-water
            // shafts exist at one depth, that layer is not used as a renderer gate.
            if (samples >= 8 && firstMismatch != null) {
                deepClientLightFailures++;
                return ClientReadiness.fail("DEEP_SKYLIGHT_STALE", firstMismatch, 15, firstMismatchSky, 15, requiredMin);
            }
        }

        // v253.125.14 detection, v253.125.15 log-pressure containment: the 125.13 runtime proved the remaining ocean-base visual
        // artifact is often a MID/DEEP positive-SKY island, not the final water cell
        // above the floor. The server recorded >114k deep-overbright sampled columns
        // while this client gate reported zero absolute overbright failures. Probe the
        // same zero-tail depths used by the server, but require a surrounded 3x3 plain-
        // water neighborhood so structures/shorelines cannot manufacture resync debt.
        for (int depth : DEEP_SKY_ZERO_TAIL_DEPTHS) {
            int y = waterTop - depth;
            if (client.level == null || y <= client.level.getMinY()) continue;
            for (int lx = 2; lx < 16; lx += 4) {
                for (int lz = 2; lz < 16; lz += 4) {
                    int bx = baseX + lx, bz = baseZ + lz;
                    if (!clientHasSurroundedFloorWater(chunk, bx, bz, y, waterTop)) continue;
                    BlockPos sample = new BlockPos(bx, y, bz);
                    int sky = client.level.getBrightness(LightLayer.SKY, sample);
                    if (sky > 1) {
                        deepClientLightFailures++;
                        deepClientOverbrightFailures++;
                        long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
                        if (shouldLogDeepRootCause(packed)) {
                            OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-DEEP-SKY-ROOT-CAUSE build={} chunk={},{} classification=DEEP_SKYLIGHT_OVERBRIGHT sample={} actual={} expectedMax=1 depth={} action=hold-renderer-request-authoritative-light-and-post-resync-geometry-barrier",
                                    net.oceancanvas.mod.OceanCanvas.VERSION, chunk.getPos().x(), chunk.getPos().z(),
                                    sample, sky, depth);
                        }
                        return ClientReadiness.fail("DEEP_SKYLIGHT_OVERBRIGHT", sample, sky, -1, 1, -1);
                    }
                }
            }
        }

        // v253.125.11: restore ABSOLUTE deep-zero detection only for the
        // floor-adjacent renderer band, without restoring the disproven direct
        // client SKY-storage mutation. v253.125.10 removed the two surface defects
        // while the residual screenshot still showed bottom-ocean light islands.
        // Fixed depths can miss the final few water cells above a variable floor,
        // and seam-only proof accepts a uniformly overbright chunk. Detect the
        // actual first dry floor under 16 deterministic sample shafts, require a
        // surrounded 3x3 water neighborhood at floor and mid-depth, then request
        // authoritative server repair if floor-adjacent SKY is >1.
        floorBandClientScans++;
        for (int lx = 2; lx < 16; lx += 4) {
            for (int lz = 2; lz < 16; lz += 4) {
                int bx = baseX + lx, bz = baseZ + lz;
                int floorY = findClientOpenWaterFloorY(client, chunk, bx, bz, waterTop);
                if (floorY == Integer.MIN_VALUE) continue;
                int sampleY = floorY + 1;
                int depth = waterTop - sampleY;
                if (depth < 16) continue;
                if (!clientHasSurroundedFloorWater(chunk, bx, bz, sampleY, waterTop)) continue;
                BlockPos floorWater = new BlockPos(bx, sampleY, bz);
                int sky = client.level.getBrightness(LightLayer.SKY, floorWater);
                if (sky > 1) {
                    deepClientLightFailures++;
                    deepClientOverbrightFailures++;
                    floorBandClientFailures++;
                    long packed = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
                    if (shouldLogDeepRootCause(packed)) {
                        OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-DEEP-SKY-ROOT-CAUSE build={} chunk={},{} classification=FLOOR_SKYLIGHT_OVERBRIGHT sample={} actual={} expectedMax=1 depth={} action=hold-renderer-request-authoritative-light-and-post-resync-geometry-barrier",
                                net.oceancanvas.mod.OceanCanvas.VERSION, chunk.getPos().x(), chunk.getPos().z(),
                                floorWater, sky, depth);
                    }
                    return ClientReadiness.fail("FLOOR_SKYLIGHT_OVERBRIGHT", floorWater, sky, -1, 1, -1);
                }
            }
        }

        // v253.73.15: the v253.73.14 screenshot/log pair disproved the absolute
        // "all deep SKY must be zero" oracle as a safe renderer invariant. The server
        // had already written 120 whole zero-SKY sections while the visible defect was
        // a single hard-edged black square. Absolute zeroing can manufacture the exact
        // chunk-aligned discontinuity we are trying to remove.
        //
        // Replace the absolute-value rule with the local propagation invariant that
        // matters visually: across adjacent plain-water blocks at a loaded chunk seam,
        // SKY may differ by at most one level. This catches both a black zero-island
        // and a pale overbright island without forcing either side to an arbitrary
        // absolute value. Unloaded/structured/vegetated seams simply opt out.
        ClientReadiness seamFailure = inspectClientDeepChunkSeams(client, chunk);
        if (seamFailure != null) {
            deepClientLightFailures++;
            return seamFailure;
        }
        return ClientReadiness.ok();
    }

    private static ClientReadiness inspectClientDeepChunkSeams(Minecraft client, LevelChunk center) {
        if (client.level == null || center == null) return null;
        final int waterTop = net.oceancanvas.mod.config.OceanCanvasConfig.WATER_SURFACE_Y;
        final int baseX = center.getPos().getMinBlockX();
        final int baseZ = center.getPos().getMinBlockZ();
        final int[][] sides = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};

        for (int depth : DEEP_SKY_ZERO_TAIL_DEPTHS) {
            int y = waterTop - depth;
            for (int[] side : sides) {
                int dx = side[0], dz = side[1];
                LevelChunk neighbor = getActuallyLoadedClientChunk(client,
                        center.getPos().x() + dx, center.getPos().z() + dz);
                if (neighbor == null) continue;

                int samples = 0;
                int failures = 0;
                BlockPos first = null;
                int firstActual = -1;
                int firstNeighbor = -1;

                for (int offset = 1; offset <= 13; offset += 2) {
                    int ax, az, bx, bz;
                    if (dx > 0) {
                        ax = baseX + 15; az = baseZ + offset; bx = ax + 1; bz = az;
                    } else if (dx < 0) {
                        ax = baseX; az = baseZ + offset; bx = ax - 1; bz = az;
                    } else if (dz > 0) {
                        ax = baseX + offset; az = baseZ + 15; bx = ax; bz = az + 1;
                    } else {
                        ax = baseX + offset; az = baseZ; bx = ax; bz = az - 1;
                    }

                    if (!clientHasDirectWaterShaft(center, ax, az, y, waterTop)) continue;
                    if (!clientHasDirectWaterShaft(neighbor, bx, bz, y, waterTop)) continue;

                    BlockPos aPos = new BlockPos(ax, y, az);
                    BlockPos bPos = new BlockPos(bx, y, bz);
                    int aSky = client.level.getBrightness(LightLayer.SKY, aPos);
                    int bSky = client.level.getBrightness(LightLayer.SKY, bPos);
                    samples++;
                    if (Math.abs(aSky - bSky) > 1) {
                        failures++;
                        if (first == null) {
                            first = aPos;
                            firstActual = aSky;
                            firstNeighbor = bSky;
                        }
                    }
                }

                // Four failures across the same seven-point border sample is a hard
                // chunk seam, not normal local variation. Require a majority so one
                // unusual column near preserved geometry cannot trigger a repair loop.
                if (samples >= 4 && failures >= Math.max(4, (samples + 1) / 2)) {
                    int expectedMin = Math.max(0, firstNeighbor - 1);
                    int expectedMax = Math.min(15, firstNeighbor + 1);
                    OceanCanvas.LOGGER.warn("(Ocean Canvas) CLIENT-DEEP-SKY-ROOT-CAUSE build={} chunk={},{} classification=DEEP_SKYLIGHT_CHUNK_SEAM depth={} side={},{} samples={} failures={} first={} actual={} neighbor={} allowed={}..{} action=hold-renderer-and-request-authoritative-light",
                            net.oceancanvas.mod.OceanCanvas.VERSION, center.getPos().x(), center.getPos().z(),
                            depth, dx, dz, samples, failures, first, firstActual, firstNeighbor, expectedMin, expectedMax);
                    return ClientReadiness.fail("DEEP_SKYLIGHT_CHUNK_SEAM", first,
                            firstActual, firstNeighbor, expectedMin, expectedMax);
                }
            }
        }
        return null;
    }

    private static int findClientOpenWaterFloorY(Minecraft client, LevelChunk chunk, int x, int z, int waterTop) {
        if (client.level == null) return Integer.MIN_VALUE;
        int minY = Math.max(client.level.getMinY() + 1, waterTop - 96);
        for (int y = waterTop - 1; y >= minY; y--) {
            var state = chunk.getBlockState(new BlockPos(x, y, z));
            if (state.is(Blocks.WATER)) continue;
            // Water-bearing vegetation is not a floor anchor; neither is air.
            if (state.isAir() || !state.getFluidState().isEmpty()) return Integer.MIN_VALUE;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean clientHasSurroundedFloorWater(LevelChunk chunk, int x, int z, int sampleY, int waterTop) {
        if (!clientHasDirectWaterShaft(chunk, x, z, sampleY, waterTop)) return false;
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        int lx = x - baseX, lz = z - baseZ;
        if (lx <= 0 || lx >= 15 || lz <= 0 || lz >= 15) return false;
        int midY = sampleY + Math.max(1, (waterTop - sampleY) / 2);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!chunk.getBlockState(new BlockPos(x + dx, sampleY, z + dz)).is(Blocks.WATER)) return false;
                if (!chunk.getBlockState(new BlockPos(x + dx, midY, z + dz)).is(Blocks.WATER)) return false;
            }
        }
        return true;
    }

    private static int minimumClientPlainWaterSkyAtDepth(int depthBelowSurfaceWater) {
        return Math.max(0, 14 - Math.max(0, depthBelowSurfaceWater));
    }

    /**
     * v253.36: ClientLevel#hasChunk + ClientLevel#getChunk can resolve the cache's
     * synthetic empty fallback chunk for positions that are not actually resident.
     * The v253.34 runtime exposed this decisively: thousands of far Pregen entries
     * reported minecraft:void_air and consumed 170k surface scans while only the
     * player's near field mattered. Query ClientChunkCache directly with
     * loadOrGenerate=false so absent positions are truly null and cannot poison the
     * renderer convergence queue or its diagnostics.
     */
    private static LevelChunk getActuallyLoadedClientChunk(Minecraft client, int x, int z) {
        if (client.level == null) return null;
        return client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }

    private static boolean clientHasDirectWaterShaft(LevelChunk chunk, int x, int z, int sampleY, int waterTop) {
        if (!chunk.getBlockState(new BlockPos(x, waterTop + 1, z)).isAir()) return false;
        for (int y = sampleY; y <= waterTop; y++) {
            if (!chunk.getBlockState(new BlockPos(x, y, z)).is(Blocks.WATER)) return false;
        }
        return true;
    }

    private static boolean clientHasSurroundedZeroTailWater(LevelChunk chunk, int x, int z, int sampleY, int waterTop) {
        if (!clientHasDirectWaterShaft(chunk, x, z, sampleY, waterTop)) return false;
        int midY = sampleY + Math.max(1, (waterTop - sampleY) / 2);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!chunk.getBlockState(new BlockPos(x + dx, sampleY, z + dz)).is(Blocks.WATER)) return false;
            if (!chunk.getBlockState(new BlockPos(x + dx, midY, z + dz)).is(Blocks.WATER)) return false;
        }
        return true;
    }


    /**
     * v253.73.15: direct client SKY DataLayer replacement is intentionally disabled.
     * The v253.73.14 black-square screenshot is consistent with a chunk-local all-zero
     * section. Client recovery is now authoritative-resync + seam proof + renderer
     * invalidation only; it never writes light storage itself.
     */
    private static int repairClientDeterministicDeepZeroSkyStorage(Minecraft client, LevelChunk center) {
        return 0;
    }

    private static boolean clientHasDeterministicDeepPlainWaterNeighborhood(Minecraft client, int centerChunkX, int centerChunkZ, int minY, int maxY) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            LevelChunk neighbor = getActuallyLoadedClientChunk(client, centerChunkX + dx, centerChunkZ + dz);
            if (neighbor == null || !clientIsDeterministicDeepPlainWaterSection(neighbor, minY, maxY)) return false;
        }
        return true;
    }

    private static boolean clientIsDeterministicDeepPlainWaterSection(LevelChunk chunk, int minY, int maxY) {
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int x = baseX + lx, z = baseZ + lz;
            for (int y = minY; y <= maxY; y++) {
                var state = chunk.getBlockState(new BlockPos(x, y, z));
                if (state.is(Blocks.WATER)) {
                    if (!state.getFluidState().isSource()) return false;
                    continue;
                }
                if (state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)
                        || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)) continue;
                return false;
            }
        }
        return true;
    }


    private static boolean isNearClientPlayer(Minecraft client, int chunkX, int chunkZ) {
        if (client.player == null) return false;
        ChunkPos center = client.player.chunkPosition();
        return Math.abs(chunkX - center.x()) <= AWAIT_CLIENT_LOAD_RADIUS_CHUNKS
                && Math.abs(chunkZ - center.z()) <= AWAIT_CLIENT_LOAD_RADIUS_CHUNKS;
    }

    private static void rememberAwaitingClientLoad(long packed) {
        remember(packed);
        long deadline = clientTickCounter + AWAIT_CLIENT_LOAD_TICKS;
        long prior = AWAIT_CLIENT_LOAD_DEADLINE.put(packed, deadline);
        if (prior == NO_LONG_STATE) clientAwaitLoadQueued++;
    }

    private static void remember(long packed) {
        if (PENDING.size() >= MAX_PENDING) {
            LongIterator it = PENDING.iterator();
            if (it.hasNext()) {
                long oldest = it.nextLong();
                it.remove();
                clearChunkRepairState(oldest);
            }
        }
        PENDING.add(packed);
    }

    private static void defer(long packed) {
        if (PENDING.remove(packed)) PENDING.add(packed);
    }

    private static void forget(long packed) {
        PENDING.remove(packed);
        clearChunkRepairState(packed);
    }

    private static void clearChunkRepairState(long packed) {
        READY_STREAK.remove(packed);
        FAILURE_STREAK.remove(packed);
        RESYNC_ATTEMPTS.remove(packed);
        NEXT_RESYNC_TICK.remove(packed);
        LAST_RESYNC_REQUEST_TICK.remove(packed);
        PUBLICATION_SETTLE_UNTIL_TICK.remove(packed);
        VOXY_REINGEST_ATTEMPTS.remove(packed);
        VOXY_REINGEST_NOT_BEFORE_TICK.remove(packed);
        LOCAL_DEEP_ZERO_REPAIR_ATTEMPTS.remove(packed);
        STRONG_GEOMETRY_REQUIRED.remove(packed);
        STRONG_GEOMETRY_TARGET_EPOCH.remove(packed);
        STRONG_GEOMETRY_NOT_BEFORE_TICK.remove(packed);
        RESYNC_REQUEST_QUEUE.remove(packed);
        AWAIT_CLIENT_LOAD_DEADLINE.remove(packed);
    }

    private static void clearClientRepairState() {
        flushClientResyncLogSummaries(true);
        PENDING.clear();
        RESYNC_REQUEST_QUEUE.clear();
        READY_STREAK.clear();
        FAILURE_STREAK.clear();
        RESYNC_ATTEMPTS.clear();
        NEXT_RESYNC_TICK.clear();
        LAST_RESYNC_REQUEST_TICK.clear();
        PUBLICATION_SETTLE_UNTIL_TICK.clear();
        VOXY_REINGEST_ATTEMPTS.clear();
        VOXY_REINGEST_NOT_BEFORE_TICK.clear();
        LOCAL_DEEP_ZERO_REPAIR_ATTEMPTS.clear();
        STRONG_GEOMETRY_REQUIRED.clear();
        STRONG_GEOMETRY_TARGET_EPOCH.clear();
        STRONG_GEOMETRY_NOT_BEFORE_TICK.clear();
        AWAIT_CLIENT_LOAD_DEADLINE.clear();
        clientTickCounter = 0L;
        strongGeometryEpoch = 0L;
        lastStrongGeometryInvalidateTick = Long.MIN_VALUE / 4L;
        nearFieldRebuilds = 0L;
        fullClientLightScans = 0L;
        fullClientLightFailures = 0L;
        deepClientLightScans = 0L;
        deepClientLightFailures = 0L;
        deepClientOverbrightFailures = 0L;
        floorBandClientScans = 0L;
        floorBandClientFailures = 0L;
        localDeepZeroRepairs = 0L;
        localDeepZeroSections = 0L;
        localDeepZeroInconclusive = 0L;
        clientLightResyncRequests = 0L;
        clientLightResyncChunks = 0L;
        clientLightRecoveries = 0L;
        clientPostResyncConvergenceHolds = 0L;
        clientWideSectionRebuilds = 0L;
        clientTintCacheResets = 0L;
        clientAwaitLoadQueued = 0L;
        clientAwaitLoadActivated = 0L;
        clientAwaitLoadExpired = 0L;
        clientStrongGeometryInvalidations = 0L;
        clientStrongGeometryWaits = 0L;
        resyncRequestLogWindowStartTick = 0L;
        resyncRequestDetailedLogs = 0;
        resyncRequestSuppressedLogs = 0L;
        resyncConvergedLogWindowStartTick = 0L;
        resyncConvergedDetailedLogs = 0;
        resyncConvergedSuppressedLogs = 0L;
        tintCacheResetPending = false;
    }

    private static void permanentlyDisable(String message, Throwable t) {
        permanentlyDisabled = true;
        enabled = false;
        OceanCanvas.LOGGER.warn("(Ocean Canvas) {}. Ocean Canvas will continue without Voxy compatibility; near-field vanilla/Sodium repair remains active: {}",
                message, t.toString());
    }
}
