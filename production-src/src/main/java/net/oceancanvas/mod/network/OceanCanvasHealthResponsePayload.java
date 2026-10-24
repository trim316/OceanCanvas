package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Compact deep-health result for the canonical map Health tab. */
public record OceanCanvasHealthResponsePayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasHealthResponsePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"health_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf,OceanCanvasHealthResponsePayload> STREAM_CODEC=StreamCodec.of(
            (buf,p)->ByteBufCodecs.STRING_UTF8.encode(buf,p.packed()==null?"":p.packed()),
            buf->new OceanCanvasHealthResponsePayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}