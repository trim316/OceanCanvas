package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: rename an existing zone, keeping its bounds,
 * protection state, owner and structure overrides intact.
 *
 * <p>Renaming exists because of how the map screen creates zones: dragging
 * a rectangle makes a zone immediately, under an auto-generated name
 * ({@code zone-1}, {@code zone-2}, ...), rather than stopping to demand a
 * name first. That ordering is deliberate - a drag that completes
 * instantly feels like a tool, a drag that opens a naming prompt feels
 * like a form - but it only works if naming it properly afterwards is
 * easy, which is what this payload is for. There is no {@code
 * /oceancanvas protect rename} equivalent for the same reason there was
 * never a need for one before now: from the command line you always
 * supplied the name up front.</p>
 *
 * <p>See {@link OceanCanvasZoneCreateRequestPayload}'s class doc for the
 * shared design notes - server-side permission re-validation, hand-rolled
 * codec shape - that apply equally here.</p>
 */
public record OceanCanvasZoneRenameRequestPayload(String name, String newName) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneRenameRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_rename_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneRenameRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.newName());
			},
			buf -> new OceanCanvasZoneRenameRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					ByteBufCodecs.STRING_UTF8.decode(buf))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
