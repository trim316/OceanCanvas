package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Compact server-authoritative project metadata for the map control center. */
public record OceanCanvasProjectSyncPayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasProjectSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "project_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasProjectSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> ByteBufCodecs.STRING_UTF8.encode(buf, payload.packed()),
            buf -> new OceanCanvasProjectSyncPayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
