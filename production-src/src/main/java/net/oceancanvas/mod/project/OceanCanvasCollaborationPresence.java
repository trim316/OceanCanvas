package net.oceancanvas.mod.project;

import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transient, opt-in P8 map cursor presence. Nothing here is persisted and stale cursors expire
 * automatically so disconnects/crashes cannot leave ghost collaborators on the planning map.
 */
public final class OceanCanvasCollaborationPresence {
    private OceanCanvasCollaborationPresence() { }
    private static final long TTL_MS = 15_000L;
    private static final ConcurrentHashMap<UUID, Cursor> CURSORS = new ConcurrentHashMap<>();

    public record Cursor(UUID uuid, String name, String dimension, int x, int z, String subjectId, long updatedAt) { }

    public static void update(ServerPlayer player, int x, int z, String subjectId) {
        if (player == null) return;
        CURSORS.put(player.getUUID(), new Cursor(player.getUUID(), player.getGameProfile().name(),
                String.valueOf(player.level().dimension()), x, z, subjectId == null ? "" : subjectId.trim(), System.currentTimeMillis()));
    }

    public static void remove(UUID uuid) { if (uuid != null) CURSORS.remove(uuid); }

    public static List<Cursor> snapshots() {
        long now = System.currentTimeMillis();
        CURSORS.entrySet().removeIf(e -> now - e.getValue().updatedAt() > TTL_MS);
        return List.copyOf(new ArrayList<>(CURSORS.values()));
    }

    public static void clear() { CURSORS.clear(); }
}
