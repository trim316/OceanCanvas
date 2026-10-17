package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: "create a new protected zone with these
 * bounds and this name" - the drag-to-select half of the in-game Ocean
 * Canvas map screen (see {@code net.oceancanvas.mod.gui.OceanCanvasMapScreen}),
 * the direct visual replacement for typing {@code /oceancanvas protect
 * pos1}/{@code pos2}/{@code create}. Handled server-side in {@link
 * OceanCanvasNetworking}, which re-validates the requesting player's
 * permission before touching {@code OceanCanvasPlayerZones} at all - a
 * modified client sending this packet directly, bypassing the screen's
 * own button-enablement logic, gains nothing, since the server is the
 * only thing that actually mutates zone data.
 *
 * <p>Same hand-rolled {@code StreamCodec.of(...)} shape as {@link
 * OceanCanvasZoneSyncPayload} - see that class's doc for why this
 * project doesn't rely on {@code StreamCodec.composite}. Bounds are full
 * min/max ints, not chunk-radius-derived like {@code /oceancanvas
 * protect here} - the map screen's drag selection naturally produces an
 * arbitrary rectangle in block coordinates, matching {@code pos1}/{@code
 * pos2}/{@code create}'s precision, not {@code here}'s chunk-grid
 * convenience.</p>
 */
public record OceanCanvasZoneCreateRequestPayload(
		String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneCreateRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_create_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneCreateRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				buf.writeInt(payload.minX());
				buf.writeInt(payload.minY());
				buf.writeInt(payload.minZ());
				buf.writeInt(payload.maxX());
				buf.writeInt(payload.maxY());
				buf.writeInt(payload.maxZ());
			},
			buf -> new OceanCanvasZoneCreateRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					buf.readInt(), buf.readInt(), buf.readInt(),
					buf.readInt(), buf.readInt(), buf.readInt())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
