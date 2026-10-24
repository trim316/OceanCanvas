package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: set what a region says about one kind of
 * naturally generated structure - keep them, clear them, or follow the
 * world default.
 *
 * <p>{@code structureKind} is any {@code OceanCanvasStructureKind} id
 * ({@code "shipwreck"}, {@code "ocean_ruin"}, {@code "buried_treasure"},
 * {@code "ocean_monument"}); {@code override} is the target {@code
 * StructureOverride} enum name ({@code "INHERIT"}/{@code "FORCE_ON"}/
 * {@code "FORCE_OFF"}).</p>
 *
 * <p><b>One payload type for every kind, rather than one per kind.</b>
 * The only thing that varies between them is which kind {@code
 * OceanCanvasPlayerZones#setStructureOverride} is told about, so a kind
 * added to the enum needs no new packet, no new handler and no change
 * here. Both strings are validated server-side against the real enum and
 * kind names before use (see {@code OceanCanvasNetworking}'s receiver) -
 * an unrecognized value from a modified or buggy client is rejected with
 * a chat message, not silently misapplied.</p>
 *
 * <p>See {@link OceanCanvasZoneCreateRequestPayload}'s class doc for the
 * shared design notes - server-side permission re-validation, hand-rolled
 * codec shape - that apply equally here.</p>
 */
public record OceanCanvasZoneSetOverrideRequestPayload(String name, String structureKind, String override)
		implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneSetOverrideRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_set_override_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneSetOverrideRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.structureKind());
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.override());
			},
			buf -> new OceanCanvasZoneSetOverrideRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					ByteBufCodecs.STRING_UTF8.decode(buf),
					ByteBufCodecs.STRING_UTF8.decode(buf))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
