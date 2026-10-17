package net.oceancanvas.mod.lifecycle;

import net.minecraft.server.MinecraftServer;
import net.oceancanvas.mod.OceanCanvas;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Supplier;

/**
 * Server-identity-owned container for transient Ocean Canvas runtime state.
 *
 * <p>Integrated Minecraft can stop one server and start another in the same JVM. Session state
 * must therefore belong to the concrete {@link MinecraftServer}, not to process-global static
 * collections. Subsystems keep their own private state types and store them here by class key;
 * this class never needs to know their internals.</p>
 */
public final class OceanCanvasServerRuntime implements AutoCloseable {
    private static final Map<MinecraftServer, OceanCanvasServerRuntime> ACTIVE = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final Map<Class<?>, Object> states = new HashMap<>();
    private volatile boolean closed;

    private OceanCanvasServerRuntime(MinecraftServer server) { this.server = server; }

    /** Opens the runtime at SERVER_STARTING; idempotent for defensive duplicate lifecycle calls. */
    public static synchronized OceanCanvasServerRuntime open(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server");
        return ACTIVE.computeIfAbsent(server, OceanCanvasServerRuntime::new);
    }

    /**
     * Returns the runtime opened by SERVER_STARTING.
     *
     * <p>Lookup is deliberately fail-closed: a callback arriving after SERVER_STOPPING must not
     * resurrect a fresh runtime and retain state past the server lifetime.</p>
     */
    public static synchronized OceanCanvasServerRuntime get(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server");
        OceanCanvasServerRuntime runtime = ACTIVE.get(server);
        if (runtime == null || runtime.closed) {
            throw new IllegalStateException("Ocean Canvas server runtime is not active");
        }
        return runtime;
    }

    /** Read-only helper for single-server process-wide facades. Never creates state. */
    public static synchronized OceanCanvasServerRuntime onlyActiveOrNull() {
        OceanCanvasServerRuntime only = null;
        for (OceanCanvasServerRuntime runtime : ACTIVE.values()) {
            if (runtime == null || runtime.closed) continue;
            if (only != null && only != runtime) return null;
            only = runtime;
        }
        return only;
    }

    /** Clears one subsystem only when the owning server runtime is still active. Never reopens it. */
    public static synchronized void clearStateIfOpen(MinecraftServer server, Class<?> key) {
        if (server == null || key == null) return;
        OceanCanvasServerRuntime runtime = ACTIVE.get(server);
        if (runtime != null && !runtime.closed) runtime.clearState(key);
    }

    /** Removes exactly this server's runtime and releases all contained state. */
    public static synchronized void close(MinecraftServer server) {
        OceanCanvasServerRuntime runtime = ACTIVE.remove(server);
        if (runtime != null) runtime.close();
    }

    public MinecraftServer server() { return server; }

    public synchronized <T> T state(Class<T> key, Supplier<? extends T> factory) {
        if (closed) throw new IllegalStateException("Ocean Canvas server runtime is closed");
        Object value = states.computeIfAbsent(key, ignored -> factory.get());
        return key.cast(value);
    }

    /** Returns existing subsystem state without creating it. */
    public synchronized <T> T stateIfPresent(Class<T> key) {
        if (closed) return null;
        Object value = states.get(key);
        return value == null ? null : key.cast(value);
    }

    /** Drops one subsystem state without disturbing any other server/session service. */
    public synchronized void clearState(Class<?> key) {
        Object removed = states.remove(key);
        closeState(removed);
    }

    synchronized int stateCountForTests() { return states.size(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Object state : states.values()) closeState(state);
        states.clear();
    }

    private static void closeState(Object state) {
        if (!(state instanceof AutoCloseable closeable)) return;
        try { closeable.close(); }
        catch (Exception ex) {
            OceanCanvas.LOGGER.warn("(Ocean Canvas) Transient server-runtime state close failed: {}", ex.toString());
        }
    }
}
