package net.oceancanvas.mod.mixin;
import net.minecraft.server.level.ServerLevel;import net.minecraft.util.ProgressListener;import net.oceancanvas.mod.worldgen.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;import com.llamalad7.mixinextras.injector.wrapoperation.Operation;import org.spongepowered.asm.mixin.Mixin;
@Mixin(ServerLevel.class)
abstract class OceanCanvasLightProofSaveBoundaryMixin {
 @WrapMethod(method="save(Lnet/minecraft/util/ProgressListener;ZZ)V")
 private void oceancanvas$saveCurrentProofMetadata(ProgressListener progress,boolean flush,boolean skip,Operation<Void> original){
  if(skip){original.call(progress,flush,true);return;}
  ServerLevel world=(ServerLevel)(Object)this;
  long[] before={0};
  OceanCanvasLightProofSaveBoundary.run(()->{OceanCanvasSurfaceFlattener.flushCompletedLightInvalidations(world);before[0]=OceanCanvasSurfaceFlattener.nativeLightMetadataRevision(world);},
   ()->original.call(progress,flush,false),()->{OceanCanvasSurfaceFlattener.flushCompletedLightInvalidations(world);return OceanCanvasSurfaceFlattener.nativeLightMetadataRevision(world)!=before[0]?1:0;},()->{
    // Only an existing explicit native flush-save uses its normal blocking SavedData API. Autosave remains asynchronous.
    var storage=world.getChunkSource().getDataStorage();if(flush)storage.saveAndJoin();else storage.scheduleSave();
   });
 }
}
