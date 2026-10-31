package net.oceancanvas.probe.receipts.mixin;
import net.minecraft.core.*;import net.minecraft.world.level.LevelAccessor;import net.minecraft.world.level.block.state.BlockState;import net.minecraft.world.level.material.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(LavaFluid.class) public interface LavaSpreadInvoker {
 @Invoker("spreadTo") void receipt$nativeSpread(LevelAccessor world,BlockPos pos,BlockState old,Direction direction,FluidState incoming);
}
