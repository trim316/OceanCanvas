package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Server-authoritative deep-health scan/metadata-repair request from the map control center. */
public record OceanCanvasHealthRequestPayload(String action) implements CustomPacketPayload {
    public static final Type<OceanCanvasHealthRequestPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"health_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,OceanCanvasHealthRequestPayload> STREAM_CODEC=StreamCodec.of(
            (buf,p)->ByteBufCodecs.STRING_UTF8.encode(buf,p.action()==null?"":p.action()),
            buf->new OceanCanvasHealthRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}