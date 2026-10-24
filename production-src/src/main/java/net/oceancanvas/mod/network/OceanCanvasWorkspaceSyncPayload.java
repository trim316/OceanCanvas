package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Compact project-workspace metadata. Screenshot/image bytes are never sent here. */
public record OceanCanvasWorkspaceSyncPayload(String packed) implements CustomPacketPayload {
    public static final Type<OceanCanvasWorkspaceSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "workspace_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasWorkspaceSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> ByteBufCodecs.STRING_UTF8.encode(buf, p.packed()),
            buf -> new OceanCanvasWorkspaceSyncPayload(ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
