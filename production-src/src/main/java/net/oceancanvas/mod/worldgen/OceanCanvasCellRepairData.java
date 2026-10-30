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
    public OceanCanvasCellRepairData() { this(List.of()); }
    private OceanCanvasCellRepairData(List<OceanCanvasCellRepairReceipts.Receipt> saved) {
        receipts = new OceanCanvasCellRepairReceipts(saved);
    }
    public static OceanCanvasCellRepairData get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }
    public boolean issue(OceanCanvasCellRepairReceipts.Receipt receipt, boolean explicitAuthority,
            OceanCanvasCellRepairReceipts.Scope scope) {
        if (!receipts.issue(receipt, explicitAuthority, scope)) return false;
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
