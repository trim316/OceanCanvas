package net.oceancanvas.mod.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.geometry.OceanFloorProfile;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkRecord;
import net.oceancanvas.core.pipeline.SingleChunkPorts;
import net.oceancanvas.core.pipeline.StageActionResult;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

/**
 * Minecraft 26.2 adapter for exactly one explicitly-confirmed sacrificial chunk.
 * No client APIs, renderer hooks, recovery queues or multi-chunk scheduling live here.
 */
final class SingleChunkWorldPorts implements SingleChunkPorts {
    private static final int MAX_PHYSICAL_RECONCILIATION_PASSES = 3;
    private static final int MAX_RESIDENCY_REACQUIRE_ATTEMPTS = 8;
    private static final long STALE_FULL_FUTURE_GRACE_TICKS = 40L;
    private static final long MAX_RESIDENCY_RETRY_DELAY_TICKS = 20L;
    private final ServerLevel world;
    private final CoreConfig config;
    private final ChunkKey key;
    private final ChunkPos pos;
    private final RuntimeReceiptLog receipts;

    private boolean ticketInstalled;
    private CompletableFuture<ChunkResult<ChunkAccess>> loadFuture;
    private LevelChunk chunk;
    private int residencyReacquireAttempts;
    private long staleFullFutureGraceUntilTick = Long.MIN_VALUE;
    private long residencyRetryNotBeforeTick = Long.MIN_VALUE;

    private int authorCursor;
    private long physicalSettleReadyTick = Long.MIN_VALUE;
    private int physicalVerifyCursor;
    private int physicalReconciliationPasses;
    private int physicalReconciliationRepairs;
    private String physicalReconciliationFirstMismatch = "";
    private boolean persistAttempted;

    private boolean lightSourcesPropagated;
    private int lightRequestCursor;
    private long lightSettleReadyTick = Long.MIN_VALUE;

    private int finalPhysicalCursor;
    private int finalLightCursor;
    private boolean finalVerificationSaved;

    SingleChunkWorldPorts(ServerLevel world, CoreConfig config, ChunkKey key, RuntimeReceiptLog receipts) {
        this.world = world;
        this.config = config;
        this.key = key;
        this.pos = new ChunkPos(key.x(), key.z());
        this.receipts = receipts;
    }

    @Override
    public StageActionResult load(ChunkRecord record) {
        return ensureResident();
    }

    @Override
    public StageActionResult authorPhysical(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;

        int minY = config.oceanFloorY() - config.oceanFloorVariation() - 1;
        int maxY = world.getMaxY() - 1;
        if (minY <= world.getMinY() || maxY < minY) {
            return StageActionResult.failure("configured ocean floor outside world build range: floorY=" + config.oceanFloorY()
                    + " world=" + world.getMinY() + ".." + world.getMaxY());
        }
        int height = maxY - minY + 1;
        int total = 256 * height;
        int examined = 0, writes = 0;
        long deadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        while (authorCursor < total && examined < config.maxChecksPerTick()
                && writes < config.maxBlockWritesPerTick() && System.nanoTime() < deadline) {
            int index = authorCursor++;
            int column = index / height;
            int y = minY + (index % height);
            int x = pos.getMinBlockX() + (column & 15);
            int z = pos.getMinBlockZ() + (column >>> 4);
            cursor.set(x, y, z);
            BlockState current = chunk.getBlockState(cursor);
            BlockState target = canonicalTarget(x, z, y);
            examined++;
            if (!authoredCanonicalState(current, x, z, y)) {
                world.setBlock(cursor, target, Block.UPDATE_CLIENTS);
                writes++;
            }
        }

        if (authorCursor < total) {
            return StageActionResult.waiting("physical authoring cursor=" + authorCursor + "/" + total + " writesThisTick=" + writes);
        }

        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        chunk.markUnsaved();
        try {
            receipts.append(ReceiptKind.PHYSICAL_AUTHORING_COMPLETE, key,
                    "cells=" + total + ";floorY=" + config.oceanFloorY() + ";variation=" + config.oceanFloorVariation() + ";waterY=" + config.waterSurfaceY());
        } catch (IOException e) {
            return StageActionResult.failure("physical authoring receipt fsync failed: " + e.getMessage());
        }
        return StageActionResult.success("canonical single-chunk ocean profile authored; cells=" + total);
    }

