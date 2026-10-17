package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Queue view only; requests use the existing permission-checked workspace edit channel. */
public record OceanCanvasPregenQueuePayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasPregenQueuePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"pregen_queue"));
    public static final StreamCodec<RegistryFriendlyByteBuf,OceanCanvasPregenQueuePayload> STREAM_CODEC=StreamCodec.of(
            (buf,p)->ByteBufCodecs.STRING_UTF8.encode(buf,p.packed()),buf->new OceanCanvasPregenQueuePayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
