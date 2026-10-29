package net.oceancanvas.mod.performance;
import java.util.function.BooleanSupplier;
/** The native ticking predicate already requires block-ticking range. No cached authority. */
public final class OceanCanvasScheduledTickPrefilter {
    public static boolean entitiesLoadedIfBlockTicking(boolean blockTicking, BooleanSupplier entityLookup) {
        return blockTicking && entityLookup.getAsBoolean();
    }
}
