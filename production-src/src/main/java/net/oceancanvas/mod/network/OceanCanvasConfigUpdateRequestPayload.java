package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** One settings edit from the map control center. Server validates and persists it. */
public record OceanCanvasConfigUpdateRequestPayload(String key, String value, String previewToken) implements CustomPacketPayload {
    public OceanCanvasConfigUpdateRequestPayload(String key,String value){this(key,value,"");}
    public static final Type<OceanCanvasConfigUpdateRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "config_update_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasConfigUpdateRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> { ByteBufCodecs.STRING_UTF8.encode(buf, payload.key()); ByteBufCodecs.STRING_UTF8.encode(buf, payload.value()); ByteBufCodecs.STRING_UTF8.encode(buf,payload.previewToken()==null?"":payload.previewToken()); },
            buf -> new OceanCanvasConfigUpdateRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
