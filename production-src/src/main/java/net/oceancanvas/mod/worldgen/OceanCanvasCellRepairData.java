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
    public static final Codec<OceanCanvasCellRepairData> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(i->i.group(
            OceanCanvasCellRepairReceipts.CODEC.fieldOf("receipts").forGetter(d->d.receipts.snapshot()),
            OceanCanvasCellRepairReceipts.CODEC.optionalFieldOf("operatorRequests",List.of()).forGetter(d->List.copyOf(d.operatorRequests.values()))
    ).apply(i,OceanCanvasCellRepairData::new));
    public static final SavedDataType<OceanCanvasCellRepairData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("oceancanvas", "cell_repair_receipts"),
            OceanCanvasCellRepairData::new, CODEC, null);
    private final OceanCanvasCellRepairReceipts receipts;
    private final java.util.Map<Long,OceanCanvasCellRepairReceipts.Receipt> operatorRequests = new java.util.LinkedHashMap<>();
    private final java.util.Map<Long,java.util.List<Long>> chunkCells = new java.util.HashMap<>();
    private final OceanCanvasCellRepairTransactions transactions = new OceanCanvasCellRepairTransactions(java.util.concurrent.ForkJoinPool.commonPool());
    public OceanCanvasCellRepairData() { this(List.of(),List.of()); }
    private OceanCanvasCellRepairData(List<OceanCanvasCellRepairReceipts.Receipt> saved, List<OceanCanvasCellRepairReceipts.Receipt> requests) {
        receipts = new OceanCanvasCellRepairReceipts(saved);
        for (var r:saved) index(r.position());
        for(var r:requests) { if(r.consumed()||!r.origin().equals("OPERATOR_PREVIOUS_SOURCE_WATER")||operatorRequests.putIfAbsent(r.position(),r)!=null)throw new IllegalArgumentException("Invalid operator request");index(r.position()); }
    }
    private void index(long position) {
        var p=net.minecraft.core.BlockPos.of(position);
        long chunk=net.minecraft.world.level.ChunkPos.pack(p.getX()>>4,p.getZ()>>4);
        var cells=chunkCells.computeIfAbsent(chunk,k->new java.util.ArrayList<>());if(!cells.contains(position))cells.add(position);
    }
    public java.util.List<Long> cellsInChunk(long chunk) { return chunkCells.getOrDefault(chunk,java.util.List.of()); }
    public static OceanCanvasCellRepairData get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }
    /** Queued attestation is NOT repair authority. Activate only after loaded-current-state veto checks. */
    public boolean queueOperatorRequest(OceanCanvasCellRepairReceipts.Receipt r) {
        if(r.consumed()||!r.origin().equals("OPERATOR_PREVIOUS_SOURCE_WATER")||receipts.at(r.position())!=null||operatorRequests.putIfAbsent(r.position(),r)!=null)return false;
        index(r.position());setDirty();return true;
    }
    public OceanCanvasCellRepairReceipts.Receipt requestAt(long pos) { return operatorRequests.get(pos); }
    public void removeRequest(long pos) { if(operatorRequests.remove(pos)!=null)setDirty(); }
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
