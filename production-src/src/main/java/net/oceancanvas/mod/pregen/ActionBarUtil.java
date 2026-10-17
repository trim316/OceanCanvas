package net.oceancanvas.mod.pregen;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tiny shared helper for the "Progress UX" follow-up request - live,
 * frequently-updated progress for long-running pregen/reset/expand/undo
 * jobs on the action bar, instead of only the existing every-~10s chat
 * log line ({@code PregenManager.Job#reportProgress}/{@code
 * OceanCanvasUndoManager.UndoJob}). A chat message that repeats every 10
 * seconds reads as spam over a multi-minute job; an action bar that
 * updates every half-second reads like a real mod's progress bar
 * (Chunky's own in-game overlay does something similar) - the chat log
 * line stays too, for anyone who wants a durable, scrollback-visible
 * record rather than a transient overlay.
 *
 * <p><b>Real, honestly-flagged API-shape risk, same category as {@code
 * ProtectCommand#outlineBounds}'s {@code sendParticles} note.</b> Sends
 * {@link ClientboundSetActionBarTextPacket} directly over the player's
 * connection rather than going through any higher-level convenience
 * method - this packet class and its single-{@code Component}-argument
 * constructor have been the standard, long-stable way to show action bar
 * text since the system-message rework years ago, and {@code
 * ServerPlayer#connection} being a public field of type {@code
 * ServerGamePacketListenerImpl} with a {@code send(Packet<?>)} method is
 * likewise long-stable, widely-used-by-mods territory - but neither is
 * actually confirmed against this exact 26.2 build (same sandbox
 * limitation as everywhere else in this project - no network access to a
 * real Loom-generated source). This whole feature is additive UX polish,
 * never load-bearing for anything else in the mod, so if this specific
 * call doesn't compile the fix is almost certainly a small adjustment
 * right here, not a deeper design problem.</p>
 */
final class ActionBarUtil {

	// How often (in ticks) the action bar refreshes while a job runs -
	// deliberately much more frequent than PregenManager's own
	// PROGRESS_REPORT_INTERVAL_TICKS (200 ticks / ~10s) chat log, since an
	// action bar that only updates every 10 seconds would look "stuck"
	// most of the time and can easily get overwritten by an unrelated
	// action bar message (item pickups, etc.) in between. 10 ticks (twice
	// a second) feels live without meaningfully increasing packet traffic
	// - a single small packet, not a broadcast.
	static final int INTERVAL_TICKS = 10;

	private static final int BAR_WIDTH = 20;

	private ActionBarUtil() {
	}

	/** Sends a one-off action bar update to {@code player} - a no-op if {@code player} is {@code null} (offline/console). */
	static void send(ServerPlayer player, String text) {
		if (player == null) {
			return;
		}
		player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(text)));
	}

	/**
	 * A small ASCII progress bar (deliberately '#'/'-', not block-drawing
	 * characters like '█') - plain ASCII is guaranteed to render in any
	 * font, where a block-drawing glyph's appearance in Minecraft's
	 * default font isn't something worth risking on cosmetic-only text.
	 */
	static String progressBar(long done, long total) {
		long safeTotal = Math.max(total, 1);
		int filled = (int) Math.min(BAR_WIDTH, (Math.max(0, done) * BAR_WIDTH) / safeTotal);
		StringBuilder bar = new StringBuilder(BAR_WIDTH);
		for (int i = 0; i < BAR_WIDTH; i++) {
			bar.append(i < filled ? '#' : '-');
		}
		long percent = total <= 0 ? 100 : Math.min(100, (done * 100L) / safeTotal);
		return "[" + bar + "] " + percent + "%";
	}
}
