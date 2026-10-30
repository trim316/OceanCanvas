package net.oceancanvas.probe.mixin;

import net.oceancanvas.probe.FreshCheckpointFixture;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;
import net.oceancanvas.mod.worldgen.OceanCanvasProtectedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Test-only publication pause. Vanilla generation/lighting and product bytecode are untouched. */
@Mixin(OceanCanvasSurfaceFlattener.class)
public class FreshCheckpointMixin {
 private static boolean reported;
 private static boolean hold(){return Boolean.getBoolean("oceancanvas.fixture.holdLighting");}
 @Inject(method="drainPendingLightSync",at=@At("HEAD"),cancellable=true)
 private static void fixture$holdFinalizer(CallbackInfo ci){if(hold())ci.cancel();}
 @Inject(method="tryFastCertifyResidentCanvasLighting",at=@At("HEAD"),cancellable=true)
 private static void fixture$holdFastPublication(CallbackInfoReturnable<Boolean> cir){if(hold())cir.setReturnValue(false);}
 @Inject(method="flattenChunkObserved",at=@At("RETURN"))
 private static void fixture$observeCompletedAuthoring(CallbackInfoReturnable<Boolean> cir){
  if(!hold()||reported||!cir.getReturnValueZ()||FreshCheckpointFixture.server==null)return;
  var world=FreshCheckpointFixture.server.overworld();if(world==null)return;
  var pos=new net.minecraft.world.level.ChunkPos(6,4);var data=OceanCanvasProtectedData.get(world);
  if(!data.isChunkProcessedPhysicallyVerified(pos))return;
  boolean authority=OceanCanvasSurfaceFlattener.physicalRepairRecoveryChunksSnapshot().contains(net.minecraft.world.level.ChunkPos.pack(6,4));
  boolean lighting=data.isChunkLightingVerified(pos);reported=true;
  System.out.println("FRESH-PHYSICAL-CHECKPOINT target=6,4 physical=true lighting="+lighting+" authority="+authority);
  if(!authority||lighting)throw new IllegalStateException("Fresh completed proof lost checkpoint authority or publication escaped hold");
 }
}


