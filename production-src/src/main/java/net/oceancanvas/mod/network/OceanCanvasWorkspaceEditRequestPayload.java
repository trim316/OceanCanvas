package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Generic, validated server-authoritative workspace edit request from the unified map UI. */
public record OceanCanvasWorkspaceEditRequestPayload(String action, String id, String arg1, String arg2) implements CustomPacketPayload {
    public static final Type<OceanCanvasWorkspaceEditRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "workspace_edit_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasWorkspaceEditRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf,p) -> { ByteBufCodecs.STRING_UTF8.encode(buf,p.action()); ByteBufCodecs.STRING_UTF8.encode(buf,p.id()); ByteBufCodecs.STRING_UTF8.encode(buf,p.arg1()); ByteBufCodecs.STRING_UTF8.encode(buf,p.arg2()); },
            buf -> new OceanCanvasWorkspaceEditRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
