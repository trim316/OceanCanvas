package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: paint a biome across a region, or clear the
 * one it has.
 *
 * <p>This is the other half of what Ocean Canvas means by editing. The mod
 * does not place blocks, raise land or build anything - it lets you draw a
 * region and say what should be true inside it. One of those things is
 * which biome it reads as, which is a purely cosmetic-and-ambient change
 * (water colour, fog, sounds, some spawn rules) applied at the same
 * four-block resolution vanilla already stores biomes at, with no terrain
 * touched at all.</p>
 *
 * <p>{@code biomeId} is a namespaced id such as {@code minecraft:warm_ocean},
 * or empty to clear the rule and let the region fall back to whatever the
 * world would otherwise say. The SERVER validates it against the real
 * biome registry before storing it - the client cannot be trusted to know
 * what datapacks are loaded, and an id that resolves on one side but not
 * the other is exactly the kind of mismatch that produces a rule which
 * silently does nothing.</p>
 *
 * <p>See {@link OceanCanvasZoneCreateRequestPayload}'s class doc for the
 * shared design notes that apply equally here.</p>
 */
public record OceanCanvasZoneSetBiomeRequestPayload(String name, String biomeId) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasZoneSetBiomeRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_set_biome_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneSetBiomeRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.biomeId() == null ? "" : payload.biomeId());
			},
			buf -> new OceanCanvasZoneSetBiomeRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					ByteBufCodecs.STRING_UTF8.decode(buf))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
