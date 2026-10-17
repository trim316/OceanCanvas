package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: "protect" / "unprotect" an existing zone -
 * the map screen's direct visual equivalent of {@code /oceancanvas
 * protect enable}/{@code disable}. See {@link
 * OceanCanvasZoneCreateRequestPayload}'s class doc for the shared
 * design notes (server-side re-validation, hand-rolled codec shape) that
 * apply equally here.
 */
public record OceanCanvasZoneSetEnabledRequestPayload(String name, boolean enabled) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneSetEnabledRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_set_enabled_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneSetEnabledRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				buf.writeBoolean(payload.enabled());
			},
			buf -> new OceanCanvasZoneSetEnabledRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
