package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Server-authoritative snapshot of every global setting exposed by Ocean Canvas. */
public record OceanCanvasConfigSyncPayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasConfigSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "config_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasConfigSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> ByteBufCodecs.STRING_UTF8.encode(buf, payload.packed()),
            buf -> new OceanCanvasConfigSyncPayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
