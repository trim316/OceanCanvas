package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: set or clear a region's display colour.
 *
 * <p>Purely cosmetic - see {@code OceanCanvasPlayerZones.Zone#color}'s
 * doc. A crowded canvas of a dozen same-looking amber rectangles is hard
 * to scan; a colour is a label, the same as a name, letting a region be
 * picked out at a glance instead of by reading text. It has no effect on
 * carving, structures, biome or mobs - nothing in this codebase ever
 * reads it to decide anything.</p>
 *
 * <p>{@code color} is one of a small fixed palette (see {@code
 * OceanCanvasPlayerZones#VALID_REGION_COLORS}), or empty to clear it back
 * to no custom colour. The SERVER validates it against that fixed set
 * before storing - same reasoning as {@link
 * OceanCanvasZoneSetBiomeRequestPayload} not trusting the client's own
 * button-enablement logic.</p>
 *
 * <p>See {@link OceanCanvasZoneCreateRequestPayload}'s class doc for the
 * shared design notes that apply equally here.</p>
 */
public record OceanCanvasZoneSetColorRequestPayload(String name, String color) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneSetColorRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_set_color_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneSetColorRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.color() == null ? "" : payload.color());
			},
			buf -> new OceanCanvasZoneSetColorRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					ByteBufCodecs.STRING_UTF8.decode(buf))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
