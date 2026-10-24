package net.oceancanvas.mod.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

/**
 * Cross-thread, fail-fast Save & Quit / process-shutdown preemption signal.
 *
 * <p>FastQuit can detach the client from an integrated server before Fabric's
 * SERVER_STOPPING callback is reached. Ocean Canvas therefore cannot use the
 * server lifecycle callback as the first time it stops feeding multi-second
 * terrain/light work. The client disconnect signal lives in the same JVM as
 * the integrated server and flips this atomic flag immediately. Server tick
 * workers then cooperatively return at bounded checkpoints; the ordinary
 * SERVER_STOPPING cleanup remains authoritative for persistence/ticket release.
 * A remote multiplayer disconnect is harmless because there is no server in
 * that client JVM, and the next integrated SERVER_STARTING resets the flag.
 */
public final class OceanCanvasShutdownCoordinator {
    private static final AtomicBoolean PREEMPT_REQUESTED = new AtomicBoolean(false);
    private static final AtomicBoolean OBSERVED_LOGGED = new AtomicBoolean(false);
    private static volatile String reason = "";
    private static volatile long requestedAtNanos = 0L;

    private OceanCanvasShutdownCoordinator() {}

    public static void resetForServerStart() {
        PREEMPT_REQUESTED.set(false);
        OBSERVED_LOGGED.set(false);
        reason = "";
        requestedAtNanos = 0L;
    }

    public static void request(String why) {
        if (PREEMPT_REQUESTED.compareAndSet(false, true)) {
            reason = why == null ? "unspecified" : why;
            requestedAtNanos = System.nanoTime();
        }
    }

    public static boolean requested() {
        return PREEMPT_REQUESTED.get();
    }

    public static boolean shouldPreempt(MinecraftServer server) {
        return PREEMPT_REQUESTED.get() || (server != null && !server.isRunning());
    }

    public static String reason() {
        return reason;
    }

    public static long requestedAtNanos() {
        return requestedAtNanos;
    }

    /** Log at most once from the server side, never from the render/network thread. */
    public static void logObservedOnce() {
        if (PREEMPT_REQUESTED.get() && OBSERVED_LOGGED.compareAndSet(false, true)) {
            net.oceancanvas.mod.OceanCanvas.LOGGER.info(
                    "(Ocean Canvas) SAVE-QUIT-PREEMPT build={} reason={} action=stop-admission-and-yield-active-work",
                    net.oceancanvas.mod.OceanCanvas.VERSION, reason);
        }
    }
}
