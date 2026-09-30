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
import net.oceancanvas.core.geometry.ChunkColumnScanBounds;
import net.oceancanvas.core.pipeline.ChunkKey;
import net.oceancanvas.core.pipeline.ChunkRecord;
import net.oceancanvas.core.pipeline.SingleChunkPorts;
import net.oceancanvas.core.pipeline.StageActionResult;
import net.oceancanvas.core.receipt.ReceiptKind;
import net.oceancanvas.core.receipt.RuntimeReceiptLog;
import net.oceancanvas.core.runtime.ResidencyReacquirePolicy;
import net.oceancanvas.core.restore.BlockStatePreimageStore;
import net.oceancanvas.core.restore.BlockStatePreimageArchive;
import net.oceancanvas.core.restore.PreimageAdmissionPolicy;
import net.oceancanvas.core.restore.RestorePassPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final String operationId;
    private final Path preimagePath;

    private boolean ticketInstalled;
    private CompletableFuture<ChunkResult<ChunkAccess>> loadFuture;
    private LevelChunk chunk;
    private final ResidencyReacquirePolicy residencyPolicy =
            new ResidencyReacquirePolicy(MAX_RESIDENCY_REACQUIRE_ATTEMPTS,
                    STALE_FULL_FUTURE_GRACE_TICKS, MAX_RESIDENCY_RETRY_DELAY_TICKS);

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

    private int preimageCaptureCursor;
    private int[] preimageCaptureIds;
    private int restorePreflightCursor;
    private boolean restorePreflightComplete;
    private int restoreCursor;
    private int restorePass;
    private int restoreVerifyCursor;
    private BlockStatePreimageStore.Preimage restorePreimage;

    SingleChunkWorldPorts(ServerLevel world, CoreConfig config, ChunkKey key, RuntimeReceiptLog receipts,
                          String operationId, Path preimagePath) {
        this.world = world;
        this.config = config;
        this.key = key;
        this.pos = new ChunkPos(key.x(), key.z());
        this.receipts = receipts;
        this.operationId = operationId;
        this.preimagePath = preimagePath;
    }

    @Override
    public StageActionResult load(ChunkRecord record) {
        return ensureResident();
    }

    @Override
    public StageActionResult capturePreimage(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;

        final ChunkColumnScanBounds configured;
        try {
            configured = ChunkColumnScanBounds.forOceanFloor(
                    config.oceanFloorY(), config.oceanFloorVariation(), world.getMaxY());
        } catch (IllegalArgumentException e) {
            return StageActionResult.failure("unsafe preimage capture geometry; no world mutation started: " + e.getMessage());
        }
        int minY = configured.minY();
        int maxY = configured.maxY();
        if (minY <= world.getMinY()) {
            return StageActionResult.failure("preimage range outside world build range: " + minY + ".." + maxY);
        }

        try {
            if (Files.exists(preimagePath)) {
                BlockStatePreimageStore.Preimage existing = BlockStatePreimageStore.readVerified(preimagePath, operationId, key);
                if (existing.minY() != minY || existing.maxY() != maxY) {
                    return StageActionResult.failure("preimage geometry mismatch: existing="
                            + existing.minY() + ".." + existing.maxY() + " expected=" + minY + ".." + maxY);
                }
                String preimageSha = BlockStatePreimageStore.sha256Hex(preimagePath);
                receipts.append(ReceiptKind.PREIMAGE_CAPTURED, key,
                        "operation=" + operationId + ";states=" + existing.count() + ";minY=" + minY + ";maxY=" + maxY
                                + ";blockEntities=0;replayedDurablePreimage=true;preimageSha256=" + preimageSha);
                return StageActionResult.success("durable preimage already exists; states=" + existing.count());
            }

            int height = configured.height();
            int total = configured.cells();
            if (preimageCaptureIds == null) preimageCaptureIds = new int[total];

            int checked = 0;
            long deadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            while (preimageCaptureCursor < total && checked < config.maxChecksPerTick()
                    && System.nanoTime() < deadline) {
                int index = preimageCaptureCursor;
                int column = index / height;
                int y = minY + (index % height);
                int x = pos.getMinBlockX() + (column & 15);
                int z = pos.getMinBlockZ() + (column >>> 4);
                cursor.set(x, y, z);

                BlockState sourceState = chunk.getBlockState(cursor);
                if (PreimageAdmissionPolicy.refuses(sourceState.hasBlockEntity(),
                        chunk.getBlockEntity(cursor) != null)) {
                    return StageActionResult.failure("preimage capture refuses block-entity state or entity at "
                            + x + "," + y + "," + z + ";no NBT backup available");
                }
                int sourceId = Block.getId(sourceState);
                BlockState recovered = Block.stateById(sourceId);
                if (PreimageAdmissionPolicy.refusesStateId(sourceId,
                        Block.getId(recovered), recovered == sourceState)) {
                    return StageActionResult.failure("preimage capture refuses non-roundtrippable state at "
                            + x + "," + y + "," + z + ";stateId=" + sourceId
                            + ";no terrain mutation authorized");
                }
                preimageCaptureIds[index] = sourceId;
                preimageCaptureCursor++;
                checked++;
            }

            if (preimageCaptureCursor < total) {
                return StageActionResult.waiting("preimage capture cursor=" + preimageCaptureCursor + "/" + total);
            }

            BlockStatePreimageStore.Preimage preimage =
                    new BlockStatePreimageStore.Preimage(operationId, key, minY, maxY, preimageCaptureIds);
            BlockStatePreimageStore.writeExact(preimagePath, preimage);
            BlockStatePreimageStore.readVerified(preimagePath, operationId, key);
            String preimageSha = BlockStatePreimageStore.sha256Hex(preimagePath);
            receipts.append(ReceiptKind.PREIMAGE_CAPTURED, key,
                    "operation=" + operationId + ";states=" + total + ";minY=" + minY + ";maxY=" + maxY
                            + ";blockEntities=0;preimageSha256=" + preimageSha);
            return StageActionResult.success("durable exact block-state preimage captured; states=" + total);
        } catch (Throwable t) {
            return StageActionResult.failure("preimage capture failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    @Override
    public StageActionResult authorPhysical(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;

        final ChunkColumnScanBounds scan;
        try {
            scan = ChunkColumnScanBounds.forOceanFloor(
                    config.oceanFloorY(), config.oceanFloorVariation(), world.getMaxY());
        } catch (IllegalArgumentException e) {
            return StageActionResult.failure("unsafe physical authoring geometry; no world mutation started: " + e.getMessage());
        }
        int minY = scan.minY();
        int maxY = scan.maxY();
        if (minY <= world.getMinY()) {
            return StageActionResult.failure("configured ocean floor outside world build range: floorY=" + config.oceanFloorY()
                    + " world=" + world.getMinY() + ".." + world.getMaxY());
        }
        int height = scan.height();
        int total = scan.cells();
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
    public StageActionResult restore(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;

        try {
            if (restorePreimage == null) {
                restorePreimage = BlockStatePreimageStore.readVerified(preimagePath, operationId, key);
            }
            int minY = restorePreimage.minY();
            int maxY = restorePreimage.maxY();
            int height = maxY - minY + 1;
            int total = restorePreimage.count();

            // Refuse invalid registry references before writing ANY restored block.
            // The bounded scan is deliberately repeated after a process restart;
            // a partly restored chunk must never mask invalid remaining preimage IDs.
            if (!restorePreflightComplete) {
                int preflightChecked = 0;
                long preflightDeadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
                while (restorePreflightCursor < total
                        && preflightChecked < config.maxChecksPerTick()
                        && System.nanoTime() < preflightDeadline) {
                    int id = restorePreimage.stateIdAt(restorePreflightCursor);
                    BlockState state = Block.stateById(id);
                    if (id < 0 || Block.getId(state) != id) {
                        return StageActionResult.failure("preimage contains unresolvable block-state id " + id
                                + " at restore index " + restorePreflightCursor + "; no restore writes started");
                    }
                    restorePreflightCursor++;
                    preflightChecked++;
                }
                if (restorePreflightCursor < total) {
                    return StageActionResult.waiting("restore registry preflight cursor="
                            + restorePreflightCursor + "/" + total + "; no restore writes started");
                }
                restorePreflightComplete = true;
            }

            int checked = 0;
            int writes = 0;
            long deadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            while (restoreCursor < total && checked < config.maxChecksPerTick()
                    && writes < config.maxBlockWritesPerTick() && System.nanoTime() < deadline) {
                int index = restoreCursor++;
                int column = index / height;
                int y = minY + (index % height);
                int x = pos.getMinBlockX() + (column & 15);
                int z = pos.getMinBlockZ() + (column >>> 4);
                cursor.set(x, y, z);

                int stateId = restorePreimage.stateIdAt(index);
                BlockState target = Block.stateById(stateId);
                if (Block.getId(target) != stateId) {
                    return StageActionResult.failure("preimage references unknown block-state id " + stateId
                            + " at " + x + "," + y + "," + z);
                }

                checked++;
                if (Block.getId(chunk.getBlockState(cursor)) != stateId) {
                    world.setBlock(cursor, target, Block.UPDATE_CLIENTS);
                    writes++;
                }
            }

            if (restoreCursor < total) {
                return StageActionResult.waiting("restore pass=" + (restorePass + 1) + "/2 cursor="
                        + restoreCursor + "/" + total + " writesThisTick=" + writes);
            }

            // One column at a time can restore a dependent plant or attachment
            // before its neighboring support is back. A full bounded second
            // pass reapplies the *same verified preimage* once all columns have
            // had their support restored. Crash/reopen before RESTORED journal
            // credit simply restarts these idempotent passes.
            RestorePassPlan.AfterPass completed = RestorePassPlan.afterFullPass(
                    restorePass, restoreCursor, total);
            restorePass = completed.nextPass();
            restoreCursor = completed.nextCursor();
            if (!completed.readyToPersist()) {
                return StageActionResult.waiting("first full restore pass complete; reapplying original states"
                        + " with all support columns present; no RESTORED journal credit");
            }

            Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
            chunk.markUnsaved();
            world.getServer().saveAllChunks(false, true, true);
            receipts.append(ReceiptKind.RESTORE_COMPLETE, key,
                    "operation=" + operationId + ";states=" + total + ";minY=" + minY + ";maxY=" + maxY
                            + ";supportReapplyPasses=1;durableFlush=true;preimageSha256="
                            + BlockStatePreimageStore.sha256Hex(preimagePath));
            return StageActionResult.success("preimage block states restored and durably flushed; states=" + total);
        } catch (Throwable t) {
            return StageActionResult.failure("restore failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    @Override
    public StageActionResult verifyRestore(ChunkRecord record) {
        StageActionResult resident = ensureResident();
        if (resident.status() != StageActionResult.Status.SUCCEEDED) return resident;

        try {
            if (restorePreimage == null) {
                restorePreimage = BlockStatePreimageStore.readVerified(preimagePath, operationId, key);
            }
            int minY = restorePreimage.minY();
            int maxY = restorePreimage.maxY();
            int height = maxY - minY + 1;
            int total = restorePreimage.count();

            int checked = 0;
            long deadline = System.nanoTime() + config.stageWallBudgetMicros() * 1_000L;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            while (restoreVerifyCursor < total && checked < config.maxChecksPerTick()
                    && System.nanoTime() < deadline) {
                int index = restoreVerifyCursor++;
                int column = index / height;
                int y = minY + (index % height);
                int x = pos.getMinBlockX() + (column & 15);
                int z = pos.getMinBlockZ() + (column >>> 4);
                cursor.set(x, y, z);

                int expectedId = restorePreimage.stateIdAt(index);
                int actualId = Block.getId(chunk.getBlockState(cursor));
                checked++;
                if (actualId != expectedId) {
                    // Identify the missing vanilla state and its support
                    // immediately in the first failure receipt. Do not repair
                    // or mask a changed post-restart world on this evidence path.
                    BlockState expectedState = Block.stateById(expectedId);
                    BlockState actualState = chunk.getBlockState(cursor);
                    BlockState belowState = chunk.getBlockState(
                            cursor.set(x, y - 1, z));
                    cursor.set(x, y, z);
                    return StageActionResult.failure("restore verification mismatch at " + x + "," + y + "," + z
                            + " restoreIndex=" + (restoreVerifyCursor - 1)
                            + " expectedStateId=" + expectedId + " expectedState=" + expectedState
                            + " actualStateId=" + actualId + " actualState=" + actualState
                            + " belowState=" + belowState
                            + "; post-restart mismatch remains release-blocking");
                }
                if (PreimageAdmissionPolicy.refuses(chunk.getBlockState(cursor).hasBlockEntity(),
                        chunk.getBlockEntity(cursor) != null)) {
                    return StageActionResult.failure("restore verification found unexpected block-entity state or entity at "
                            + x + "," + y + "," + z);
                }
            }

            if (restoreVerifyCursor < total) {
                return StageActionResult.waiting("restore verification cursor=" + restoreVerifyCursor + "/" + total);
            }

            receipts.append(ReceiptKind.RESTORE_VERIFIED, key,
                    "operation=" + operationId + ";states=" + total + ";exactBlockStateIds=true;blockEntities=0"
                            + ";preimageSha256=" + BlockStatePreimageStore.sha256Hex(preimagePath));
            return StageActionResult.success("exact preimage block-state restoration verified; states=" + total);
        } catch (Throwable t) {
            return StageActionResult.failure("restore verification failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    @Override
    public StageActionResult release(ChunkRecord record) {
        try {
            // Release precedes the journal's durable COMPLETE append. Retain an
            // immutable exact backup so any crash in that window remains
            // independently auditable and a repeated release is idempotent.
            Path archivePath = preimagePath.resolveSibling(
                    preimagePath.getFileName().toString() + ".completed.archive");
            String archivedSha = BlockStatePreimageArchive.archiveExact(
                    preimagePath, archivePath, operationId, key);
            boolean had = ticketInstalled;
            if (ticketInstalled) {
                world.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 0);
                ticketInstalled = false;
            }
            chunk = null;
            loadFuture = null;
            residencyPolicy.reset();
            receipts.append(ReceiptKind.TICKET_RELEASED, key,
                    (had ? "forced radius=0" : "no live ticket after restart")
                            + ";restoreVerified=true;preimageArchiveSha256=" + archivedSha);
            return StageActionResult.success((had ? "owned forced ticket released" : "ticket already absent after restart")
                    + "; immutable restore preimage archived sha256=" + archivedSha);
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
                long nowTick = world.getGameTime();
                if (residencyPolicy.retryBackoffActive(nowTick)) {
                    return StageActionResult.waiting("bounded FULL chunk residency reacquire backoff attempt="
                            + residencyPolicy.attempts() + "/" + MAX_RESIDENCY_REACQUIRE_ATTEMPTS
                            + " remainingTicks=" + residencyPolicy.retryBackoffRemaining(nowTick));
                }
                residencyPolicy.futureRequested();
                loadFuture = world.getChunkSource().getChunkFuture(key.x(), key.z(), ChunkStatus.FULL, false);
                return StageActionResult.waiting("FULL chunk future requested attempt="
                        + (residencyPolicy.attempts() + 1) + "/" + (MAX_RESIDENCY_REACQUIRE_ATTEMPTS + 1));
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
                    residencyPolicy.reset();
                    receipts.append(ReceiptKind.CHUNK_RESIDENT, key, "resident-after-stale-FULL-future");
                    return StageActionResult.success("FULL chunk resident after stale future");
                }

                String error = String.valueOf(result.getError());
                long nowTick = world.getGameTime();
                ResidencyReacquirePolicy.Decision decision = residencyPolicy.onStaleFuture(nowTick);

                if (decision.action() == ResidencyReacquirePolicy.Action.GRACE) {
                    return StageActionResult.waiting("FULL chunk future transiently unavailable; graceRemainingTicks="
                            + decision.graceRemainingTicks() + " error=" + error);
                }

                if (decision.action() == ResidencyReacquirePolicy.Action.FAIL) {
                    return StageActionResult.failure("FULL chunk residency could not be reacquired after "
                            + MAX_RESIDENCY_REACQUIRE_ATTEMPTS + " bounded stale/unloaded future windows: " + error);
                }

                loadFuture = null;
                // Reasserting the same owned FORCED ticket is idempotent and avoids widening
                // ticket radius/ownership while allowing Minecraft's ticket graph to settle.
                world.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 0);
                return StageActionResult.waiting("FULL chunk future remained unavailable through grace window; reacquire attempt="
                        + decision.attempt() + "/" + MAX_RESIDENCY_REACQUIRE_ATTEMPTS
                        + " retryInTicks=" + decision.retryDelayTicks() + " error=" + error);
            }
            chunk = live;
            loadFuture = null;
            residencyPolicy.reset();
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
