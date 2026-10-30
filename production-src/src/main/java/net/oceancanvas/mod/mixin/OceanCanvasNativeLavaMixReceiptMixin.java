package net.oceancanvas.mod.mixin;
import net.minecraft.core.*;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;
/** Observe the override that bypasses FlowingFluid's base spreadTo; never cancel vanilla physics. */
@Mixin(LavaFluid.class)
public class OceanCanvasNativeLavaMixReceiptMixin {
 @Unique private static final ThreadLocal<Deque<Optional<OceanCanvasSurfaceFlattener.NativeLavaMixCapture>>> ocean$mixFrames=ThreadLocal.withInitial(ArrayDeque::new);
 @Inject(method="spreadTo",at=@At("HEAD"))
 private void ocean$capture(LevelAccessor world,BlockPos pos,BlockState old,Direction direction,FluidState incoming,CallbackInfo ci){
  ocean$mixFrames.get().push(Optional.ofNullable(OceanCanvasSurfaceFlattener.captureNativeLavaMix(world,pos,old,direction,incoming)));
 }
 @Inject(method="spreadTo",at=@At("RETURN"))
 private void ocean$confirm(LevelAccessor world,BlockPos pos,BlockState old,Direction direction,FluidState incoming,CallbackInfo ci){
  var frames=ocean$mixFrames.get();if(frames.isEmpty())return;
  var capture=frames.pop();if(frames.isEmpty())ocean$mixFrames.remove();
  capture.ifPresent(OceanCanvasSurfaceFlattener::recordNativeLavaMix);
 }
}
