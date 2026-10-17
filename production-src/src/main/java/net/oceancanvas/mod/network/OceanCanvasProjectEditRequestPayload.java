package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Generic project-metadata edit from the map: stage, notes, template, or current-project. */
public record OceanCanvasProjectEditRequestPayload(String name, String key, String value) implements CustomPacketPayload {
    public static final Type<OceanCanvasProjectEditRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "project_edit_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasProjectEditRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.key());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.value());
            },
            buf -> new OceanCanvasProjectEditRequestPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
