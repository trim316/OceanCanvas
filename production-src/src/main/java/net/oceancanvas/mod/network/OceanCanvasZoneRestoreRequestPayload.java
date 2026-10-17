package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Client-to-server request to restore one map region to vanilla generation. */
public record OceanCanvasZoneRestoreRequestPayload(String name, String previewToken) implements CustomPacketPayload {
    public OceanCanvasZoneRestoreRequestPayload(String name){this(name,"");}
    public static final Type<OceanCanvasZoneRestoreRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_restore_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneRestoreRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf,p)->{ByteBufCodecs.STRING_UTF8.encode(buf,p.name());ByteBufCodecs.STRING_UTF8.encode(buf,p.previewToken()==null?"":p.previewToken());},
            buf->new OceanCanvasZoneRestoreRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf),ByteBufCodecs.STRING_UTF8.decode(buf)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
