package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: move a region ahead of another one in
 * precedence order.
 *
 * <p>Where two regions overlap and set the same rule differently, the one
 * defined first wins. The map screen shows that, which raised the obvious
 * next question - and then what? Deleting and redrawing the winning region
 * was the only answer, and it threw away that region's name, owner and
 * every other rule to fix an ordering problem. This is the non-destructive
 * answer: the losing region says "put me in front of that one", and
 * nothing else about either region changes.</p>
 *
 * <p>{@code beforeName} names the region to get in front of rather than
 * carrying a direction or an index. Precedence is only ever observable
 * between two regions that actually overlap, so "one step earlier" would
 * often move a region past something unrelated and appear to do nothing;
 * naming the target means one press produces one visible result.</p>
 *
 * <p>See {@link OceanCanvasZoneCreateRequestPayload}'s class doc for the
 * shared design notes - server-side permission re-validation, hand-rolled
 * codec shape - that apply equally here.</p>
 */
public record OceanCanvasZoneReorderRequestPayload(String name, String beforeName) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneReorderRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_reorder_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneReorderRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.beforeName());
			},
			buf -> new OceanCanvasZoneReorderRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					ByteBufCodecs.STRING_UTF8.decode(buf))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
