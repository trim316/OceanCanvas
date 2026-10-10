package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: put the player at the centre of a zone.
 *
 * <p>The one action on the map screen that changes the world rather than
 * the mod's own data, and included deliberately: a map you can only look
 * at is a diagram, while a map you can travel from is a tool. Seeing a
 * zone on the far side of a 20,000-block canvas and then having to close
 * the screen and work out its coordinates by hand is exactly the kind of
 * seam that makes something feel bolted on.</p>
 *
 * <p><b>Gated at the same permission level as every other zone action</b>
 * ({@code oceancanvas.protect}, level 2 - see {@code
 * OceanCanvasNetworking}) rather than being treated as more sensitive
 * because it moves a player. That is the correct comparison: anyone who
 * can already delete a protected region, or run {@code /oceancanvas reset}
 * over a build, can trivially reach anywhere by other means; withholding
 * teleport from them would be theatre, not security. The server still
 * resolves the destination itself from its own zone data - the client
 * sends only a name, never coordinates, so a modified client cannot use
 * this to teleport somewhere arbitrary.</p>
 *
 * <p>See {@link OceanCanvasZoneCreateRequestPayload}'s class doc for the
 * shared design notes that apply equally here.</p>
 */
public record OceanCanvasZoneTeleportRequestPayload(String name) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneTeleportRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_teleport_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneTeleportRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> ByteBufCodecs.STRING_UTF8.encode(buf, payload.name()),
			buf -> new OceanCanvasZoneTeleportRequestPayload(ByteBufCodecs.STRING_UTF8.decode(buf))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
