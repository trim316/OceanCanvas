package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Planning-layer metadata only; image bytes are intentionally transported separately later. */
public record OceanCanvasPlanningSyncPayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasPlanningSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "planning_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasPlanningSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> ByteBufCodecs.STRING_UTF8.encode(buf, payload.packed()),
            buf -> new OceanCanvasPlanningSyncPayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
