package net.oceancanvas.probe.upper.mixin;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.level.ChunkPos;import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(OceanCanvasSurfaceFlattener.class)
public interface LightOnlyScheduleInvoker {
 @Invoker("scheduleLightSync") static void schedule(ServerLevel world,ChunkPos pos,boolean repair){throw new AssertionError();}
}
