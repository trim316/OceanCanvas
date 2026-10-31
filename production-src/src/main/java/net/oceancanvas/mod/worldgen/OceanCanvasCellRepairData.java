package net.oceancanvas.mod.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import java.util.List;

/** Saved exact-cell receipts. Dirty state alone is NOT a durable-write barrier.
 * The consumer must wait for a successful save before using newly issued authority. */
public final class OceanCanvasCellRepairData extends SavedData {
    public static final Codec<OceanCanvasCellRepairData> CODEC = OceanCanvasCellRepairReceipts.CODEC
            .fieldOf("receipts").codec().xmap(OceanCanvasCellRepairData::new, d -> d.receipts.snapshot());
    public static final SavedDataType<OceanCanvasCellRepairData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("oceancanvas", "cell_repair_receipts"),
            OceanCanvasCellRepairData::new, CODEC, null);
    private final OceanCanvasCellRepairReceipts receipts;
    private final java.util.Map<Long,java.util.List<Long>> chunkCells = new java.util.HashMap<>();
    private final OceanCanvasCellRepairTransactions transactions = new OceanCanvasCellRepairTransactions(java.util.concurrent.ForkJoinPool.commonPool());
    public OceanCanvasCellRepairData() { this(List.of()); }
    private OceanCanvasCellRepairData(List<OceanCanvasCellRepairReceipts.Receipt> saved) {
        receipts = new OceanCanvasCellRepairReceipts(saved);
        for (var r:saved) index(r.position());
    }
    private void index(long position) {
        var p=net.minecraft.core.BlockPos.of(position);
        long chunk=net.minecraft.world.level.ChunkPos.pack(p.getX()>>4,p.getZ()>>4);
        chunkCells.computeIfAbsent(chunk,k->new java.util.ArrayList<>()).add(position);
    }
    public java.util.List<Long> cellsInChunk(long chunk) { return chunkCells.getOrDefault(chunk,java.util.List.of()); }
    public static OceanCanvasCellRepairData get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }
    public OceanCanvasCellRepairReceipts.Receipt at(long position) { return receipts.at(position); }
    public OceanCanvasCellRepairTransactions.State authority(java.nio.file.Path root, OceanCanvasCellRepairReceipts.Receipt receipt) { return transactions.authority(root,receipt); }
    public OceanCanvasCellRepairTransactions.State consumeBarrier(java.nio.file.Path root, OceanCanvasCellRepairReceipts.Receipt receipt) { return transactions.consume(root,receipt); }
    public boolean issue(OceanCanvasCellRepairReceipts.Receipt receipt, boolean explicitAuthority,
            OceanCanvasCellRepairReceipts.Scope scope) {
        if (!receipts.issue(receipt, explicitAuthority, scope)) return false;
        index(receipt.position());
        setDirty(); return true;
    }
    public boolean permits(String world, String operation, long pos, OceanCanvasCellRepairReceipts.Scope scope) {
        return receipts.permits(world, operation, pos, scope);
    }
    public boolean finish(String world, String operation, long pos, OceanCanvasCellRepairReceipts.Scope before,
            boolean sourceWater, boolean dirtyAndReproof) {
        if (!receipts.finish(world, operation, pos, before, sourceWater, dirtyAndReproof)) return false;
        setDirty(); return true;
    }
    public boolean recoverCompletedWrite(String world, String operation, long pos,
            OceanCanvasCellRepairReceipts.Scope current, boolean sourceWater, boolean dirtyAndReproof) {
        if (!receipts.recoverCompletedWrite(world, operation, pos, current, sourceWater, dirtyAndReproof)) return false;
        setDirty(); return true;
    }
}
