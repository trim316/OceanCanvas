package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Compact server-authored Design Analysis result. */
public record OceanCanvasAnalysisResponsePayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasAnalysisResponsePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"analysis_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf,OceanCanvasAnalysisResponsePayload> STREAM_CODEC=StreamCodec.of(
            (buf,p)->ByteBufCodecs.STRING_UTF8.encode(buf,p.packed()),buf->new OceanCanvasAnalysisResponsePayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
