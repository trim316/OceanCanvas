package net.oceancanvas.mod.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.LongSet;import net.minecraft.world.level.chunk.LightChunkGetter;import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.oceancanvas.mod.worldgen.OceanCanvasNativeLightProofRevisions;import org.spongepowered.asm.mixin.*;
@Mixin(LayerLightSectionStorage.class)
abstract class OceanCanvasNativeLightProofRevisionMixin {
 @Shadow @Final protected LightChunkGetter chunkSource;
 @Shadow @Final protected LongSet changedSections;
 @Shadow @Final protected LongSet sectionsAffectedByLightUpdates;
 @WrapMethod(method="swapSectionMap()V")
 private void oceancanvas$bindProofRevision(Operation<Void> original){
  try(var publication=OceanCanvasNativeLightProofRevisions.begin(chunkSource,changedSections,sectionsAffectedByLightUpdates)){original.call();}
 }
}
