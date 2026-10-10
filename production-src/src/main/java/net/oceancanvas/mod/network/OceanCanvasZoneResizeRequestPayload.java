package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Client-to-server request: change an existing zone's horizontal extent,
 * keeping everything else about it.
 *
 * <p>Before this, correcting a zone that was drawn slightly wrong meant
 * deleting it and drawing a new one - losing its name, its owner and both
 * structure overrides in the process, and briefly leaving the area
 * unprotected in between. Dragging its edge is what every editor this
 * project is now being compared to does, and it is also the safer
 * operation: the zone never stops existing.</p>
 *
 * <p><b>Only X and Z travel over the wire.</b> The vertical range is left
 * exactly as it was rather than being resent, because a top-down map has
 * no way to express a Y range and should not be able to silently flatten
 * one: a zone created from {@code /oceancanvas protect pos1/pos2} may
 * deliberately cover only a build's real height, and having someone nudge
 * its edge on the map quietly expand that to the whole build limit would
 * be a real, invisible change to what gets protected.</p>
 *
 * <p>See {@link OceanCanvasZoneCreateRequestPayload}'s class doc for the
 * shared design notes - server-side permission re-validation, hand-rolled
 * codec shape - that apply equally here.</p>
 */
public record OceanCanvasZoneResizeRequestPayload(String name, int minX, int minZ, int maxX, int maxZ, String previewToken)
		implements CustomPacketPayload {
	public OceanCanvasZoneResizeRequestPayload(String name,int minX,int minZ,int maxX,int maxZ){this(name,minX,minZ,maxX,maxZ,"");}

	public static final CustomPacketPayload.Type<OceanCanvasZoneResizeRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "zone_resize_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasZoneResizeRequestPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
				buf.writeInt(payload.minX());
				buf.writeInt(payload.minZ());
				buf.writeInt(payload.maxX());
				buf.writeInt(payload.maxZ());
				ByteBufCodecs.STRING_UTF8.encode(buf,payload.previewToken()==null?"":payload.previewToken());
			},
			buf -> new OceanCanvasZoneResizeRequestPayload(
					ByteBufCodecs.STRING_UTF8.decode(buf),
					buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), ByteBufCodecs.STRING_UTF8.decode(buf))
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
