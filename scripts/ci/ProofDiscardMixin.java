package net.oceancanvas.test.mixin;
import net.oceancanvas.test.ProofDiscardProbe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener",remap=false)
public abstract class ProofDiscardMixin {
 @Inject(method="clearCooperativeFinalizerState",at=@At("HEAD"),require=1,remap=false)
 private static void proof$reset(long packed,CallbackInfo ci){ProofDiscardProbe.beforeReset(packed);}
 @Inject(method="advanceLiveChunkLightSweep",at=@At("RETURN"),require=1,remap=false)
 private static void proof$sweep(ServerLevel world,LevelChunk chunk,boolean includeSectionStatus,long packed,CallbackInfoReturnable<Boolean> cir){ProofDiscardProbe.sweepReturned(Boolean.TRUE.equals(cir.getReturnValue()));}
}
