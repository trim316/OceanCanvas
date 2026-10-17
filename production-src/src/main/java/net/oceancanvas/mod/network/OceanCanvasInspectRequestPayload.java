package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Requests the authoritative Inspector/Explain-This snapshot for one block column. */
public record OceanCanvasInspectRequestPayload(int x, int z) implements CustomPacketPayload {
    public static final Type<OceanCanvasInspectRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "inspect_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasInspectRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> { ByteBufCodecs.VAR_INT.encode(buf, payload.x()); ByteBufCodecs.VAR_INT.encode(buf, payload.z()); },
            buf -> new OceanCanvasInspectRequestPayload(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
