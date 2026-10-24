package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/** Client-to-server request to destructively re-carve one named protected region, then protect it again. */
public record OceanCanvasZoneRewipeRequestPayload(String name, String previewToken) implements CustomPacketPayload {
	public OceanCanvasZoneRewipeRequestPayload(String name){this(name,"");}
	public static final CustomPacketPayload.Type<OceanCanvasZoneRewipeRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_rewipe_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneRewipeRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());ByteBufCodecs.STRING_UTF8.encode(buf,payload.previewToken()==null?"":payload.previewToken());},
			buf -> new OceanCanvasZoneRewipeRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf),ByteBufCodecs.STRING_UTF8.decode(buf))
	);
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
