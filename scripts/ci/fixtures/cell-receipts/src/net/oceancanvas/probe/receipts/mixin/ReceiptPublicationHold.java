package net.oceancanvas.probe.receipts.mixin;
import net.oceancanvas.probe.receipts.ReceiptFixture;import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(OceanCanvasSurfaceFlattener.class) public class ReceiptPublicationHold {
 @Inject(method="drainPendingLightSync",at=@At("HEAD"),cancellable=true) private static void receipt$hold(CallbackInfo ci){if(ReceiptFixture.hold())ci.cancel();}
 @Inject(method="tryFastCertifyResidentCanvasLighting",at=@At("HEAD"),cancellable=true) private static void receipt$fast(CallbackInfoReturnable<Boolean> cir){if(ReceiptFixture.hold())cir.setReturnValue(false);}
}
