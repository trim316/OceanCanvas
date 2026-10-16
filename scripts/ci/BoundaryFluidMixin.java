package net.oceancanvas.test.mixin;
import net.oceancanvas.test.BoundaryFluidProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Level.class)
public abstract class BoundaryFluidMixin {
 @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",at=@At("RETURN"))
 private void boundary$observe(BlockPos p,BlockState state,int flags,CallbackInfoReturnable<Boolean> cir){
  if((Object)this instanceof ServerLevel w)BoundaryFluidProbe.changed(w,p,state,Boolean.TRUE.equals(cir.getReturnValue()));
 }
}
