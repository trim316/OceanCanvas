package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Server-authored Inspector/Explain-This response, packed for a compact read-only UI. */
public record OceanCanvasInspectResponsePayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasInspectResponsePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "inspect_response"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasInspectResponsePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> ByteBufCodecs.STRING_UTF8.encode(buf, payload.packed()),
            buf -> new OceanCanvasInspectResponsePayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
