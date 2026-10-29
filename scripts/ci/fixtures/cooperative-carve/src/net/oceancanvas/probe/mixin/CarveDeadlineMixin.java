package net.oceancanvas.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Test-only conservative deadline fault; native world time and outcomes are untouched. */
@Mixin(OceanCanvasSurfaceFlattener.class)
public class CarveDeadlineMixin {
    private static final ThreadLocal<long[]> CALLS = new ThreadLocal<>();

    @WrapMethod(method = "flattenChunk")
    private static boolean fixture$scopedDeadline(ServerLevel world, LevelChunk chunk, Operation<Boolean> original) {
        CALLS.set(new long[]{0});
        try { return original.call(world, chunk); }
        finally { CALLS.remove(); }
    }

    @Redirect(method = "flattenChunk", at = @At(value = "INVOKE", target = "Ljava/lang/System;nanoTime()J"), require = 2)
    private static long fixture$conservativeClock() {
        long actual = System.nanoTime();
        long[] calls = CALLS.get();
        if (calls == null) throw new IllegalStateException("Missing scoped deadline fixture");
        return actual + calls[0]++ * 25_000_000L;
    }
}
