package net.oceancanvas.mod.pregen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Observation only. The existing governor continues using its own wall-cadence signal. */
public final class OceanCanvasTickTelemetry {
    private static OceanCanvasPregenMetrics.TickTiming timing=new OceanCanvasPregenMetrics.TickTiming();
    private static long tickSequence;
    private static final long VANILLA_AUTOSAVE_INTERVAL_TICKS = 6000L;
    private static final long AUTOSAVE_GUARD_BEFORE_TICKS = 120L;
    private static final long AUTOSAVE_GUARD_AFTER_TICKS = 40L;
    private OceanCanvasTickTelemetry(){}
    public static void register(){
        ServerTickEvents.START_SERVER_TICK.register(server->{ tickSequence++; timing.begin(System.nanoTime()); });
        ServerTickEvents.END_SERVER_TICK.register(server->timing.end(System.nanoTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{ timing=new OceanCanvasPregenMetrics.TickTiming(); tickSequence=0L; });
    }
    public static double workMs(){return timing.workMs();}
    public static double intervalMs(){return timing.intervalMs();}
    public static long tickSequence(){return tickSequence;}
    /**
     * v253.125.51: stop admitting new chunk mutations immediately before vanilla's
     * 6000-tick autosave cadence and briefly after it. The .50 runtime captured an
     * 8.75s stall exactly at tick sequence 6000 inside saveAllChunks/PalettedContainer.copy.
     * Existing work may retire, but the admission side must not add more dirty chunks
     * while the serializer is about to sweep the world.
     */
    public static boolean autosaveGuardActive(){
        long seq=tickSequence;
        if(seq < VANILLA_AUTOSAVE_INTERVAL_TICKS - AUTOSAVE_GUARD_BEFORE_TICKS) return false;
        long phase=seq % VANILLA_AUTOSAVE_INTERVAL_TICKS;
        return phase >= VANILLA_AUTOSAVE_INTERVAL_TICKS - AUTOSAVE_GUARD_BEFORE_TICKS
                || phase <= AUTOSAVE_GUARD_AFTER_TICKS;
    }
}
