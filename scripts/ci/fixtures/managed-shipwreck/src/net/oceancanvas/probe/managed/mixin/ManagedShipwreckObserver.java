package net.oceancanvas.probe.managed.mixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.*;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;
import net.oceancanvas.probe.managed.ManagedShipwreckSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(OceanCanvasSurfaceFlattener.class)
public class ManagedShipwreckObserver {
 @Inject(method="relocateShipwreckIfNeeded",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;fillFromWorld(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Vec3i;ZLjava/util/List;)V"))
 private static void fixture$before(ServerLevel world,StructureStart start,CallbackInfoReturnable<BoundingBox> cir){ManagedShipwreckSnapshot.before(world,start);}
 @Inject(method="relocateShipwreckIfNeeded",at=@At("RETURN"))
 private static void fixture$after(ServerLevel world,StructureStart start,CallbackInfoReturnable<BoundingBox> cir){ManagedShipwreckSnapshot.after(world,start,cir.getReturnValue());}
}
