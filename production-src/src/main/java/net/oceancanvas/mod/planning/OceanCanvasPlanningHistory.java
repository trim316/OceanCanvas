package net.oceancanvas.mod.planning;

import net.minecraft.server.level.ServerPlayer;
import net.oceancanvas.mod.project.OceanCanvasPlanningData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped, server-authoritative undo/redo history for Plan vector objects.
 * Reference layers and Terrain Assets deliberately remain outside this history boundary.
 */
public final class OceanCanvasPlanningHistory {
    private static final int LIMIT = 30;

    private static final class SessionState {
        final Map<UUID, History> histories = new ConcurrentHashMap<>();
    }

    private OceanCanvasPlanningHistory() { }

    private static SessionState state(ServerPlayer player) {
        return net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime.get(player.level().getServer())
                .state(SessionState.class, SessionState::new);
    }

    public static void record(ServerPlayer player,
                              List<OceanCanvasPlanningData.PlanningObject> before,
                              List<OceanCanvasPlanningData.PlanningObject> after) {
        if (before.equals(after)) return;
        History h = state(player).histories.computeIfAbsent(player.getUUID(), ignored -> new History());
        h.undo.push(List.copyOf(before));
        while (h.undo.size() > LIMIT) h.undo.removeLast();
        h.redo.clear();
    }

    public static boolean undo(ServerPlayer player, OceanCanvasPlanningData data) {
        History h = state(player).histories.computeIfAbsent(player.getUUID(), ignored -> new History());
        if (h.undo.isEmpty()) return false;
        h.redo.push(data.objects());
        data.replaceObjects(h.undo.pop());
        return true;
    }

    public static boolean redo(ServerPlayer player, OceanCanvasPlanningData data) {
        History h = state(player).histories.computeIfAbsent(player.getUUID(), ignored -> new History());
        if (h.redo.isEmpty()) return false;
        h.undo.push(data.objects());
        data.replaceObjects(h.redo.pop());
        return true;
    }

    /** Explicit targeted cleanup; normal lifecycle cleanup is owned by OceanCanvasServerRuntime.close(server). */
    public static void clearSession(net.minecraft.server.MinecraftServer server) {
        net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime.clearStateIfOpen(server, SessionState.class);
    }

    private static final class History {
        final Deque<List<OceanCanvasPlanningData.PlanningObject>> undo = new ArrayDeque<>();
        final Deque<List<OceanCanvasPlanningData.PlanningObject>> redo = new ArrayDeque<>();
    }
}
