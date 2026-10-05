package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Server-authored preview plus the state fingerprint required to execute it. */
public record OceanCanvasOperationPreviewResponsePayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasOperationPreviewResponsePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"operation_preview_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf,OceanCanvasOperationPreviewResponsePayload> STREAM_CODEC=StreamCodec.of(
            (b,p)->ByteBufCodecs.STRING_UTF8.encode(b,p.packed()==null?"":p.packed()),b->new OceanCanvasOperationPreviewResponsePayload(ByteBufCodecs.STRING_UTF8.decode(b)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
