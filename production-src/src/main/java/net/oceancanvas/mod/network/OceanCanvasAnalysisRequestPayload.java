package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Requests a read-only terrain cross-section between two X/Z points. */
public record OceanCanvasAnalysisRequestPayload(int x0, int z0, int x1, int z1, int samples) implements CustomPacketPayload {
    public static final Type<OceanCanvasAnalysisRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"analysis_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,OceanCanvasAnalysisRequestPayload> STREAM_CODEC=StreamCodec.of(
            (buf,p)->{ByteBufCodecs.VAR_INT.encode(buf,p.x0());ByteBufCodecs.VAR_INT.encode(buf,p.z0());ByteBufCodecs.VAR_INT.encode(buf,p.x1());ByteBufCodecs.VAR_INT.encode(buf,p.z1());ByteBufCodecs.VAR_INT.encode(buf,p.samples());},
            buf->new OceanCanvasAnalysisRequestPayload(ByteBufCodecs.VAR_INT.decode(buf),ByteBufCodecs.VAR_INT.decode(buf),ByteBufCodecs.VAR_INT.decode(buf),ByteBufCodecs.VAR_INT.decode(buf),ByteBufCodecs.VAR_INT.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
