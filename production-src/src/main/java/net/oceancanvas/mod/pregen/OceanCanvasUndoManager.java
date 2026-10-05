package net.oceancanvas.mod.pregen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A real "undo my mistake" tool for {@code /oceancanvas reset} - the
 * "Undo + selection visualization" follow-up work the user asked for
 * alongside job persistence (see {@link OceanCanvasJobState}) and the
 * broadcast tuning across the command classes.
 *
 * <p><b>Deliberately scoped to {@code /oceancanvas reset} only - never
 * pregen, never expand.</b> Pregen only ever touches terrain nobody has
 * built on (freshly generated or already-canonical, see {@code
 * PregenManager#start}'s doc), so there's nothing meaningful to undo
 * there. Expand permanently changes {@code canvasSize} in {@code
 * oceancanvas.properties} - the config change itself is already
 * explicitly documented as not reversible by this mod (see {@code
 * ExpandCommand}'s "grow-only, never shrink" doc), so offering to undo
 * only the flattening half of an expand while leaving the config change
 * in place would be confusing and partial at best. Reset is the one
 * operation whose entire purpose is "destroy whatever's here on purpose,
 * possibly by mistake" - the actual use case undo exists for.</p>
 *
 * <p><b>Deliberately in-memory only, not persisted - a real, considered
 * choice, not an oversight.</b> Unlike {@link OceanCanvasJobState} (which
 * genuinely needs to survive a restart, since a large job can run for
 * minutes), undo is meant for "I just made a mistake, let me fix it right
 * now" - realistically used within the same session it was recorded in.
 * Persisting up to {@code undoDepthPerPlayer} (see {@link #maxUndoDepth}) operations of up to
 * {@link #MAX_UNDO_BLOCKS_PER_OPERATION} block records each, per player,
 * would be real, ongoing disk/schema cost for a feature whose whole
 * premise is "temporary, recent, and small" - if the server restarts
 * before you undo, the mistake is very likely already old news, and a
 * completely fresh {@code /oceancanvas reset} is the right tool at that
 * point anyway. This is the same "keep it simple, add the complexity only
 * once a simpler version is confirmed working" reasoning already used for
 * {@code PregenManager.Job}'s "no sliding window" v1 limitation.</p>
 *
 * <p><b>Real, honestly-documented limitation: block entity contents are
 * NOT restored, only block types/shapes.</b> A chest, sign, etc. destroyed
 * by a reset already loses its block entity data the moment {@code
 * LevelChunk#setBlockState} replaces it with a type that doesn't have one
 * - that's existing, pre-this-feature vanilla chunk behavior, not
 * something undo introduces. Correctly capturing and restoring arbitrary
 * block entity NBT (chest inventories, sign text, etc.) would need real,
 * currently-unconfirmed serialization APIs for this 26.2 build - rather
 * than ship a partial, fragile attempt at that and risk someone trusting
 * it to bring back a chest's contents when it silently can't, this undo
 * restores exactly what it can honestly guarantee (block types/shapes)
 * and says so plainly, both in the message printed the moment undo starts
 * (see {@link #undoMostRecent}) and here. Per this project's own standing
 * "eliminate the real risk, don't accept it as probably fine" principle -
 * the risk here is a player being misled about what "undo" restores, and
 * the fix is making the limitation impossible to miss, not pretending a
 * best-effort partial restore is a complete one.</p>
 *
 * <p><b>{@code /oceancanvas redo} (drafted this round, the "redo" half of
 * the "Progress UX + redo" follow-up) is deliberately NOT a second
 * recorded snapshot.</b> The obvious symmetric design - record the
 * post-carve "after" state the same way undo records the pre-carve
 * "before" state - was considered and rejected: it would double this
 * class's memory footprint for no real benefit, since {@code
 * flattenChunk} is already unconditionally idempotent (see {@code
 * PregenManager#rewipe}'s own doc comment) - re-running the exact same
 * reset over the exact same center/radius always recomputes the same
 * canonical target state, regardless of what happened to the world in
 * between. So redo just re-invokes {@link PregenManager#rewipe} with the
 * original center/radius, stored on {@link UndoOperation} alongside the
 * block records. A genuinely fresh {@code /oceancanvas reset} never
 * clears a player's redo stack (unlike a typical text editor) - this is
 * deliberate, not an oversight: since redo is always safe and correct to
 * re-apply regardless of what else happened since (the idempotence
 * guarantee again), there's no real "stale redo" risk here the way there
 * would be in an editor replaying a literal diff.</p>
 */
public final class OceanCanvasUndoManager {

	/**
	 * Only the last N reset operations are kept undoable per player - old
	 * ones simply age out (evicted, not an error) once a newer one is
	 * recorded. Matches this feature's own "recent mistake" premise (see
	 * the class doc) and keeps memory use bounded regardless of how many
	 * resets a long session runs.
	 *
	 * <p>Configurable this round ({@code undoDepthPerPlayer} in {@code
	 * OceanCanvasConfig}, previously a hardcoded constant here) - read
	 * fresh from config on every call rather than cached, matching how
	 * every other "safe to flip live" config value in this project is
	 * already read (e.g. {@code pregenChunksPerTick} inside {@code
	 * PregenManager.Job}), so a change takes effect immediately rather
	 * than needing a restart.</p>
	 */
	private static int maxUndoDepth() {
		return OceanCanvasConfig.get().undoDepthPerPlayer();
	}

	/**
	 * Hard cap on recorded blocks for a single reset operation - well
	 * under a size that would ever be a real memory concern, but more
	 * importantly a deliberate line between "small correction" (this
	 * tool's actual purpose, see {@code PregenManager#rewipe}'s "minimal
	 * correction tool" doc) and "large reset" (not meant to be undoable at
	 * this level of granularity - it just isn't recorded, an honest
	 * refusal rather than a silent partial/truncated undo, see {@link
	 * Recorder#record}).
	 */
	private static final long MAX_UNDO_BLOCKS_PER_OPERATION = 200_000;

	// Plain block writes during replay are much cheaper per-item than
	// pregen's chunk force-load dance, so a considerably higher per-tick
	// budget than PregenManager's chunks/tick is safe here.
	private static final int UNDO_BLOCKS_PER_TICK = 2_000;

	private static final Map<UUID, Deque<UndoOperation>> HISTORY = new HashMap<>();

	// Same depth cap and in-memory-only reasoning as HISTORY above -
	// populated only by #undoMostRecent (an operation becomes redo-able
	// the moment it's undone), consumed only by #redoMostRecent.
	private static final Map<UUID, Deque<UndoOperation>> REDO_HISTORY = new HashMap<>();

	private static Recorder activeRecorder;
	private static UndoJob activeUndoJob;

	private OceanCanvasUndoManager() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			UndoJob job;
			synchronized (OceanCanvasUndoManager.class) {
				job = activeUndoJob;
			}
			if (job == null) {
				return;
			}
			boolean done = job.tick(UNDO_BLOCKS_PER_TICK);
			if (done) {
				job.reportDone();
				synchronized (OceanCanvasUndoManager.class) {
					activeUndoJob = null;
				}
			}
		});
	}

	public static synchronized boolean isRunning() {
		return activeUndoJob != null;
	}

	/** Clears every world-owning in-memory undo reference at server shutdown. */
	public static synchronized void onServerStopping() {
		HISTORY.clear();
		REDO_HISTORY.clear();
		activeRecorder = null;
		activeUndoJob = null;
	}

	// --- Recording - called only from PregenManager#rewipe and
	// OceanCanvasSurfaceFlattener#flattenChunk, never from pregen/expand. ---

	/**
	 * Starts a new recording session for an about-to-run rewipe job. A
	 * no-op (no recorder started, nothing to undo later) for a
	 * console/command-block-initiated reset - there's no player to
	 * attribute an undo buffer to, matching {@code PregenManager.Job}'s
	 * own nullable {@code requestedBy} handling elsewhere. {@code
	 * centerBlockX}/{@code centerBlockZ}/{@code radiusChunks} are the
	 * exact arguments {@code PregenManager#rewipe} was called with -
	 * carried through onto the eventual {@link UndoOperation} purely so
	 * {@link #redoMostRecent} can re-invoke the same reset later, see
	 * this class's doc for why that's simpler and sufficient instead of
	 * recording a second "after" snapshot.
	 */
	public static synchronized void beginRecording(ServerLevel world, ServerPlayer requestedBy, String description,
			int centerBlockX, int centerBlockZ, int radiusChunks) {
		activeRecorder = requestedBy == null ? null
				: new Recorder(world, requestedBy.getUUID(), description, centerBlockX, centerBlockZ, radiusChunks);
	}

	/**
	 * Records one block's pre-carve state - called from {@code
	 * OceanCanvasSurfaceFlattener#flattenChunk} (a different package, so
	 * this is {@code public}, unlike most of this class's other internals)
	 * right before the raw write there, only reachable when a rewipe job is
	 * actively recording for that world (checked internally, so the call
	 * site there is unconditional and cheap when nothing is recording).
	 */
	public static synchronized void record(ServerLevel world, BlockPos pos, BlockState before) {
		if (activeRecorder != null && activeRecorder.world == world) {
			activeRecorder.record(pos, before);
		}
	}

	/**
	 * Ends the current recording session, for ANY reason the rewipe job
	 * that started it stopped (finished, cancelled, or {@code
	 * pregenEnabled} disabled mid-job) - commits it to that player's undo
	 * history if it recorded at least one block and never overflowed the
	 * cap; silently discards it otherwise (an empty operation has nothing
	 * to undo; an overflowed one was already logged as "too large to
	 * undo" at the moment it overflowed, see {@link Recorder#record}). A
	 * no-op if nothing is currently recording (the common case when the
	 * job that just stopped was pregen/expand, not reset).
	 */
	public static synchronized void finishRecording() {
		if (activeRecorder == null) {
			return;
		}
		Recorder recorder = activeRecorder;
		activeRecorder = null;
		if (recorder.overflowed || recorder.records.isEmpty()) {
			return;
		}
		Deque<UndoOperation> deque = HISTORY.computeIfAbsent(recorder.player, key -> new ArrayDeque<>());
		deque.addFirst(new UndoOperation(recorder.player, recorder.world, recorder.description, recorder.records,
				recorder.centerBlockX, recorder.centerBlockZ, recorder.radiusChunks));
		while (deque.size() > maxUndoDepth()) {
			deque.removeLast();
		}
	}

	// --- Undo - called from UndoCommand. ---

	/** This player's undoable operations, most recent first - for {@code /oceancanvas undo list}. */
	public static synchronized List<UndoOperation> history(UUID player) {
		Deque<UndoOperation> deque = HISTORY.get(player);
		return deque == null ? List.of() : new ArrayList<>(deque);
	}

	/**
	 * Starts undoing the given player's single most recent undoable reset
	 * (removing it from their history so a second {@code /oceancanvas
	 * undo} reaches the one before it, walking back through history in
	 * order - deliberately no "undo a specific numbered entry" support,
	 * see {@code UndoCommand}'s doc for why that's not needed). Returns a
	 * human-readable status message, same "message, not exception"
	 * convention as {@link PregenManager} - never throws for an ordinary
	 * bad state (nothing to undo, something already running, etc.).
	 */
	public static synchronized String undoMostRecent(UUID player) {
		if (activeUndoJob != null) {
			return "An undo is already running (" + activeUndoJob.describeProgress() + ").";
		}
		if (PregenManager.isRunning()) {
			return "A pregen/rewipe/restore/expand job is currently running - wait for it to finish (or run "
					+ "\"/oceancanvas pregen cancel\") before undoing, so the two don't modify the world at the "
					+ "same time.";
		}
		Deque<UndoOperation> deque = HISTORY.get(player);
		if (deque == null || deque.isEmpty()) {
			return "Nothing to undo - either you haven't run /oceancanvas reset yet this session, the operation "
					+ "was too large to record (see \"/oceancanvas undo\"'s own doc comment), or everything "
					+ "undoable has already been undone/aged out (only the last " + maxUndoDepth()
					+ " reset(s) are kept, per player, and none of this survives a server restart).";
		}
		UndoOperation op = deque.removeFirst();
		activeUndoJob = new UndoJob(op);
		// The operation becomes redo-able the instant it's popped, not
		// only once the (ticked, possibly multi-second) replay finishes -
		// simpler bookkeeping, and "can I redo this yet" matching "did I
		// just undo it" is the more intuitive behavior anyway.
		Deque<UndoOperation> redoDeque = REDO_HISTORY.computeIfAbsent(player, key -> new ArrayDeque<>());
		redoDeque.addFirst(op);
		while (redoDeque.size() > maxUndoDepth()) {
			redoDeque.removeLast();
		}
		String message = "Undoing " + op.description() + ": restoring " + op.records.size() + " block(s) "
				+ "(up to " + UNDO_BLOCKS_PER_TICK + "/tick). Block entity contents (chest items, sign text, "
				+ "etc.) that were destroyed are NOT restored - only block types/shapes. See \"/oceancanvas "
				+ "undo\"'s own documentation for why. Undone this session? \"/oceancanvas redo\" can bring it "
				+ "back.";
		OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
		return message;
	}

	/** This player's redo-able (just-undone) operations, most recent first - for {@code /oceancanvas redo list}. */
	public static synchronized List<UndoOperation> redoHistory(UUID player) {
		Deque<UndoOperation> deque = REDO_HISTORY.get(player);
		return deque == null ? List.of() : new ArrayList<>(deque);
	}

	/**
	 * Re-applies the given player's single most recent undone operation by
	 * re-invoking {@link PregenManager#rewipe} with its original center/
	 * radius - see this class's doc for why that's simpler and just as
	 * correct as replaying a saved "after" snapshot (never recorded).
	 * {@code player} must be non-null and online - unlike undo/list, redo
	 * needs a real {@code ServerPlayer} to pass through to {@code reset}
	 * as the new job's requester, not just a UUID.
	 */
	public static synchronized String redoMostRecent(ServerPlayer player) {
		if (activeUndoJob != null) {
			return "An undo is already running (" + activeUndoJob.describeProgress() + ") - wait for it to finish "
					+ "first.";
		}
		Deque<UndoOperation> deque = REDO_HISTORY.get(player.getUUID());
		if (deque == null || deque.isEmpty()) {
			return "Nothing to redo - you haven't undone anything this session, or everything undone has "
					+ "already been redone/aged out (only the last " + maxUndoDepth() + " are kept, per "
					+ "player, and none of this survives a server restart).";
		}
		UndoOperation op = deque.removeFirst();
		// Re-runs the actual rewipe job (force-load + flatten), NOT a
		// recorded block-state replay - see this class's doc for why.
		// PregenManager#rewipe does its own activeJob/undo-running checks,
		// so redo doesn't need to duplicate them here.
		String resetMessage = PregenManager.rewipe(op.world, op.centerBlockX, op.centerBlockZ, op.radiusChunks, true, player);
		return "Redoing " + op.description() + ": " + resetMessage;
	}

	public static synchronized String status() {
		if (activeUndoJob == null) {
			return "No undo is running.";
		}
		return "Undo in progress: " + activeUndoJob.describeProgress();
	}

	// ---

	private static final class Recorder {
		final ServerLevel world;
		final UUID player;
		final String description;
		final int centerBlockX;
		final int centerBlockZ;
		final int radiusChunks;
		final List<UndoBlockRecord> records = new ArrayList<>();
		boolean overflowed;

		Recorder(ServerLevel world, UUID player, String description, int centerBlockX, int centerBlockZ, int radiusChunks) {
			this.world = world;
			this.player = player;
			this.description = description;
			this.centerBlockX = centerBlockX;
			this.centerBlockZ = centerBlockZ;
			this.radiusChunks = radiusChunks;
		}

		void record(BlockPos pos, BlockState before) {
			if (overflowed) {
				return; // already reported below the first time this triggered - stay quiet after that
			}
			if (records.size() >= MAX_UNDO_BLOCKS_PER_OPERATION) {
				overflowed = true;
				// Free the memory now rather than waiting for
				// finishRecording to discard it - this operation will
				// never be committed either way, see finishRecording.
				records.clear();
				OceanCanvas.LOGGER.info(
						"(Ocean Canvas) A rewipe job passed {} changed blocks - too large to keep an undo record "
								+ "for. This only affects whether it's undoable; the reset itself is completely "
								+ "unaffected and keeps running normally.",
						MAX_UNDO_BLOCKS_PER_OPERATION);
				return;
			}
			// BlockPos and BlockState are both already immutable - safe
			// to store the exact instances directly, no defensive copy
			// needed.
			records.add(new UndoBlockRecord(pos, before));
		}
	}

	private record UndoBlockRecord(BlockPos pos, BlockState before) {
	}

	/** One completed, undoable reset operation - see {@link #finishRecording} for how this gets created. */
	public static final class UndoOperation {
		private final UUID player;
		private final ServerLevel world;
		private final String description;
		private final List<UndoBlockRecord> records;
		// The original reset's own arguments - kept purely so #redoMostRecent
		// can re-invoke PregenManager#rewipe later, see this class's doc for
		// why that's simpler than a second recorded "after" snapshot.
		private final int centerBlockX;
		private final int centerBlockZ;
		private final int radiusChunks;

		private UndoOperation(UUID player, ServerLevel world, String description, List<UndoBlockRecord> records,
				int centerBlockX, int centerBlockZ, int radiusChunks) {
			this.player = player;
			this.world = world;
			this.description = description;
			this.records = records;
			this.centerBlockX = centerBlockX;
			this.centerBlockZ = centerBlockZ;
			this.radiusChunks = radiusChunks;
		}

		public String description() {
			return description;
		}

		/** Count only - the actual per-block records stay package-private, nothing outside this file needs them directly. */
		public int recordCount() {
			return records.size();
		}
	}

	/**
	 * Replays one {@link UndoOperation}'s recorded block states back onto
	 * the world, budgeted per tick - deliberately NOT a single synchronous
	 * loop even though {@link #MAX_UNDO_BLOCKS_PER_OPERATION} keeps any
	 * one operation bounded, since up to 200,000 block writes in a single
	 * server tick would itself be exactly the kind of tick-stall risk this
	 * whole project has already been burned by once (see {@code
	 * PregenManager.Job}'s own "~900 ticks behind" doc) - caught and
	 * avoided here rather than assumed safe just because the upper bound
	 * happens to be finite.
	 *
	 * <p>Checks {@code isPositionTicking} and force-loads/retries exactly
	 * like {@code PregenManager.Job#tick}/{@code
	 * OceanCanvasSurfaceFlattener#neighborsReady} before writing each
	 * block, not just {@code hasChunk} - undo can restore a liquid block
	 * (a player's build might have included one), and placing a liquid
	 * needs a genuinely "ticking" chunk to schedule its fluid tick, the
	 * exact gap that caused the original spectator-flight freeze
	 * documented on {@code neighborsReady}. Reusing that same proven check
	 * here avoids reintroducing it via a different code path.</p>
	 *
	 * <p>Uses the standard {@code ServerLevel#setBlock} with update flags
	 * (not {@code Job}'s raw chunk-level write) - undo can restore
	 * arbitrary player-placed content, not just terrain, so the full
	 * light-recalculation/neighbor-update behavior that flag provides is
	 * the correct, safer choice here; the raw-write optimization elsewhere
	 * is specifically about safely carving a huge area fast, a concern
	 * that doesn't apply at undo's much smaller, capped scale.</p>
	 */
	private static final class UndoJob {
		private final UndoOperation operation;
		private int index;
		private int ticksSinceActionBar;

		UndoJob(UndoOperation operation) {
			this.operation = operation;
		}

		boolean tick(int budget) {
			ServerLevel world = operation.world;
			List<UndoBlockRecord> records = operation.records;
			int done = 0;
			while (done < budget && index < records.size()) {
				UndoBlockRecord record = records.get(index);
				// ChunkPos.containing(BlockPos), not new ChunkPos(BlockPos) -
				// confirmed via a real gradle runClient compile in this
				// sandbox that the record ChunkPos only has an (int,int)
				// constructor; containing() is the real BlockPos-accepting
				// factory.
				ChunkPos chunkPos = ChunkPos.containing(record.pos());
				if (!world.getChunkSource().isPositionTicking(chunkPos.pack())) {
					// Fire-and-forget, non-blocking - same dance as
					// PregenManager.Job#tick, see this method's own doc
					// for why isPositionTicking (not hasChunk) matters
					// here specifically.
					// v137: this call was actually NOT non-blocking despite the
					// comment above - getChunkFuture(...,true) synchronously blocks
					// the main thread when called this way. Fixed to use the real
					// non-blocking ticket path instead - see
					// OceanCanvasSurfaceFlattener#requestNonBlockingChunkLoad's doc.
					net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener.requestNonBlockingChunkLoad(
							world, chunkPos.x(), chunkPos.z());
					done++;
					break; // don't keep hammering the same not-ready position this tick - try the rest next tick
				}
				world.setBlock(record.pos(), record.before(), 3);
				index++;
				done++;
			}

			// Live action-bar progress - see ActionBarUtil's doc for why
			// this is a separate, much faster cadence than the plain chat
			// message #reportDone sends once at the very end.
			ticksSinceActionBar++;
			if (ticksSinceActionBar >= ActionBarUtil.INTERVAL_TICKS && index < records.size()) {
				ticksSinceActionBar = 0;
				ActionBarUtil.send(world.getServer().getPlayerList().getPlayer(operation.player),
						"[Ocean Canvas] undo " + ActionBarUtil.progressBar(index, records.size()));
			}

			return index >= records.size();
		}

		String describeProgress() {
			long percent = operation.records.isEmpty() ? 100 : (index * 100L) / operation.records.size();
			return index + "/" + operation.records.size() + " blocks restored (" + percent + "%)";
		}

		void reportDone() {
			String message = "Undo finished: " + operation.records.size() + " block(s) restored.";
			OceanCanvas.LOGGER.info("(Ocean Canvas) {}", message);
			ServerPlayer player = operation.world.getServer().getPlayerList().getPlayer(operation.player);
			if (player != null) {
				player.sendSystemMessage(Component.literal("[Ocean Canvas] " + message));
			}
			ActionBarUtil.send(player, "[Ocean Canvas] undo " + ActionBarUtil.progressBar(operation.records.size(), operation.records.size()));
		}
	}
}
