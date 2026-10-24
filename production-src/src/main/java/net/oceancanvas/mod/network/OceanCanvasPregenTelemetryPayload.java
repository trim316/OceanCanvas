package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Additive channel: leaves the established spatial job_status packet unchanged. */
public record OceanCanvasPregenTelemetryPayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasPregenTelemetryPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID,"pregen_telemetry"));
    public static final StreamCodec<RegistryFriendlyByteBuf,OceanCanvasPregenTelemetryPayload> STREAM_CODEC=StreamCodec.of(
            (buf,p)->ByteBufCodecs.STRING_UTF8.encode(buf,p.packed()),
            buf->new OceanCanvasPregenTelemetryPayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
