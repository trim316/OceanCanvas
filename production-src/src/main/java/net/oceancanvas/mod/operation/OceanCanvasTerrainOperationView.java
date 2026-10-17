package net.oceancanvas.mod.operation;

import java.util.Objects;
import java.util.UUID;

/**
 * Dependency-light read-only view of the currently active terrain operation.
 *
 * <p>Concrete Pregen and Restore controllers remain the owners of their runtime
 * state. They are adapted into this model once at the composition root so
 * history, diagnostics, project services and networking do not need to import
 * controller-specific overlay or ownership record types.</p>
 */
public final class OceanCanvasTerrainOperationView {
    public record Overlay(String kind, String scopeName,
                          int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                          int cursorChunkX, int cursorChunkZ, long progressed, long total) {
        public Overlay {
            kind = safe(kind);
            scopeName = safe(scopeName);
            progressed = Math.max(0L, progressed);
            total = Math.max(0L, total);
        }
        public boolean active() { return !kind.isBlank(); }
    }

    public record Owner(String kind, UUID requesterId, String requesterDisplay) {
        public Owner {
            kind = safe(kind);
            requesterDisplay = safe(requesterDisplay);
        }
    }

    public interface Provider {
        Overlay overlaySnapshot();
        Owner ownerSnapshot();
    }

    private static final Provider IDLE = new Provider() {
        @Override public Overlay overlaySnapshot() { return null; }
        @Override public Owner ownerSnapshot() { return null; }
    };

    private static volatile Provider provider = IDLE;

    private OceanCanvasTerrainOperationView() { }

    public static void install(Provider next) {
        provider = Objects.requireNonNull(next, "provider");
    }

    public static Overlay overlaySnapshot() { return provider.overlaySnapshot(); }
    public static Owner ownerSnapshot() { return provider.ownerSnapshot(); }

    private static String safe(String value) { return value == null ? "" : value; }
}