    @Override
    public StageActionResult settlePhysical(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;
        if (physicalSettleReadyTick == Long.MIN_VALUE) {
            physicalSettleReadyTick = world.getGameTime() + config.physicalSettleTicks();
            return StageActionResult.waiting("physical settlement quiet window armed until tick " + physicalSettleReadyTick);
        }
        if (world.getGameTime() < physicalSettleReadyTick) {
            return StageActionResult.waiting("physical settlement quiet window remaining=" + (physicalSettleReadyTick - world.getGameTime()));
        }

        StageActionResult scan = reconcilePhysicalSettlement();
        if (scan.status() == StageActionResult.Status.SUCCEEDED) {
            try {
                receipts.append(ReceiptKind.PHYSICAL_SETTLEMENT_VERIFIED, key, scan.evidence());
            } catch (IOException e) {
                return StageActionResult.failure("physical settlement receipt fsync failed: " + e.getMessage());
            }
        }
        return scan;
    }

    @Override
    public StageActionResult persist(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;
        if (persistAttempted) return StageActionResult.success("durable save flush already completed in this process");
        try {
            chunk.markUnsaved();
            // v0.2 deliberately uses Minecraft's synchronous durable world flush as a
            // correctness gate. It is intentionally NOT the future multi-chunk path.
            world.getServer().saveAllChunks(false, true, true);
            persistAttempted = true;
            receipts.append(ReceiptKind.SAVE_FLUSH_COMPLETE, key, "MinecraftServer.saveAllChunks(false,true,true) returned normally");
            return StageActionResult.success("durable server save flush completed");
        } catch (Throwable t) {
            return StageActionResult.failure("durable save flush failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    @Override
    public StageActionResult settleLighting(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;

        if (!lightSourcesPropagated) {
            try {
                world.getChunkSource().getLightEngine().propagateLightSources(pos);
                lightSourcesPropagated = true;
            } catch (Throwable t) {
                return StageActionResult.failure("light-source propagation failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
            }
        }

        int minY = config.oceanFloorY() - config.oceanFloorVariation();
        int maxY = config.waterSurfaceY() + 1;
        int height = maxY - minY + 1;
        int total = 256 * height;
        int submitted = 0;
        long deadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
        while (lightRequestCursor < total && submitted < config.maxChecksPerTick() && System.nanoTime() < deadline) {
            int index = lightRequestCursor++;
            int column = index / height;
            int y = minY + (index % height);
            int x = pos.getMinBlockX() + (column & 15);
            int z = pos.getMinBlockZ() + (column >>> 4);
            world.getChunkSource().getLightEngine().checkBlock(new BlockPos(x, y, z));
            submitted++;
        }
        if (lightRequestCursor < total) {
            return StageActionResult.waiting("authoritative light request cursor=" + lightRequestCursor + "/" + total);
        }

        if (lightSettleReadyTick == Long.MIN_VALUE) {
            lightSettleReadyTick = world.getGameTime() + config.lightSettleTicks();
            try {
                receipts.append(ReceiptKind.LIGHT_REQUEST_COMPLETE, key,
                        "checkBlockCalls=" + total + ";settleUntilTick=" + lightSettleReadyTick);
            } catch (IOException e) {
                return StageActionResult.failure("light request receipt fsync failed: " + e.getMessage());
            }
            return StageActionResult.waiting("authoritative light request complete; settling until tick " + lightSettleReadyTick);
        }
        if (world.getGameTime() < lightSettleReadyTick) {
            return StageActionResult.waiting("light settlement quiet window remaining=" + (lightSettleReadyTick - world.getGameTime()));
        }
        return StageActionResult.success("authoritative light request settled for " + config.lightSettleTicks() + " ticks");
    }

    @Override
    public StageActionResult verify(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;

        StageActionResult physical = scanPhysical(true);
        if (physical.status() != StageActionResult.Status.SUCCEEDED) return physical;

        int minY = config.oceanFloorY() - config.oceanFloorVariation();
        int maxY = config.waterSurfaceY() + 1;
        int height = maxY - minY + 1;
        int total = 256 * height;
        int checked = 0;
        long deadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (finalLightCursor < total && checked < config.maxChecksPerTick() && System.nanoTime() < deadline) {
            int index = finalLightCursor++;
            int column = index / height;
            int y = minY + (index % height);
            int x = pos.getMinBlockX() + (column & 15);
            int z = pos.getMinBlockZ() + (column >>> 4);
            cursor.set(x, y, z);
            int localFloorY = floorYFor(x, z);
            if (y < localFloorY) { checked++; continue; }
            int actual = world.getBrightness(LightLayer.SKY, cursor);
            int verticalMinimum = y == config.waterSurfaceY() + 1 ? 15
                    : Math.max(0, 14 - Math.max(0, config.waterSurfaceY() - y));
            boolean naturalSurfaceCap = hasNaturalSurfaceEvolution(x, z);
            // The strict water column expects at most 14 below open sky. If vanilla
            // weather has converted the surface to ice (optionally with snow above),
            // the physical state itself explains a different transparent-light path,
            // so permit 15 rather than misclassifying that column as overbright.
            int allowedMaximum = (y == config.waterSurfaceY() + 1 || naturalSurfaceCap) ? 15 : 14;
            checked++;
            if (actual < verticalMinimum || actual > allowedMaximum) {
                return StageActionResult.failure("server skylight invariant mismatch at " + x + "," + y + "," + z
                        + " allowed=" + verticalMinimum + ".." + allowedMaximum + " actual=" + actual
                        + " naturalSurfaceCap=" + naturalSurfaceCap);
            }
        }
        if (finalLightCursor < total) {
            return StageActionResult.waiting("strict server light verification cursor=" + finalLightCursor + "/" + total);
        }
        try {
            if (!finalVerificationSaved) {
                chunk.markUnsaved();
                world.getServer().saveAllChunks(false, true, true);
                finalVerificationSaved = true;
            }
            receipts.append(ReceiptKind.SERVER_VERIFICATION_COMPLETE, key,
                    "physical=settled-canonical;skyInvariantSamples=" + total + ";finalSaveFlush=true;clientEvidenceRequired=false");
        } catch (Throwable e) {
            return StageActionResult.failure("verification/final save receipt failed: " + e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
        return StageActionResult.success("strict server physical+skylight invariant verification complete and durably flushed; samples=" + total);
    }

    @Override
    public StageActionResult release(ChunkRecord record) {
        try {
            boolean had = ticketInstalled;
            if (ticketInstalled) {
                world.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 0);
                ticketInstalled = false;
            }
            chunk = null;
            loadFuture = null;
            residencyReacquireAttempts = 0;
            staleFullFutureGraceUntilTick = Long.MIN_VALUE;
            residencyRetryNotBeforeTick = Long.MIN_VALUE;
            receipts.append(ReceiptKind.TICKET_RELEASED, key, had ? "forced radius=0" : "no live ticket after restart");
            return StageActionResult.success(had ? "owned forced ticket released" : "ticket already absent after restart");
        } catch (Throwable t) {
            return StageActionResult.failure("ticket release failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    @Override
    public void close() {
        if (!ticketInstalled) return;
        try {
            world.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 0);
            ticketInstalled = false;
            receipts.append(ReceiptKind.TICKET_RELEASED, key, "session-close-before-terminal; restart will reacquire if needed");
        } catch (Throwable ignored) {
            // Server teardown/fatal paths are best-effort here. The FORCED ticket is
            // runtime-only and cannot survive process shutdown.
        }
    }

    private StageActionResult ensureResident() {
        if (chunk != null) {
            LevelChunk now = world.getChunkSource().getChunkNow(key.x(), key.z());
            if (now != null) { chunk = now; return StageActionResult.success("FULL chunk resident"); }
            chunk = null;
            loadFuture = null;
        }
        try {
            if (!ticketInstalled) {
                world.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 0);
                ticketInstalled = true;
                receipts.append(ReceiptKind.TICKET_INSTALLED, key, "TicketType.FORCED radius=0");
            }
            LevelChunk now = world.getChunkSource().getChunkNow(key.x(), key.z());
            if (now != null) {
                chunk = now;
                receipts.append(ReceiptKind.CHUNK_RESIDENT, key, "resident-via-getChunkNow");
                return StageActionResult.success("FULL chunk resident");
            }
            if (loadFuture == null) {
                if (world.getGameTime() < residencyRetryNotBeforeTick) {
                    return StageActionResult.waiting("bounded FULL chunk residency reacquire backoff attempt="
                            + residencyReacquireAttempts + "/" + MAX_RESIDENCY_REACQUIRE_ATTEMPTS);
                }
                staleFullFutureGraceUntilTick = Long.MIN_VALUE;
                loadFuture = world.getChunkSource().getChunkFuture(key.x(), key.z(), ChunkStatus.FULL, false);
                return StageActionResult.waiting("FULL chunk future requested attempt="
                        + (residencyReacquireAttempts + 1) + "/" + (MAX_RESIDENCY_REACQUIRE_ATTEMPTS + 1));
            }
            if (!loadFuture.isDone()) return StageActionResult.waiting("waiting for FULL chunk future");
            ChunkResult<ChunkAccess> result = loadFuture.join();
            ChunkAccess access = result.orElse(null);
            if (!(access instanceof LevelChunk live)) {
                // A FORCED ticket is asynchronous. Across restart/ticket propagation, Minecraft can
                // complete this particular future with `Unloaded chunk` even though our owned
                // ticket is still valid and the chunk becomes resident a few ticks later. Treat
                // that completion as a stale observation, not immediate terminal corruption.
                LevelChunk recovered = world.getChunkSource().getChunkNow(key.x(), key.z());
                if (recovered != null) {
                    chunk = recovered;
                    loadFuture = null;
                    residencyReacquireAttempts = 0;
                    staleFullFutureGraceUntilTick = Long.MIN_VALUE;
                    residencyRetryNotBeforeTick = Long.MIN_VALUE;
                    receipts.append(ReceiptKind.CHUNK_RESIDENT, key, "resident-after-stale-FULL-future");
                    return StageActionResult.success("FULL chunk resident after stale future");
                }

                String error = String.valueOf(result.getError());
                long nowTick = world.getGameTime();

                // A completed FULL future can briefly report Unloaded while the owned
                // FORCED ticket is still propagating through Minecraft's ticket graph.
                // Keep polling getChunkNow for a bounded grace window before consuming
                // a reacquire attempt. This preserves one-chunk ownership and avoids
                // turning short G16 restart pressure into a false terminal failure.
                if (staleFullFutureGraceUntilTick == Long.MIN_VALUE) {
                    staleFullFutureGraceUntilTick = nowTick + STALE_FULL_FUTURE_GRACE_TICKS;
                    return StageActionResult.waiting("FULL chunk future transiently unavailable; graceUntilTick="
                            + staleFullFutureGraceUntilTick + " error=" + error);
                }
                if (nowTick < staleFullFutureGraceUntilTick) {
                    return StageActionResult.waiting("waiting for owned FORCED ticket after stale FULL future; graceRemainingTicks="
                            + (staleFullFutureGraceUntilTick - nowTick) + " error=" + error);
                }

                if (residencyReacquireAttempts >= MAX_RESIDENCY_REACQUIRE_ATTEMPTS) {
                    return StageActionResult.failure("FULL chunk residency could not be reacquired after "
                            + MAX_RESIDENCY_REACQUIRE_ATTEMPTS + " bounded stale/unloaded future windows: " + error);
                }

                residencyReacquireAttempts++;
                long delay = Math.min(MAX_RESIDENCY_RETRY_DELAY_TICKS, 1L << Math.min(4, residencyReacquireAttempts - 1));
                residencyRetryNotBeforeTick = nowTick + delay;
                staleFullFutureGraceUntilTick = Long.MIN_VALUE;
                loadFuture = null;
                // Reasserting the same owned FORCED ticket is idempotent and avoids widening
                // ticket radius/ownership while allowing Minecraft's ticket graph to settle.
                world.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 0);
                return StageActionResult.waiting("FULL chunk future remained unavailable through grace window; reacquire attempt="
                        + residencyReacquireAttempts + "/" + MAX_RESIDENCY_REACQUIRE_ATTEMPTS
                        + " retryInTicks=" + delay + " error=" + error);
            }
            chunk = live;
            loadFuture = null;
            residencyReacquireAttempts = 0;
            staleFullFutureGraceUntilTick = Long.MIN_VALUE;
            residencyRetryNotBeforeTick = Long.MIN_VALUE;
            receipts.append(ReceiptKind.CHUNK_RESIDENT, key, "resident-via-FULL-future");
            return StageActionResult.success("FULL chunk resident");
        } catch (Throwable t) {
            return StageActionResult.failure("chunk residency failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    /**
     * Post-restart settlement is allowed to repair a bounded number of transient
     * non-canonical cells inside the already-authorized sacrificial chunk. This
     * handles save/load normalization or one-off mod/world simulation interference
     * without turning a single recoverable cell into a terminal campaign failure.
     * A complete repair pass is followed by a fresh quiet window and a full rescan.
     * Repeated instability still fails closed after a small fixed number of passes.
     */
    private StageActionResult reconcilePhysicalSettlement() {
        int minY = config.oceanFloorY() - config.oceanFloorVariation() - 1;
        int maxY = world.getMaxY() - 1;
        int height = maxY - minY + 1;
        int total = 256 * height;
        int checked = 0;
        int writesThisTick = 0;
        long deadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        while (physicalVerifyCursor < total && checked < config.maxChecksPerTick()
                && writesThisTick < config.maxBlockWritesPerTick() && System.nanoTime() < deadline) {
            int index = physicalVerifyCursor++;
            int column = index / height;
            int y = minY + (index % height);
            int x = pos.getMinBlockX() + (column & 15);
            int z = pos.getMinBlockZ() + (column >>> 4);
            cursor.set(x, y, z);
            BlockState state = chunk.getBlockState(cursor);
            checked++;
            if (settledCanonicalState(state, x, z, y)) continue;

            String mismatch = x + "," + y + "," + z + " expected=" + settledCanonicalName(x, z, y) + " actual=" + state;
            if (physicalReconciliationPasses >= MAX_PHYSICAL_RECONCILIATION_PASSES) {
                return StageActionResult.failure("physical settlement remained unstable after "
                        + MAX_PHYSICAL_RECONCILIATION_PASSES + " reconciliation passes; mismatch=" + mismatch);
            }
            if (physicalReconciliationFirstMismatch.isBlank()) physicalReconciliationFirstMismatch = mismatch;
            world.setBlock(cursor, canonicalTarget(x, z, y), Block.UPDATE_CLIENTS);
            physicalReconciliationRepairs++;
            writesThisTick++;
        }

        if (physicalVerifyCursor < total) {
            return StageActionResult.waiting("physical settlement verification cursor=" + physicalVerifyCursor + "/" + total
                    + " repairsThisPass=" + physicalReconciliationRepairs);
        }

        if (physicalReconciliationRepairs > 0) {
            int repaired = physicalReconciliationRepairs;
            int pass = ++physicalReconciliationPasses;
            String first = physicalReconciliationFirstMismatch;
            try {
                chunk.markUnsaved();
                receipts.append(ReceiptKind.PHYSICAL_SETTLEMENT_RECONCILED, key,
                        "pass=" + pass + ";repairedCells=" + repaired + ";firstMismatch=" + first);
            } catch (IOException e) {
                return StageActionResult.failure("physical reconciliation receipt fsync failed: " + e.getMessage());
            }
            physicalVerifyCursor = 0;
            physicalReconciliationRepairs = 0;
            physicalReconciliationFirstMismatch = "";
            physicalSettleReadyTick = world.getGameTime() + config.physicalSettleTicks();
            return StageActionResult.waiting("physical settlement reconciliation pass " + pass + " repaired " + repaired
                    + " cells; quiet window rearmed until tick " + physicalSettleReadyTick);
        }

        return StageActionResult.success("settled physical profile verified; cells=" + total
                + ";reconciliationPasses=" + physicalReconciliationPasses
                + ";naturalSurfaceEvolution=ice/snow-tolerated");
    }

    private StageActionResult scanPhysical(boolean finalPass) {
        int minY = config.oceanFloorY() - config.oceanFloorVariation() - 1;
        int maxY = world.getMaxY() - 1;
        int height = maxY - minY + 1;
        int total = 256 * height;
        int cursorValue = finalPass ? finalPhysicalCursor : physicalVerifyCursor;
        int checked = 0;
        long deadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (cursorValue < total && checked < config.maxChecksPerTick() && System.nanoTime() < deadline) {
            int index = cursorValue++;
            int column = index / height;
            int y = minY + (index % height);
            int x = pos.getMinBlockX() + (column & 15);
            int z = pos.getMinBlockZ() + (column >>> 4);
            cursor.set(x, y, z);
            BlockState state = chunk.getBlockState(cursor);
            checked++;
            if (!settledCanonicalState(state, x, z, y)) {
                if (finalPass) finalPhysicalCursor = cursorValue; else physicalVerifyCursor = cursorValue;
                return StageActionResult.failure("physical profile mismatch at " + x + "," + y + "," + z
                        + " expected=" + settledCanonicalName(x, z, y) + " actual=" + state);
            }
        }
        if (finalPass) finalPhysicalCursor = cursorValue; else physicalVerifyCursor = cursorValue;
        if (cursorValue < total) {
            return StageActionResult.waiting((finalPass ? "final physical verification" : "physical settlement verification")
                    + " cursor=" + cursorValue + "/" + total);
        }
        return StageActionResult.success((finalPass ? "final" : "settled") + " physical profile verified; cells=" + total);
    }

    private int floorYFor(int x, int z) {
        return config.oceanFloorY() + OceanFloorProfile.floorOffset(x, z, config.oceanFloorVariation());
    }

    private BlockState canonicalTarget(int x, int z, int y) {
        int floorY = floorYFor(x, z);
        if (y < floorY) return Blocks.STONE.defaultBlockState();
        if (y <= config.waterSurfaceY()) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    /** Strict authored target used while Core is actively rewriting the chunk. */
    private boolean authoredCanonicalState(BlockState state, int x, int z, int y) {
        int floorY = floorYFor(x, z);
        if (y < floorY) return state.is(Blocks.STONE);
        if (y <= config.waterSurfaceY()) return state.is(Blocks.WATER) && state.getFluidState().isSource();
        return state.isAir();
    }

    /**
     * Stable physical semantics after normal server simulation resumes. Surface
     * source water may naturally freeze to vanilla ice, and vanilla snowfall may
     * place a snow layer directly above that surface. Those are not evidence that
     * the Ocean Canvas geometry or water column failed to persist. Everything
     * below the surface and everything above the one-block weather cap remains
     * strict.
     */
    private boolean settledCanonicalState(BlockState state, int x, int z, int y) {
        int floorY = floorYFor(x, z);
        if (y < floorY) return state.is(Blocks.STONE);
        if (y < config.waterSurfaceY()) return state.is(Blocks.WATER) && state.getFluidState().isSource();
        if (y == config.waterSurfaceY()) {
            return (state.is(Blocks.WATER) && state.getFluidState().isSource()) || state.is(Blocks.ICE);
        }
        if (y == config.waterSurfaceY() + 1) return state.isAir() || state.is(Blocks.SNOW);
        return state.isAir();
    }

    private boolean hasNaturalSurfaceEvolution(int x, int z) {
        BlockPos surface = new BlockPos(x, config.waterSurfaceY(), z);
        BlockPos cap = new BlockPos(x, config.waterSurfaceY() + 1, z);
        return chunk.getBlockState(surface).is(Blocks.ICE) || chunk.getBlockState(cap).is(Blocks.SNOW);
    }

    private String settledCanonicalName(int x, int z, int y) {
        int floorY = floorYFor(x, z);
        if (y < floorY) return "STONE";
        if (y < config.waterSurfaceY()) return "SOURCE_WATER";
        if (y == config.waterSurfaceY()) return "SOURCE_WATER_OR_NATURAL_ICE";
        if (y == config.waterSurfaceY() + 1) return "AIR_OR_NATURAL_SNOW";
        return "AIR";
    }

    private static String safeMessage(Throwable t) {
        String s = t.getMessage(); return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
