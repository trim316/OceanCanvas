package net.oceancanvas.mod.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;

/** Exact-cell recovery evidence. A record is not a chunk terrain lease or a certificate.
 * Runtime integration must provide explicit operator authority or a confirmed native event,
 * and recheck the complete current scope immediately before a write. */
public final class OceanCanvasCellRepairReceipts {
    public record Receipt(String world, String operation, long position, String evidence,
            String origin, boolean consumed) {
        public Receipt {
            UUID.fromString(world); UUID.fromString(operation);
            if (!evidence.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Evidence SHA256 required");
            if (!origin.equals("OPERATOR_PREVIOUS_SOURCE_WATER") && !origin.equals("CONFIRMED_NATIVE_LAVA_MIX"))
                throw new IllegalArgumentException("Unknown authority origin");
        }
    }
    public static final Codec<Receipt> RECEIPT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("world").forGetter(Receipt::world),
            Codec.STRING.fieldOf("operation").forGetter(Receipt::operation),
            Codec.LONG.fieldOf("position").forGetter(Receipt::position),
            Codec.STRING.fieldOf("evidence").forGetter(Receipt::evidence),
            Codec.STRING.fieldOf("origin").forGetter(Receipt::origin),
            Codec.BOOL.fieldOf("consumed").forGetter(Receipt::consumed)
    ).apply(i, Receipt::new));
    public static final Codec<List<Receipt>> CODEC = RECEIPT_CODEC.listOf();
    public record Scope(boolean activeOperation, boolean exactSelectedColumn, boolean canvas,
            boolean physicalProof, boolean authoredWaterBand, boolean protectedCell,
            boolean blockEntity, boolean currentStone) {
        boolean permits() {
            return activeOperation && exactSelectedColumn && canvas && physicalProof && authoredWaterBand
                    && !protectedCell && !blockEntity && currentStone;
        }
    }
    private final LinkedHashMap<Long,Receipt> cells = new LinkedHashMap<>();
    public OceanCanvasCellRepairReceipts() { }
    public OceanCanvasCellRepairReceipts(List<Receipt> saved) {
        for (Receipt r : saved) {
            if (cells.putIfAbsent(r.position(), r) != null)
                throw new IllegalArgumentException("Duplicate persisted cell authority");
        }
    }
    /** Caller must durably save a newly issued record before allowing its mutation. */
    public boolean issue(Receipt r, boolean explicitAuthority, Scope scope) {
        if (!explicitAuthority || r.consumed() || !scope.permits() || cells.containsKey(r.position())) return false;
        cells.put(r.position(), r); return true;
    }
    public boolean permits(String world, String operation, long position, Scope scope) {
        Receipt r = cells.get(position);
        return r != null && !r.consumed() && r.world().equals(world) && r.operation().equals(operation)
                && scope.permits();
    }
    /** Called only after exact source-water replacement, dirty/save and light-epoch rearm succeed.
     * Failed/no-op writes retain the pending receipt; a consumed record cannot be reissued. */
    public boolean finish(String world, String operation, long position, Scope before,
            boolean actualSourceWaterReplacement, boolean savedDirtyAndProofRearmed) {
        if (!permits(world, operation, position, before) || !actualSourceWaterReplacement
                || !savedDirtyAndProofRearmed) return false;
        Receipt r = cells.get(position);
        cells.put(position, new Receipt(r.world(), r.operation(), position, r.evidence(), r.origin(), true));
        return true;
    }
    /** Crash after replacement but before consume: do not write again; finish only after current reproof.
     * The caller must check SOURCE water, not merely an arbitrary non-stone state. */
    public boolean recoverCompletedWrite(String world, String operation, long position, Scope current,
            boolean currentSourceWater, boolean savedDirtyAndProofRearmed) {
        if (!currentSourceWater || !savedDirtyAndProofRearmed || current.currentStone()) return false;
        Scope authority = new Scope(current.activeOperation(), current.exactSelectedColumn(), current.canvas(),
                current.physicalProof(), current.authoredWaterBand(), current.protectedCell(), current.blockEntity(), true);
        return finish(world, operation, position, authority, true, true);
    }
    public List<Receipt> snapshot() { return List.copyOf(cells.values()); }
}
