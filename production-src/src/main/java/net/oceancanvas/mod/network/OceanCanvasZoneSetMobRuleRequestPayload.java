package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: set whether a region keeps hostile mobs out.
 *
 * <p>See {@code OceanCanvasMobSuppressor} for what that rule actually
 * does - and what it deliberately does not touch - and {@link
 * OceanCanvasZoneCreateRequestPayload}'s class doc for the shared design
 * notes (server-side permission re-validation, hand-rolled codec shape)
 * that apply equally here.</p>
 */
public record OceanCanvasZoneSetMobRuleRequestPayload(String name, boolean suppress) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneSetMobRuleRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_set_mob_rule_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneSetMobRuleRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				buf.writeBoolean(payload.suppress());
			},
			buf -> new OceanCanvasZoneSetMobRuleRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
