package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: delete a zone entirely (bounds forgotten,
 * not just disabled) - the map screen's equivalent of {@code
 * /oceancanvas protect remove}. See {@link
 * OceanCanvasZoneCreateRequestPayload}'s class doc for the shared design
 * notes that apply equally here.
 */
public record OceanCanvasZoneRemoveRequestPayload(String name) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneRemoveRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_remove_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneRemoveRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> ByteBufCodecs.STRING_UTF8.encode(buf, payload.name()),
			buf -> new OceanCanvasZoneRemoveRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
