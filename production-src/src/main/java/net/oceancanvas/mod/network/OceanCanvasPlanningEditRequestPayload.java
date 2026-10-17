package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Generic server-authoritative planning-object edit from the map. */
public record OceanCanvasPlanningEditRequestPayload(String action, String id, String arg1, String arg2) implements CustomPacketPayload {
    public static final Type<OceanCanvasPlanningEditRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "planning_edit_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasPlanningEditRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.action());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.arg1());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.arg2());
            },
            buf -> new OceanCanvasPlanningEditRequestPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
