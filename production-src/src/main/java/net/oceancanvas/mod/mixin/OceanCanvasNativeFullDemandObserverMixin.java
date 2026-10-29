package net.oceancanvas.mod.mixin;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerChunkCache;
import net.oceancanvas.mod.worldgen.OceanCanvasNativeFullDemandObserver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Mixin(ServerChunkCache.class)
public abstract class OceanCanvasNativeFullDemandObserverMixin {
 @Redirect(method="getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
  at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),require=1)
 private <T> CompletableFuture<T> oceanCanvas$observeExactNativeSupplier(Supplier<T> original,Executor executor) {
  return CompletableFuture.supplyAsync(OceanCanvasNativeFullDemandObserver.capture(original),executor);
 }
}
