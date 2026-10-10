package net.oceancanvas.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.OceanCanvas;

/**
 * Server-to-client: the result of something the player just did, so the
 * map screen can say it where the player is actually looking.
 *
 * <p><b>Why this exists at all.</b> Every zone action the map screen
 * sends is answered in chat. That works fine from the command line and is
 * nearly useless from the screen: an open {@code Screen} covers the chat
 * HUD, so a rejection - "a region named 'harbour' already exists", "you
 * don't have permission", "that isn't a biome this world knows about" -
 * lands somewhere the player cannot see while the screen that caused it
 * is still in front of them. The action appeared to do nothing, and the
 * explanation was sitting behind the window.</p>
 *
 * <p>The server still sends the chat message too. This is additive: chat
 * remains the record of what happened, and this is the copy the screen
 * can show. Both come from the same one place ({@code
 * OceanCanvasNetworking#tell}), so they cannot disagree about the
 * wording.</p>
 *
 * <p>{@code error} only affects how the screen colours the line. The
 * server never relies on the client to interpret it - by the time this is
 * sent, the decision has already been made and applied or refused.</p>
 */
public record OceanCanvasFeedbackPayload(String message, boolean error) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<OceanCanvasFeedbackPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OceanCanvas.MOD_ID, "feedback"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OceanCanvasFeedbackPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.STRING_UTF8.encode(buf, payload.message() == null ? "" : payload.message());
				buf.writeBoolean(payload.error());
			},
			buf -> new OceanCanvasFeedbackPayload(ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
