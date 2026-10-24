package net.oceancanvas.mod.lifecycle;

import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

/**
 * Dependency-light read-only view of durable terrain-operation checkpoints.
 *
 * <p>Pregen and Restore keep full ownership of their SavedData schemas. The
 * composition root adapts only the small amount of checkpoint identity that
 * diagnostics need, preventing the reliability harness from importing either
 * concrete persistence implementation.</p>
 *
 * <p>This boundary is observation-only. It cannot create, clear, advance or
 * rewrite a checkpoint. Until a provider is installed it fails idle.</p>
 */
public final class OceanCanvasTerrainOperationPersistence {
    public record Checkpoint(String kind, String scope) {
        public Checkpoint {
            kind = safe(kind);
            scope = safe(scope);
        }
    }

    public interface Provider {
        Checkpoint pregenCheckpoint(ServerLevel world);
        Checkpoint restoreCheckpoint(ServerLevel world);
    }

    private static final Provider IDLE = new Provider() {
        @Override public Checkpoint pregenCheckpoint(ServerLevel world) { return null; }
        @Override public Checkpoint restoreCheckpoint(ServerLevel world) { return null; }
    };

    private static volatile Provider provider = IDLE;

    private OceanCanvasTerrainOperationPersistence() { }

    public static void install(Provider next) {
        provider = Objects.requireNonNull(next, "terrain operation persistence provider");
    }

    public static Checkpoint pregenCheckpoint(ServerLevel world) {
        return provider.pregenCheckpoint(world);
    }

    public static Checkpoint restoreCheckpoint(ServerLevel world) {
        return provider.restoreCheckpoint(world);
    }

    public static boolean hasPersistedCheckpoint(ServerLevel world) {
        Provider p = provider;
        return p.pregenCheckpoint(world) != null || p.restoreCheckpoint(world) != null;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
