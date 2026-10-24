package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: create a new zone at {@code minX..maxZ} that
 * starts with {@code sourceName}'s rules - structure overrides, biome
 * override, mob suppression - instead of the blank slate {@link
 * OceanCanvasZoneCreateRequestPayload} always produces. The map screen's
 * "C" shortcut (see {@code OceanCanvasMapScreen#duplicateSelectedZone()});
 * {@code /oceancanvas protect duplicate <name> <newName>} is the same
 * action from the command line, reusing that command's existing pos1/pos2
 * corners the way {@code create} already does.
 *
 * <p>Only the rules travel, never the bounds, the owner or the protection
 * state - those all come fresh from this request and from the requesting
 * player, exactly as they would for an ordinary {@code create}. A region's
 * value is very often the rule set built up on it one click at a time, not
 * its footprint; this is for reusing that rule set somewhere else on the
 * canvas, not for producing an indistinguishable second copy of an existing
 * region.</p>
 *
 * <p>{@code sourceName} names the region to copy rules FROM. It is read
 * once, server-side, at the moment this request is handled - there is no
 * ongoing link between the two regions afterward, and changing the source
 * later never touches the copy. No ownership check is made against the
 * source: reading another player's rule set to start a new region of your
 * own changes nothing about theirs.</p>
 *
 * <p>Same hand-rolled {@code StreamCodec.of(...)} shape and same
 * server-side re-validation posture as {@link
 * OceanCanvasZoneCreateRequestPayload} - see that class's doc.</p>
 */
public record OceanCanvasZoneDuplicateRequestPayload(
		String sourceName, String newName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneDuplicateRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_duplicate_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneDuplicateRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.sourceName());
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.newName());
				buf.writeInt(payload.minX());
				buf.writeInt(payload.minY());
				buf.writeInt(payload.minZ());
				buf.writeInt(payload.maxX());
				buf.writeInt(payload.maxY());
				buf.writeInt(payload.maxZ());
			},
			buf -> new OceanCanvasZoneDuplicateRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					ByteBufCodecs.STRING_UTF8.decode(buf),
					buf.readInt(), buf.readInt(), buf.readInt(),
					buf.readInt(), buf.readInt(), buf.readInt())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
