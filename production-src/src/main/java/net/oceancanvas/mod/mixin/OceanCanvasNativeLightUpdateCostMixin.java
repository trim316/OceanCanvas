package net.oceancanvas.mod.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.oceancanvas.mod.worldgen.OceanCanvasNativeLightUpdateCost;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.Shadow;import org.spongepowered.asm.mixin.Final;
@Mixin(ThreadedLevelLightEngine.class)
abstract class OceanCanvasNativeLightUpdateCostMixin {
 @Shadow @Final private ObjectList<?> lightTasks;
 @WrapMethod(method="runUpdate()V")
 private void oceancanvas$observeNativeUpdate(Operation<Void> original){OceanCanvasNativeLightUpdateCost.GLOBAL.observe(lightTasks.size(),()->original.call());}
}
