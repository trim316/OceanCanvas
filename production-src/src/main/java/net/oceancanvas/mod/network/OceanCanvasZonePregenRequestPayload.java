package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Client-to-server request to pre-generate exactly the chunks belonging to one named map region. */
public record OceanCanvasZonePregenRequestPayload(String name, String previewToken) implements CustomPacketPayload {
    public OceanCanvasZonePregenRequestPayload(String name){this(name,"");}
    public static final CustomPacketPayload.Type<OceanCanvasZonePregenRequestPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_pregen_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZonePregenRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());ByteBufCodecs.STRING_UTF8.encode(buf,payload.previewToken()==null?"":payload.previewToken());},
            buf -> new OceanCanvasZonePregenRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf),ByteBufCodecs.STRING_UTF8.decode(buf))
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
