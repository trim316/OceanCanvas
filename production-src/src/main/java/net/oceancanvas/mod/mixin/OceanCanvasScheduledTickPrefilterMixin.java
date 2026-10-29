package net.oceancanvas.mod.mixin;
import net.minecraft.server.level.ServerLevel;
import net.oceancanvas.mod.OceanCanvas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Mixin(ServerLevel.class)
abstract class OceanCanvasScheduledTickPrefilterMixin {
    @Unique private long oceancanvas$scheduledChecks;
    @Unique private long oceancanvas$untickingSkips;
    @Redirect(method="isPositionTickingWithEntitiesLoaded(J)Z", at=@At(value="INVOKE", target="Lnet/minecraft/server/level/ServerLevel;areEntitiesLoaded(J)Z"), require=1)
    private boolean oceancanvas$skipUntickingEntityLookup(ServerLevel world, long packed) {
        oceancanvas$scheduledChecks++;
        boolean blockTicking = world.shouldTickBlocksAt(packed);
        if (!blockTicking) {
            long skips = ++oceancanvas$untickingSkips;
            if ((skips & (skips - 1L)) == 0L) OceanCanvas.LOGGER.info("(Ocean Canvas) SCHEDULED-TICK-PREFILTER checks={} untickingSkips={} action=skip-entity-map-lookup-only-original-false-result", oceancanvas$scheduledChecks, skips);
        }
        return net.oceancanvas.mod.performance.OceanCanvasScheduledTickPrefilter.entitiesLoadedIfBlockTicking(blockTicking, () -> world.areEntitiesLoaded(packed));
    }
}
