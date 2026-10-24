package net.oceancanvas.mod.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(FlowingFluid.class)
public abstract class OceanCanvasBoundaryFluidMixin {
 @Inject(method="spreadTo",at=@At("HEAD"),cancellable=true)
 private void oceancanvas$keepAuditedBoundaryAir(LevelAccessor level,BlockPos pos,BlockState current,
         Direction direction,FluidState fluid,CallbackInfo ci) {
  if(OceanCanvasSurfaceFlattener.shouldRejectBoundaryFluidInflow(level,pos,current,direction,fluid))ci.cancel();
 }
}
