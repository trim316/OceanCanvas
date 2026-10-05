package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Requests a server-authored, zero-mutation operation/config impact preview. */
public record OceanCanvasOperationPreviewRequestPayload(String kind, String target, String argument) implements CustomPacketPayload {
    public static final Type<OceanCanvasOperationPreviewRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"operation_preview_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,OceanCanvasOperationPreviewRequestPayload> STREAM_CODEC=StreamCodec.of(
            (b,p)->{ByteBufCodecs.STRING_UTF8.encode(b,p.kind());ByteBufCodecs.STRING_UTF8.encode(b,p.target());ByteBufCodecs.STRING_UTF8.encode(b,p.argument());},
            b->new OceanCanvasOperationPreviewRequestPayload(ByteBufCodecs.STRING_UTF8.decode(b),ByteBufCodecs.STRING_UTF8.decode(b),ByteBufCodecs.STRING_UTF8.decode(b)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
