package net.oceancanvas.mod.network;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.pregen.PregenManager;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;
import net.oceancanvas.mod.worldgen.OceanCanvasStructureKind;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side half of the zone-sync feature - see {@link
 * OceanCanvasZoneSyncPayload}'s class doc for the full story of why this
 * exists at all. Registers the payload type and broadcasts the current
 * zone list to every connected player: once immediately when they join
 * (so a minimap plugin has data right away, not just after the first
 * periodic tick), and again every {@link #BROADCAST_INTERVAL_TICKS} as
 * a simple, low-effort way to keep clients current without hooking
 * every individual zone-mutating command.
 *
 * <p><b>Client-to-server zone actions (this round)</b> - the in-game
 * Ocean Canvas map screen ({@code net.oceancanvas.mod.gui.OceanCanvasMapScreen})
 * needs a way to actually create/protect/unprotect/remove a zone and
 * change its structure overrides, not just read the periodic broadcast
 * this class already sent one-way. Six new request payloads ({@link
 * OceanCanvasZoneCreateRequestPayload}, {@link
 * OceanCanvasZoneSetEnabledRequestPayload}, {@link
 * OceanCanvasZoneRemoveRequestPayload}, {@link
 * OceanCanvasZoneSetOverrideRequestPayload}, {@link
 * OceanCanvasZoneRenameRequestPayload}, {@link
 * OceanCanvasZoneTeleportRequestPayload}) are registered and handled
 * here. Every handler re-validates the requesting player's permission
 * against the exact same nodes {@code ProtectCommand} already gates its
 * command tree with ({@code oceancanvas.protect} / {@code
 * oceancanvas.protect.override}), via {@code
 * ServerPlayer#createCommandSourceStack()} - the same, already-proven
 * {@code Permissions.require(...).test(CommandSourceStack)} shape {@code
 * ProtectCommand} itself uses, just fed a source built from the network
 * context's player instead of a command's. This is deliberate defense in
 * depth: the map screen's own buttons already only show actions a player
 * should be able to take, but a modified client sending one of these
 * packets directly must never be able to bypass the same permission
 * gate the ordinary command tree already enforces - the server, not the
 * client UI, is the actual authority here. Every mutation also triggers
 * an immediate {@link #broadcastToAll}, rather than waiting for the next
 * periodic tick, so the screen (and any minimap integration) sees the
 * change reflected within roughly a network round-trip, not up to
 * {@link #BROADCAST_INTERVAL_TICKS} later.</p>
 *
 * <p><b>Two APIs used here for the first time in this project, flagged
 * honestly:</b> {@code ServerPlayer#createCommandSourceStack()} (only the
 * resulting {@code Permissions.require(...).test(CommandSourceStack)}
 * call is previously proven, via {@code ProtectCommand}'s own command-
 * sourced calls - building that {@code CommandSourceStack} FROM a player
 * outside of a command context is new) and {@code
 * ServerPlayer#level()} (this project's existing command code reads
 * a level from {@code CommandSourceStack#getLevel()} instead, since a
 * command always has one already). Both are long-standing, ordinary
 * vanilla {@code ServerPlayer} methods with no history of the kind of
 * rename this project's worldgen code has repeatedly hit, so this is
 * low-risk, but still genuinely unconfirmed against an actual 26.2
 * compile like everything else in this project without one yet.</p>
 */
public final class OceanCanvasNetworking {

	private static final int BROADCAST_INTERVAL_TICKS = 100; // 5 seconds

	/** Job status only - see {@link OceanCanvasJobStatusPayload}'s class doc for why this one is fast and the others are not. */
	private static final int JOB_BROADCAST_INTERVAL_TICKS = 20; // 1 second

    // v76: terrain compatibility invalidations are batched, not packet-per-chunk.
    private static final java.util.Map<ServerLevel, java.util.Map<String, java.util.LinkedHashSet<Long>>>
            PENDING_TERRAIN_COMPAT = new java.util.WeakHashMap<>();

    // v253.125.29: client near-field light repair is diagnostic/republication work,
    // not a license for one C2S packet to run dozens of strict server proofs in a
    // single server tick. The .28 soak serviced request batches as large as 63
    // chunks synchronously while the integrated server was already recovering from
    // Pregen stalls. Queue/deduplicate them and drain a tiny round-robin budget from
    // END_SERVER_TICK instead. Correctness is unchanged: an authoritative bad field
    // still enters the strict LIGHT_ONLY finalizer and a healthy field is resent.
    private static final java.util.Map<UUID, it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet> PENDING_CLIENT_LIGHT_REPAIR =
            new java.util.LinkedHashMap<>();
    private static final int CLIENT_LIGHT_REPAIR_QUEUE_MAX_PER_PLAYER = 512;
    private static final int CLIENT_LIGHT_REPAIR_CHUNKS_PER_TICK = 4;
    private static final long CLIENT_LIGHT_REPAIR_WALL_BUDGET_NS = 3_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_LIGHT_REPAIR_QUEUED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_LIGHT_REPAIR_DEDUPED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_LIGHT_REPAIR_DROPPED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_LIGHT_REPAIR_DRAINED = new java.util.concurrent.atomic.AtomicLong();
    private static final long CLIENT_LIGHT_REPAIR_DRAIN_LOG_INTERVAL_NS = 1_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_LIGHT_REPAIR_LAST_DRAIN_LOG_NS = new java.util.concurrent.atomic.AtomicLong();
    // v253.125.33: do not turn a client reconnect/resync wave into more foreground
    // server work while the previous server tick or heap is already unhealthy.
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_LIGHT_REPAIR_PRESSURE_HOLDS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_LIGHT_REPAIR_LAST_PRESSURE_LOG_NS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CLIENT_LIGHT_REPAIR_NEXT_PRESSURE_ESCAPE_NS = new java.util.concurrent.atomic.AtomicLong();

    // v253.69.2: Project/Planning/Workspace are aggregate text snapshots and can
    // legitimately outgrow Minecraft's 32,767-character STRING_UTF8 ceiling. Keep
    // transfer framing independent of the payload record so existing client-side
    // parsers still consume one complete packed snapshot after atomic reassembly.
    private static final java.util.concurrent.atomic.AtomicLong METADATA_TRANSFER_IDS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<MinecraftServer, MetadataBroadcastState> METADATA_BROADCAST_STATE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final class MetadataBroadcastState {
        OceanCanvasZoneSyncPayload zones;
        OceanCanvasStructureSyncPayload structures;
        String project;
        String planning;
        String workspace;
    }

	private OceanCanvasNetworking() {
	}

	public static void register() {
		// Real fix, found from an actual "cannot find symbol method
		// playS2C()" compile error: this project's fabric_version
		// (0.156.0+26.2) is well past the javadoc versions (up to
		// 0.129.0+1.21.7) that still showed playS2C()/playC2S() -
		// confirmed a real rename via Fabric's own CURRENT official docs
		// (docs.fabricmc.net/develop/networking, dated for this
		// timeline): clientboundPlay()/serverboundPlay() are the
		// current names. "Clientbound" = going TO the client, the same
		// direction "S2C" meant - this is a server->client broadcast, so
		// clientboundPlay() is the direct replacement for playS2C().
		PayloadTypeRegistry.clientboundPlay().register(OceanCanvasZoneSyncPayload.TYPE, OceanCanvasZoneSyncPayload.STREAM_CODEC);
		// The map screen's other two read-only layers - see each payload's
		// class doc. Structures ride the same five-second broadcast as
		// zones so the two can never describe different moments; job
		// status gets its own, much faster cadence because it is the one
		// thing on the screen that actually moves.
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasStructureSyncPayload.TYPE, OceanCanvasStructureSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasJobStatusPayload.TYPE, OceanCanvasJobStatusPayload.STREAM_CODEC);
		// See OceanCanvasFeedbackPayload: an open Screen hides the chat
		// HUD, so every rejection this class already sends to chat needs a
		// copy the map screen itself can show.
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasFeedbackPayload.TYPE, OceanCanvasFeedbackPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasOperationPreviewResponsePayload.TYPE, OceanCanvasOperationPreviewResponsePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasConfigSyncPayload.TYPE, OceanCanvasConfigSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasProjectSyncPayload.TYPE, OceanCanvasProjectSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasPlanningSyncPayload.TYPE, OceanCanvasPlanningSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasWorkspaceSyncPayload.TYPE, OceanCanvasWorkspaceSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasInspectResponsePayload.TYPE, OceanCanvasInspectResponsePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OceanCanvasAnalysisResponsePayload.TYPE, OceanCanvasAnalysisResponsePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                OceanCanvasHealthResponsePayload.TYPE, OceanCanvasHealthResponsePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                OceanCanvasPregenTelemetryPayload.TYPE, OceanCanvasPregenTelemetryPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                OceanCanvasPregenQueuePayload.TYPE, OceanCanvasPregenQueuePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                OceanCanvasTerrainChangedPayload.TYPE, OceanCanvasTerrainChangedPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                OceanCanvasTerrainAckPayload.TYPE, OceanCanvasTerrainAckPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                OceanCanvasTerrainRepairRequestPayload.TYPE, OceanCanvasTerrainRepairRequestPayload.STREAM_CODEC);

        net.oceancanvas.mod.compat.OceanCanvasTerrainChangeBus.register(
                net.oceancanvas.mod.network.OceanCanvasNetworking::queueTerrainCompatibilityChange);

		// Serverbound zone-action payloads (this round) - see the class
		// doc's "client-to-server zone actions" paragraph. serverboundPlay(),
		// not clientboundPlay() - "S2C"/"C2S" direction naming applies
		// exactly as documented on the clientbound registration above,
		// just the other way.
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneCreateRequestPayload.TYPE, OceanCanvasZoneCreateRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneSetEnabledRequestPayload.TYPE, OceanCanvasZoneSetEnabledRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
                OceanCanvasZoneRewipeRequestPayload.TYPE, OceanCanvasZoneRewipeRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneRestoreRequestPayload.TYPE, OceanCanvasZoneRestoreRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                OceanCanvasZonePregenRequestPayload.TYPE, OceanCanvasZonePregenRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneRemoveRequestPayload.TYPE, OceanCanvasZoneRemoveRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneSetOverrideRequestPayload.TYPE, OceanCanvasZoneSetOverrideRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneRenameRequestPayload.TYPE, OceanCanvasZoneRenameRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneReorderRequestPayload.TYPE, OceanCanvasZoneReorderRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneDuplicateRequestPayload.TYPE, OceanCanvasZoneDuplicateRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneTeleportRequestPayload.TYPE, OceanCanvasZoneTeleportRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneResizeRequestPayload.TYPE, OceanCanvasZoneResizeRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneShapeRequestPayload.TYPE, OceanCanvasZoneShapeRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneSetBiomeRequestPayload.TYPE, OceanCanvasZoneSetBiomeRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneSetColorRequestPayload.TYPE, OceanCanvasZoneSetColorRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasZoneSetMobRuleRequestPayload.TYPE, OceanCanvasZoneSetMobRuleRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasConfigUpdateRequestPayload.TYPE, OceanCanvasConfigUpdateRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasOperationPreviewRequestPayload.TYPE, OceanCanvasOperationPreviewRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasInspectRequestPayload.TYPE, OceanCanvasInspectRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasAnalysisRequestPayload.TYPE, OceanCanvasAnalysisRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                OceanCanvasHealthRequestPayload.TYPE, OceanCanvasHealthRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasProjectStageRequestPayload.TYPE, OceanCanvasProjectStageRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasProjectEditRequestPayload.TYPE, OceanCanvasProjectEditRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasPlanningEditRequestPayload.TYPE, OceanCanvasPlanningEditRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				OceanCanvasWorkspaceEditRequestPayload.TYPE, OceanCanvasWorkspaceEditRequestPayload.STREAM_CODEC);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				broadcastTo(handler.getPlayer(), server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            synchronized (PENDING_CLIENT_LIGHT_REPAIR) {
                PENDING_CLIENT_LIGHT_REPAIR.remove(handler.getPlayer().getUUID());
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            synchronized (PENDING_CLIENT_LIGHT_REPAIR) {
                PENDING_CLIENT_LIGHT_REPAIR.clear();
            }
            CLIENT_LIGHT_REPAIR_LAST_DRAIN_LOG_NS.set(0L);
            CLIENT_LIGHT_REPAIR_LAST_PRESSURE_LOG_NS.set(0L);
            CLIENT_LIGHT_REPAIR_NEXT_PRESSURE_ESCAPE_NS.set(0L);
        });

		int[] ticksUntilBroadcast = {BROADCAST_INTERVAL_TICKS};
		int[] ticksUntilJobBroadcast = {JOB_BROADCAST_INTERVAL_TICKS};
		// Tracks whether the last job broadcast said "running", so the
		// single "it stopped" packet still gets sent once and the client
		// clears its overlay - without that, an idle server would either
		// spam an empty packet every second forever or leave a finished
		// job drawn on the map indefinitely.
		boolean[] jobWasRunning = {false};
		ServerTickEvents.END_SERVER_TICK.register(server -> {
            flushTerrainLightRepairRequests(server);
            flushTerrainCompatibilityChanges(server);
			ticksUntilBroadcast[0]--;
			if (ticksUntilBroadcast[0] <= 0) {
				ticksUntilBroadcast[0] = BROADCAST_INTERVAL_TICKS;
				broadcastToAll(server);
			}

			ticksUntilJobBroadcast[0]--;
			if (ticksUntilJobBroadcast[0] <= 0) {
				ticksUntilJobBroadcast[0] = JOB_BROADCAST_INTERVAL_TICKS;
                var performance=new OceanCanvasPregenTelemetryPayload(PregenManager.performanceSnapshot().encode());
                for(ServerPlayer player:server.getPlayerList().getPlayers())ServerPlayNetworking.send(player,performance);
				var overlay = net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.overlaySnapshot();
				if (overlay != null) {
					jobWasRunning[0] = true;
					broadcastJob(server, new OceanCanvasJobStatusPayload(overlay.kind(), overlay.scopeName(), overlay.minChunkX(), overlay.minChunkZ(), overlay.maxChunkX(), overlay.maxChunkZ(), overlay.cursorChunkX(), overlay.cursorChunkZ(), overlay.progressed(), overlay.total()));
				} else if (jobWasRunning[0]) {
					jobWasRunning[0] = false;
					broadcastJob(server, OceanCanvasJobStatusPayload.IDLE);
				}
			}
		});

		// Deliberately re-dispatched via MinecraftServer#execute rather
		// than acting on the network thread directly - genuinely
		// unconfirmed whether Fabric API's ServerPlayNetworking Play
		// receivers already run on the main server thread on this exact
		// build (unlike everywhere else in this project, this is the
		// first CLIENT-to-server receiver ever written here, so there's
		// no prior in-project confirmation either way to lean on). Safe
		// either way: execute() runs immediately if already on the main
		// thread, or schedules it there if not - either way, every actual
		// SavedData/world mutation below happens on the main thread, same
		// as every command handler elsewhere in this project already
		// gets for free from Brigadier.
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneCreateRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleCreate(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneSetEnabledRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleSetEnabled(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneRewipeRequestPayload.TYPE,
                (payload, context) -> context.server().execute(() -> handleRewipe(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneRestoreRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleRestore(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZonePregenRequestPayload.TYPE,
                (payload, context) -> context.server().execute(() -> handlePregenRegion(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneRemoveRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleRemove(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneSetOverrideRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleSetOverride(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneRenameRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleRename(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneReorderRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleReorder(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneDuplicateRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleDuplicate(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneTeleportRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleTeleport(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneResizeRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleResize(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneShapeRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleShape(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneSetBiomeRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleSetBiome(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneSetColorRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleSetColor(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasZoneSetMobRuleRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleSetMobRule(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasConfigUpdateRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleConfigUpdate(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasOperationPreviewRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleOperationPreview(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasInspectRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleInspect(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasAnalysisRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleAnalysis(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(OceanCanvasHealthRequestPayload.TYPE,
                (payload, context) -> context.server().execute(() -> handleHealth(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasProjectStageRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleProjectStage(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasProjectEditRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleProjectEdit(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasPlanningEditRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handlePlanningEdit(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(OceanCanvasWorkspaceEditRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> handleWorkspaceEdit(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(OceanCanvasTerrainRepairRequestPayload.TYPE,
                (payload, context) -> context.server().execute(() -> handleTerrainLightRepairRequest(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(OceanCanvasTerrainAckPayload.TYPE,
                (payload, context) -> context.server().execute(() -> {
                    OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-FINALIZE-ACK build={} player={} kind={} received={} vanillaLoaded={} voxyEnabled={} voxyIngested={} voxyPending={} skySamples={} skyAbove={}..{} skyWater={}..{} skyAboveNot15={} skyHash={}",
                            net.oceancanvas.mod.OceanCanvas.VERSION, context.player().getGameProfile().name(), payload.kind(),
                            payload.receivedChunks(), payload.vanillaLoadedChunks(), payload.voxyEnabled(),
                            payload.voxyIngestedChunks(), payload.voxyPendingChunks(), payload.skySamples(), payload.skyAboveMin(),
                            payload.skyAboveMax(), payload.skyWaterMin(), payload.skyWaterMax(), payload.skyAboveNot15(),
                            Long.toUnsignedString(payload.skyHash()));
                }));
	}

	// --- Shared permission helpers - same nodes/levels ProtectCommand's
	// command tree already gates on, just tested against a CommandSourceStack
	// built from the network context's player rather than a command's own
	// source. See the class doc's "client-to-server zone actions" paragraph
	// for why this re-check is necessary at all. ---

	private static boolean hasBasePermission(ServerPlayer player) {
		return Permissions.require("oceancanvas.protect", 2).test(player.createCommandSourceStack());
	}

    private static boolean hasPlanningViewPermission(ServerPlayer player) {
        return Permissions.require("oceancanvas.planning.view", 1).test(player.createCommandSourceStack());
    }

    private static boolean hasPlanningEditPermission(ServerPlayer player) {
        return Permissions.require("oceancanvas.planning.edit", 1).test(player.createCommandSourceStack());
    }

    private static boolean hasProjectManagePermission(ServerPlayer player) {
        return Permissions.require("oceancanvas.project.manage", 1).test(player.createCommandSourceStack());
    }

    private static boolean hasDiagnosticsPermission(ServerPlayer player) {
        return Permissions.require("oceancanvas.diagnostics.view", 1).test(player.createCommandSourceStack());
    }

    private static boolean hasAdminPermission(ServerPlayer player) {
        return Permissions.require("oceancanvas.admin", 2).test(player.createCommandSourceStack());
    }

	private static boolean canOverrideOwnership(ServerPlayer player) {
		return Permissions.require("oceancanvas.protect.override", 3).test(player.createCommandSourceStack());
	}

	private static boolean isArchived(ServerLevel world, String regionName) {
		var meta = net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).regionMeta(regionName);
		return meta != null && meta.parsedStage() == net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.ARCHIVED;
	}

	private static boolean rejectArchived(ServerLevel world, String regionName, ServerPlayer player, String action) {
		if (!isArchived(world, regionName)) return false;
		tell(player, "Region '" + regionName + "' is Archived. Move it out of Archived before you " + action + ".", true);
		return true;
	}

	/**
	 * Feedback on the map screen without a chat line.
	 *
	 * <p>For actions a player repeats rapidly while adjusting something -
	 * today just resize, which covers edge-dragging, moving a region and
	 * arrow-key nudging. Chat is a log of what happened to the world, and
	 * a log with one entry per block of a dragged boundary is not a log.
	 * The screen, which is transient by nature, is the right place for
	 * "yes, that worked, here is the result".</p>
	 */
	private static void tellOnScreen(ServerPlayer player, String message) {
		ServerPlayNetworking.send(player, new OceanCanvasFeedbackPayload(message, false));
	}

	/**
	 * Reports one action's result to the player, in chat AND to the map
	 * screen.
	 *
	 * <p>Both go out from here rather than from each handler, so the two
	 * can never drift apart in wording - and so that adding a new action
	 * cannot accidentally ship without screen feedback, which is exactly
	 * how every existing action ended up chat-only.</p>
	 */
	private static void tell(ServerPlayer player, String message) {
		tell(player, message, false);
	}

	/** As {@link #tell(ServerPlayer, String)}, but the screen colours an error differently. */
	private static void tell(ServerPlayer player, String message, boolean error) {
		player.sendSystemMessage(Component.literal("[Ocean Canvas] " + message));
		ServerPlayNetworking.send(player, new OceanCanvasFeedbackPayload(message, error));
	}

	private static void handleOperationPreview(OceanCanvasOperationPreviewRequestPayload payload, ServerPlayer player) {
		if(!hasBasePermission(player)){tell(player,"You do not have permission to preview Ocean Canvas operations.",true);return;}
		ServerLevel world=player.level();
		try{
			String kind=payload.kind()==null?"":payload.kind().trim().toUpperCase(java.util.Locale.ROOT);
			net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.Preview preview;
			if("CONFIG".equals(kind)){preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.config(world,payload.target(),payload.argument());}
			else if("REPAIR_METADATA".equals(kind)){preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.metadataRepair(world);}
			else if("REGION_GEOMETRY".equals(kind)){preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.regionGeometry(world,payload.target(),payload.argument());}
			else{
				var zone=net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(payload.target());
				if(zone==null){tell(player,"No zone named '"+payload.target()+"'.",true);return;}
				preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.region(world,kind,zone);
			}
			String packed=preview.kind()+"\t"+b64(preview.target())+"\t"+b64(preview.argument())+"\t"+preview.token()+"\t"+preview.blocked()+"\t"+preview.chunks()+"\t"+preview.processed()+"\t"+preview.canvas()+"\t"+preview.vanilla()+"\t"+preview.custom()+"\t"+preview.unknown()+"\t"+preview.linkedProjects()+"\t"+preview.linkedTasks()+"\t"+b64(preview.compatibilitySignature())+"\t"+b64(preview.summary())+"\t"+b64(String.join(" | ",preview.blockers()))+"\t"+b64(String.join(" | ",preview.warnings()))+"\t"+b64(preview.resourceForecast())+"\t"+b64(preview.resourceRisk())+"\t"+b64(preview.workflowGate())+"\t"+b64(preview.dryRunDiff());
			ServerPlayNetworking.send(player,new OceanCanvasOperationPreviewResponsePayload(packed));
			tellOnScreen(player,preview.summary());
		}catch(RuntimeException ex){tell(player,"Preview failed: "+ex.getMessage(),true);}
	}

	private static void handleCreate(OceanCanvasZoneCreateRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to create protected zones.", true);
			return;
		}
		ServerLevel world = player.level();
		net.minecraft.world.level.levelgen.structure.BoundingBox bounds =
				net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
						new net.minecraft.core.BlockPos(payload.minX(), payload.minY(), payload.minZ()),
						new net.minecraft.core.BlockPos(payload.maxX(), payload.maxY(), payload.maxZ()));
		// v106.1: map-created regions are drafts first. This lets the player
		// choose Shipwrecks=Always (and every other region rule) before Pregen
		// commits/protects the terrain. CLI /protect creation remains immediate.
		String error = OceanCanvasPlayerZones.get(world)
				.defineDraft(payload.name(), bounds, player.getUUID(), player.getName().getString());
		if (error != null) {
			tell(player, error, true);
			return;
		}
        net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).addWorldEvent("REGION",payload.name(),"CREATED","Region created","Draft region "+payload.minX()+","+payload.minZ()+" .. "+payload.maxX()+","+payload.maxZ(),player.getGameProfile().name(),"region:create",System.currentTimeMillis());
		tell(player, "Region '" + payload.name() + "' created as a draft. Set its rules, then Pregen or Protect when ready.");
		broadcastToAll(world.getServer());
	}

	private static void handleDuplicate(OceanCanvasZoneDuplicateRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to create protected zones.", true);
			return;
		}
		ServerLevel world = player.level();
		net.minecraft.world.level.levelgen.structure.BoundingBox bounds =
				net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
						new net.minecraft.core.BlockPos(payload.minX(), payload.minY(), payload.minZ()),
						new net.minecraft.core.BlockPos(payload.maxX(), payload.maxY(), payload.maxZ()));
		String error = OceanCanvasPlayerZones.get(world).duplicate(
				payload.sourceName(), payload.newName(), bounds, player.getUUID(), player.getName().getString());
		if (error != null) {
			tell(player, error, true);
			return;
		}
		tell(player, "Zone '" + payload.newName() + "' created, copying the rules from '" + payload.sourceName() + "'.");
		broadcastToAll(world.getServer());
	}

    private static void handlePregenRegion(OceanCanvasZonePregenRequestPayload payload, ServerPlayer player) {
        if (!hasBasePermission(player)) {
            tell(player, "You don't have permission to pre-generate regions.", true);
            return;
        }
        ServerLevel world = player.level();
        if (rejectArchived(world, payload.name(), player, "pre-generate it")) return;
        OceanCanvasPlayerZones.Zone zone = OceanCanvasPlayerZones.get(world).zoneByName(payload.name());
        if (zone == null) {
            tell(player, "No zone named '" + payload.name() + "'.", true);
            return;
        }
        if (zone.owner() != null && !zone.owner().equals(player.getUUID()) && !canOverrideOwnership(player)) {
            tell(player, "Zone '" + zone.name() + "' is owned by "
                    + (zone.ownerName() == null ? "another op" : zone.ownerName()) + ".", true);
            return;
        }
        var preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.region(world,"PREGEN",zone);
        if(preview.blocked()){tell(player,preview.summary(),true);return;}
        if(!net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.tokenMatchesRegion(world,"PREGEN",zone,payload.previewToken())){
            tell(player,"Pregen preview is missing or stale. Request/review a fresh dry run before starting.",true);return;
        }
        String message = PregenManager.startRegion(world, zone, player);
        boolean error = !message.startsWith("Started region pregen:");
        tell(player, message, error);
    }

	private static void handleSetEnabled(OceanCanvasZoneSetEnabledRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to change protected zones.", true);
			return;
		}
		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "change its protection state")) return;
		String operationLock = PregenManager.regionMutationBlockReason(payload.name());
		if (!operationLock.isEmpty()) { tell(player, operationLock, true); return; }
		String error = OceanCanvasPlayerZones.get(world)
				.setEnabled(payload.name(), payload.enabled(), player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		tell(player, "Zone '" + payload.name() + "' " + (payload.enabled() ? "protected." : "unprotected."));
		broadcastToAll(world.getServer());
	}

	private static void handleRewipe(OceanCanvasZoneRewipeRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to rewipe regions.", true);
			return;
		}
		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "rewipe it")) return;
		OceanCanvasPlayerZones zones = OceanCanvasPlayerZones.get(world);
		OceanCanvasPlayerZones.Zone zone = zones.zoneByName(payload.name());
		if (zone == null) {
			tell(player, "No zone named '" + payload.name() + "'.", true);
			return;
		}
		if (zone.owner() != null && !zone.owner().equals(player.getUUID()) && !canOverrideOwnership(player)) {
			tell(player, "Zone '" + zone.name() + "' is owned by "
					+ (zone.ownerName() == null ? "another op" : zone.ownerName()) + ".", true);
			return;
		}
		var preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.region(world,"REWIPE",zone);
		if(preview.blocked()){tell(player,preview.summary(),true);return;}
		if(!net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.tokenMatchesRegion(world,"REWIPE",zone,payload.previewToken())){
			tell(player,"Rewipe preview is missing or stale. Request/review a fresh dry run before starting.",true);return;
		}

		boolean wasProtected = zone.protectedNow();
		if (wasProtected) {
			String disableError = zones.setEnabled(zone.name(), false, player.getUUID(), canOverrideOwnership(player));
			if (disableError != null) {
				tell(player, disableError, true);
				return;
			}
		}

		String message = PregenManager.rewipeRegion(world, zone, player);
		if (!message.contains("Started region rewipe:")) {
			if (wasProtected) {
				zones.setEnabled(zone.name(), true, player.getUUID(), true);
			}
			tell(player, message, true);
			broadcastToAll(world.getServer());
			return;
		}
		tell(player, message);
		broadcastToAll(world.getServer());
	}

	private static void handleRestore(OceanCanvasZoneRestoreRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) { tell(player, "You don\'t have permission to restore regions to vanilla.", true); return; }
		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "restore it to vanilla")) return;
		OceanCanvasPlayerZones.Zone zone = OceanCanvasPlayerZones.get(world).zoneByName(payload.name());
		if (zone == null) { tell(player, "No zone named '" + payload.name() + "'.", true); return; }
		if (zone.owner() != null && !zone.owner().equals(player.getUUID()) && !canOverrideOwnership(player)) {
			tell(player, "Zone '" + zone.name() + "' is owned by " + (zone.ownerName() == null ? "another op" : zone.ownerName()) + ".", true); return;
		}
		var preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.region(world,"RESTORE",zone);
		if(preview.blocked()){tell(player,preview.summary(),true);return;}
		if(!net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.tokenMatchesRegion(world,"RESTORE",zone,payload.previewToken())){
			tell(player,"Restore preview is missing or stale. Request/review a fresh dry run before starting.",true);return;
		}
		String message = net.oceancanvas.mod.restore.OceanCanvasRestoreManager.restoreRegion(world, zone, player);
		tell(player, message, !message.contains("Started Restore to Vanilla:"));
	}

	private static void handleRemove(OceanCanvasZoneRemoveRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to remove protected zones.", true);
			return;
		}
		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "delete it")) return;
		String operationLock = PregenManager.regionMutationBlockReason(payload.name());
		if (!operationLock.isEmpty()) { tell(player, operationLock, true); return; }
		String error = OceanCanvasPlayerZones.get(world)
				.remove(payload.name(), player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).removeRegion(payload.name());
		net.oceancanvas.mod.project.OceanCanvasWorkspaceData.get(world).removeRegionReference(payload.name());
		tell(player, "Zone '" + payload.name() + "' deleted from the map screen. Project/history records were kept and detached from the deleted Region.");
		broadcastToAll(world.getServer());
	}

	private static void handleSetOverride(OceanCanvasZoneSetOverrideRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to change region structure rules.", true);
			return;
		}
		OceanCanvasStructureKind kind = OceanCanvasStructureKind.byId(payload.structureKind());
		if (kind == null) {
			tell(player, "Unrecognized structure kind '" + payload.structureKind() + "' - ignored.", true);
			return;
		}
		net.oceancanvas.mod.config.StructureOverride override;
		try {
			override = net.oceancanvas.mod.config.StructureOverride.valueOf(payload.override());
		} catch (IllegalArgumentException e) {
			tell(player, "Unrecognized rule value '" + payload.override() + "' - ignored.", true);
			return;
		}

		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "change its structure rules")) return;
		String error = OceanCanvasPlayerZones.get(world).setStructureOverride(
				payload.name(), kind, override, player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		tell(player, "Region '" + payload.name() + "': " + kind.displayName() + " set to " + override + ".");
		broadcastToAll(world.getServer());
	}

	/**
	 * Paints a biome across a region, or clears it.
	 *
	 * <p><b>The id is validated against the real biome registry here</b>,
	 * before anything is stored - see {@link
	 * OceanCanvasZoneSetBiomeRequestPayload}'s class doc. A typo, or an id
	 * from a datapack this server does not have, fails loudly with the
	 * reason rather than being written down and then quietly doing nothing
	 * every time the region is carved.</p>
	 */
	private static void handleSetBiome(OceanCanvasZoneSetBiomeRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to change region biomes.", true);
			return;
		}
		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "change its biome rule")) return;
		String biomeId = payload.biomeId() == null ? "" : payload.biomeId().trim();

		if (!biomeId.isEmpty()) {
			String problem = validateBiomeId(world, biomeId);
			if (problem != null) {
				tell(player, problem, true);
				return;
			}
		}

		String error = OceanCanvasPlayerZones.get(world).setBiomeOverride(
				payload.name(), biomeId.isEmpty() ? null : biomeId, player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		tell(player, biomeId.isEmpty()
				? "Region '" + payload.name() + "': biome rule cleared."
				: "Region '" + payload.name() + "': biome set to " + biomeId + ". "
						+ "Chunks already loaded need a reload (relog, or travel away and back) to show it.");
		broadcastToAll(world.getServer());
	}

	/**
	 * Sets or clears a region's display colour - see {@link
	 * OceanCanvasZoneSetColorRequestPayload}'s class doc. Validation
	 * against the fixed palette happens inside {@code
	 * OceanCanvasPlayerZones#setColor} itself (unlike biome, there is no
	 * registry lookup only the network layer can do), so this handler is
	 * just the usual permission check and error/success relay.
	 */
	private static void handleSetColor(OceanCanvasZoneSetColorRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to change region colours.", true);
			return;
		}
		ServerLevel world = player.level();
		String error = OceanCanvasPlayerZones.get(world).setColor(
				payload.name(), payload.color(), player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		tell(player, payload.color() == null || payload.color().isEmpty()
				? "Region '" + payload.name() + "': colour cleared."
				: "Region '" + payload.name() + "': colour set to " + payload.color() + ".");
		broadcastToAll(world.getServer());
	}

	/**
	 * {@code null} when the id names a real biome, otherwise a
	 * human-readable reason. Shared by the network handler and {@code
	 * ProtectCommand} so both reject exactly the same things.
	 */
	public static String validateBiomeId(ServerLevel world, String biomeId) {
		int colon = biomeId.indexOf(':');
		if (colon <= 0 || colon == biomeId.length() - 1) {
			return "'" + biomeId + "' isn't a namespaced biome id - try something like minecraft:warm_ocean.";
		}
		net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath(
				biomeId.substring(0, colon), biomeId.substring(colon + 1));
		net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> key =
				net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id);
		// Same lookupOrThrow(...).get(key) path OceanCanvasBiomeMasker
		// already uses - see that class's comment on why this, and not
		// registryOrThrow, is the access shape on this build.
		if (world.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).get(key).isEmpty()) {
			return "'" + biomeId + "' isn't a biome this world knows about.";
		}
		return null;
	}

	private static void handleRename(OceanCanvasZoneRenameRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to rename protected zones.", true);
			return;
		}
		ServerLevel world = player.level();
		String operationLock = PregenManager.regionMutationBlockReason(payload.name());
		if (!operationLock.isEmpty()) { tell(player, operationLock, true); return; }
		String error = OceanCanvasPlayerZones.get(world)
				.rename(payload.name(), payload.newName(), player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		String newName = payload.newName().trim();
		net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).renameRegion(payload.name(), newName);
		net.oceancanvas.mod.project.OceanCanvasWorkspaceData.get(world).renameRegionReference(payload.name(), newName);
		tell(player, "Zone '" + payload.name() + "' renamed to '" + newName + "'. Linked Project/history metadata moved with it.");
		broadcastToAll(world.getServer());
	}

	/**
	 * Moves a region ahead of another in precedence order - the
	 * non-destructive way to settle a rule conflict. See {@link
	 * OceanCanvasZoneReorderRequestPayload} for the reasoning, and {@code
	 * OceanCanvasPlayerZones#movePrecedenceBefore} for what it actually
	 * does to the order.
	 *
	 * <p>Broadcast to all, unlike resize: this changes which rules are in
	 * force where two regions meet, so anyone else with the map open is
	 * looking at a stale answer until they get the new order. It is also a
	 * deliberate, occasional action rather than something you do forty
	 * times while dragging an edge, so it belongs in chat too.</p>
	 */
	private static void handleReorder(OceanCanvasZoneReorderRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don't have permission to reorder protected zones.", true);
			return;
		}
		ServerLevel world = player.level();
		String error = OceanCanvasPlayerZones.get(world).movePrecedenceBefore(
				payload.name(), payload.beforeName(), player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		tell(player, "'" + payload.name() + "' now takes precedence over '" + payload.beforeName()
				+ "' where they overlap.");
		broadcastToAll(world.getServer());
	}

	/**
	 * Puts the player at the centre of a zone - see {@link
	 * OceanCanvasZoneTeleportRequestPayload}'s class doc for why this
	 * action exists and why it sits at the same permission level as the
	 * rest.
	 *
	 * <p><b>The destination is resolved here, from the server's own zone
	 * data</b> - the client sends a name and nothing else, so this can
	 * never be used to reach coordinates the sender chose. The Y is taken
	 * from the world's own surface heightmap rather than the zone's stored
	 * {@code maxY} (which for a map-screen-created zone is the top of the
	 * build limit, several hundred blocks of empty air above anything),
	 * with a floor at sea level so a teleport into a fully-carved part of
	 * the canvas surfaces the player in the water rather than dropping
	 * them onto the ocean floor.</p>
	 */
	private static void handleTeleport(OceanCanvasZoneTeleportRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to jump to protected zones.", true);
			return;
		}
		ServerLevel world = player.level();
		for (OceanCanvasPlayerZones.Zone zone : OceanCanvasPlayerZones.get(world).all()) {
			if (!zone.name().equals(payload.name())) {
				continue;
			}
			int x = (zone.bounds().minX() + zone.bounds().maxX()) / 2;
			int z = (zone.bounds().minZ() + zone.bounds().maxZ()) / 2;
			if (zone.hasExplicitShape() && !zone.contains(x, zone.bounds().minY(), z)) {
				long best = zone.chunks().iterator().next();
				double bestDist = Double.POSITIVE_INFINITY;
				for (long packed : zone.chunks()) {
					int cx = net.minecraft.world.level.ChunkPos.getX(packed) * 16 + 8;
					int cz = net.minecraft.world.level.ChunkPos.getZ(packed) * 16 + 8;
					double d = (double)(cx - x) * (cx - x) + (double)(cz - z) * (cz - z);
					if (d < bestDist) { bestDist = d; best = packed; }
				}
				x = net.minecraft.world.level.ChunkPos.getX(best) * 16 + 8;
				z = net.minecraft.world.level.ChunkPos.getZ(best) * 16 + 8;
			}

			// Only consult the heightmap when the destination chunk is
			// ALREADY loaded. LevelReader#getHeight resolves through
			// getChunk at FULL status, which would generate the chunk
			// synchronously right here - harmless once, but this project
			// has a real, logged incident from treating chunk loading as
			// free (see OceanCanvasSurfaceFlattener#neighborsReady), and
			// the fallback costs nothing: on a canvas world the surface at
			// an unexplored spot is the water surface anyway, and the
			// teleport itself loads the chunk a moment later regardless.
			int y = OceanCanvasConfig.WATER_SURFACE_Y + 1;
			if (world.hasChunk(x >> 4, z >> 4)) {
				// LevelReader#getHeight (unlike ChunkAccess#getHeight -
				// see OceanCanvasMapTerrain#sampleColumn for that exact
				// trap) already returns the first FREE Y, i.e. the block a
				// player stands in. Adding one would drop them in from a
				// block up.
				y = Math.max(y, world.getHeight(
						net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
			}

			// The plain same-dimension teleport, not one of the
			// dimension-changing overloads: those have been reshaped more
			// than once in recent vanilla versions (the TeleportTransition
			// rework), while this three-argument form has been on Entity
			// essentially forever. A zone always lives in the level the
			// player is already standing in, so the simpler call is both
			// sufficient and the safer bet.
			player.teleportTo(x + 0.5, y, z + 0.5);
			tell(player, "Jumped to zone '" + zone.name() + "'.");
			return;
		}
		tell(player, "No zone named '" + payload.name() + "'.", true);
	}

	private static void handleSetMobRule(OceanCanvasZoneSetMobRuleRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to change region mob rules.", true);
			return;
		}
		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "change its mob rules")) return;
		String error = OceanCanvasPlayerZones.get(world).setSuppressHostileMobs(
				payload.name(), payload.suppress(), player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		tell(player, payload.suppress()
				? "Region '" + payload.name() + "': hostile mobs will be kept out. Only applies while the region "
						+ "is protected, and any spawner or mob farm inside it will stop producing."
				: "Region '" + payload.name() + "': hostile mobs allowed again.");
		broadcastToAll(world.getServer());
	}

    private static String rectGeometryArgument(int minX,int minZ,int maxX,int maxZ){return "RECT:"+minX+","+minZ+","+maxX+","+maxZ;}
    private static String polygonGeometryArgument(String operation,java.util.List<Integer> vertices){
        StringBuilder s=new StringBuilder("POLY:").append(operation==null?"REPLACE":operation.toUpperCase(java.util.Locale.ROOT)).append(':');
        for(int i=0;i+1<vertices.size();i+=2){if(i>0)s.append(';');s.append(vertices.get(i)).append(',').append(vertices.get(i+1));}return s.toString();
    }

	private static void handleShape(OceanCanvasZoneShapeRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don't have permission to reshape regions.", true);
			return;
		}
		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "reshape it")) return;
		String operationLock = PregenManager.regionMutationBlockReason(payload.name());
		if (!operationLock.isEmpty()) { tell(player, operationLock, true); return; }
        String geometryArgument=polygonGeometryArgument(payload.operation(),payload.vertices());
        var preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.regionGeometry(world,payload.name(),geometryArgument);
        if(preview.blocked()){tell(player,preview.summary(),true);return;}
        var geometryZone=net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(payload.name());
        boolean requiresGeometryPreview=geometryZone!=null&&geometryZone.protectedNow() || preview.linkedProjects()>0 || preview.linkedTasks()>0 || !preview.warnings().isEmpty();
        if(requiresGeometryPreview && !net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.tokenMatchesRegionGeometry(world,payload.name(),geometryArgument,payload.previewToken())){tell(player,"Region-geometry preview is stale. Review the impact and confirm again.",true);return;}
        java.util.List<Long> authoritativeChunks="REPLACE".equalsIgnoreCase(payload.operation()) && payload.vertices()!=null && payload.vertices().size()>=6
                ? java.util.List.of() : payload.chunks();
		String error = OceanCanvasPlayerZones.get(world).editChunks(payload.name(), authoritativeChunks, payload.operation(), payload.vertices(),
				player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
        net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).addWorldEvent("REGION",payload.name(),"RESHAPED","Region geometry changed",preview.summary(),player.getGameProfile().name(),"region:shape",System.currentTimeMillis());
		tellOnScreen(player, "Updated region shape: " + payload.name());
		broadcastToAll(world.getServer());
	}

	private static void handleResize(OceanCanvasZoneResizeRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) {
			tell(player, "You don\'t have permission to resize protected zones.", true);
			return;
		}
		ServerLevel world = player.level();
		if (rejectArchived(world, payload.name(), player, "resize it")) return;
		String operationLock = PregenManager.regionMutationBlockReason(payload.name());
		if (!operationLock.isEmpty()) { tell(player, operationLock, true); return; }
        String geometryArgument=rectGeometryArgument(payload.minX(),payload.minZ(),payload.maxX(),payload.maxZ());
        var preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.regionGeometry(world,payload.name(),geometryArgument);
        if(preview.blocked()){tell(player,preview.summary(),true);return;}
        if(!net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.tokenMatchesRegionGeometry(world,payload.name(),geometryArgument,payload.previewToken())){tell(player,"Region-geometry preview is stale. Review the impact and confirm again.",true);return;}
		String error = OceanCanvasPlayerZones.get(world).resize(payload.name(),
				payload.minX(), payload.minZ(), payload.maxX(), payload.maxZ(),
				player.getUUID(), canOverrideOwnership(player));
		if (error != null) {
			tell(player, error, true);
			return;
		}
		// Deliberately NOT broadcast to other ops the way create/delete
		// are: dragging an edge is a fine-grained adjustment someone may
		// make several times in a row while getting a boundary right, and
		// announcing each nudge server-wide would be noise, not
		// information. The resulting bounds are visible to every other map
		// screen within a broadcast anyway.
		//
		// For the same reason this is the one success that answers on
		// SCREEN ONLY and stays out of chat - a held arrow key nudging a
		// boundary a block at a time would otherwise bury the chat log in
		// a message per block. But it does have to answer something:
		// without this line a successful resize was the only action on the
		// map screen that showed nothing at all, while a failed one showed
		// an error, which is precisely backwards.
        net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).addWorldEvent("REGION",payload.name(),"RESIZED","Region geometry changed",preview.summary(),player.getGameProfile().name(),"region:resize",System.currentTimeMillis());
		tellOnScreen(player, payload.name() + " is now "
				+ (Math.abs(payload.maxX() - payload.minX()) + 1) + " \u00d7 "
				+ (Math.abs(payload.maxZ() - payload.minZ()) + 1) + " blocks.");
		broadcastToAll(world.getServer());
	}

    /**
     * v253.45 targeted client-light recovery. The v253.44 runtime proved the
     * server field clean while the client near-field verifier continued finding
     * stale surface skylight. Re-send the exact authoritative chunk+all-light
     * packet already used by the server finalizer, but only for chunks this player
     * is already tracking. Never force-load arbitrary client-requested chunks.
     */
    private static void handleTerrainLightRepairRequest(
            OceanCanvasTerrainRepairRequestPayload payload, ServerPlayer player) {
        if (payload == null || player == null || payload.chunks() == null || payload.chunks().isEmpty()) return;
        UUID playerId = player.getUUID();
        int added = 0, deduped = 0, dropped = 0;
        synchronized (PENDING_CLIENT_LIGHT_REPAIR) {
            it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet queue = PENDING_CLIENT_LIGHT_REPAIR
                    .computeIfAbsent(playerId, ignored -> new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet());
            for (long packed : payload.chunks()) {
                if (queue.contains(packed)) { deduped++; continue; }
                if (queue.size() >= CLIENT_LIGHT_REPAIR_QUEUE_MAX_PER_PLAYER) { dropped++; continue; }
                queue.add(packed);
                added++;
            }
        }
        if (added > 0) CLIENT_LIGHT_REPAIR_QUEUED.addAndGet(added);
        if (deduped > 0) CLIENT_LIGHT_REPAIR_DEDUPED.addAndGet(deduped);
        if (dropped > 0) CLIENT_LIGHT_REPAIR_DROPPED.addAndGet(dropped);
        if (added > 0 || dropped > 0) {
            OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-LIGHT-RESYNC-QUEUED build={} player={} requested={} added={} deduped={} dropped={} totalQueued={} totalDrained={} action=bounded-server-tick-repair-queue",
                    net.oceancanvas.mod.OceanCanvas.VERSION, player.getGameProfile().name(), payload.chunks().size(),
                    added, deduped, dropped, CLIENT_LIGHT_REPAIR_QUEUED.get(), CLIENT_LIGHT_REPAIR_DRAINED.get());
        }
    }

    private static boolean holdClientLightRepairForRuntimePressure(MinecraftServer server) {
        if (server == null) return false;
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        double heap = max <= 0L ? 0.0D : (double)(rt.totalMemory() - rt.freeMemory()) / (double)max;
        double workMs = net.oceancanvas.mod.pregen.OceanCanvasTickTelemetry.workMs();
        double intervalMs = net.oceancanvas.mod.pregen.OceanCanvasTickTelemetry.intervalMs();
        boolean hold = heap >= 0.88D || workMs >= 70.0D || intervalMs >= 100.0D;
        long now = System.nanoTime();
        if (!hold) { CLIENT_LIGHT_REPAIR_NEXT_PRESSURE_ESCAPE_NS.set(0L); return false; }
        // Preserve visual-repair liveness under sustained high heap: one existing
        // 4-chunk server drain is allowed per second even while the normal drain is held.
        long nextEscape = CLIENT_LIGHT_REPAIR_NEXT_PRESSURE_ESCAPE_NS.get();
        if (nextEscape == 0L) {
            CLIENT_LIGHT_REPAIR_NEXT_PRESSURE_ESCAPE_NS.compareAndSet(0L, now + 1_000_000_000L);
        } else if (now >= nextEscape && CLIENT_LIGHT_REPAIR_NEXT_PRESSURE_ESCAPE_NS.compareAndSet(nextEscape, now + 1_000_000_000L)) {
            return false;
        }
        long holds = CLIENT_LIGHT_REPAIR_PRESSURE_HOLDS.incrementAndGet();
        long last = CLIENT_LIGHT_REPAIR_LAST_PRESSURE_LOG_NS.get();
        if ((last == 0L || now - last >= 5_000_000_000L)
                && CLIENT_LIGHT_REPAIR_LAST_PRESSURE_LOG_NS.compareAndSet(last, now)) {
            int pending = 0;
            synchronized (PENDING_CLIENT_LIGHT_REPAIR) {
                for (it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet queue : PENDING_CLIENT_LIGHT_REPAIR.values()) pending += queue.size();
            }
            OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-LIGHT-RESYNC-DRAIN-HOLD build={} pending={} heapPct={} previousTickWorkMs={} previousTickIntervalMs={} holds={} action=preserve-server-headroom-and-retain-deduped-queue",
                    net.oceancanvas.mod.OceanCanvas.VERSION, pending, Math.round(heap * 100.0D),
                    Math.round(workMs), Math.round(intervalMs), holds);
        }
        return true;
    }

    private static void flushTerrainLightRepairRequests(MinecraftServer server) {
        if (server == null) return;
        // v253.125.34: .33 evaluated pressure before checking whether any client
        // repair debt existed. A high-heap but empty queue therefore emitted a
        // CLIENT-LIGHT-RESYNC-DRAIN-HOLD heartbeat forever (714 lines in the soak)
        // and incremented a meaningless hold counter. Empty means no work: return
        // before heap/tick sampling, logging, or liveness-escape bookkeeping.
        synchronized (PENDING_CLIENT_LIGHT_REPAIR) {
            if (PENDING_CLIENT_LIGHT_REPAIR.isEmpty()) return;
        }
        if (holdClientLightRepairForRuntimePressure(server)) return;
        long started = System.nanoTime();
        int remainingBudget = CLIENT_LIGHT_REPAIR_CHUNKS_PER_TICK;
        int sent = 0, deferredServerRepair = 0, untracked = 0, unavailable = 0;
        int processed = 0;
        java.util.ArrayList<UUID> players;
        synchronized (PENDING_CLIENT_LIGHT_REPAIR) {
            if (PENDING_CLIENT_LIGHT_REPAIR.isEmpty()) return;
            players = new java.util.ArrayList<>(PENDING_CLIENT_LIGHT_REPAIR.keySet());
        }
        for (UUID playerId : players) {
            if (remainingBudget <= 0) break;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                synchronized (PENDING_CLIENT_LIGHT_REPAIR) { PENDING_CLIENT_LIGHT_REPAIR.remove(playerId); }
                continue;
            }
            while (remainingBudget > 0) {
                long packed;
                synchronized (PENDING_CLIENT_LIGHT_REPAIR) {
                    it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet queue = PENDING_CLIENT_LIGHT_REPAIR.get(playerId);
                    if (queue == null || queue.isEmpty()) {
                        PENDING_CLIENT_LIGHT_REPAIR.remove(playerId);
                        break;
                    }
                    it.unimi.dsi.fastutil.longs.LongIterator iterator = queue.iterator();
                    packed = iterator.nextLong();
                    iterator.remove();
                    if (queue.isEmpty()) PENDING_CLIENT_LIGHT_REPAIR.remove(playerId);
                }
                remainingBudget--;
                processed++;
                CLIENT_LIGHT_REPAIR_DRAINED.incrementAndGet();

                ServerLevel world = player.level();
                var chunkSource = world.getChunkSource();
                int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
                if (!net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(
                        world, new ChunkPos(cx, cz)).contains(player)) {
                    untracked++;
                } else {
                    var chunk = chunkSource.getChunkNow(cx, cz);
                    if (chunk == null) {
                        unavailable++;
                    } else {
                        // v253.125.30: keep this networking drain admission-only. The
                        // .29 soak caught a single item overrunning the entire wall budget
                        // while doing fluid scanning + strict SKY proof synchronously.
                        boolean serverRepairPending = net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener
                                .requestTrackedClientLightReverification(world, chunk);
                        if (serverRepairPending) {
                            deferredServerRepair++;
                        } else {
                            player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                    chunk, chunkSource.getLightEngine(), null, null));
                            sent++;
                        }
                    }
                }
                // Always service at least one request, then honor a small wall budget.
                if (processed > 0 && System.nanoTime() - started >= CLIENT_LIGHT_REPAIR_WALL_BUDGET_NS) break;
            }
            if (processed > 0 && System.nanoTime() - started >= CLIENT_LIGHT_REPAIR_WALL_BUDGET_NS) break;
        }
        if (processed > 0) {
            int pending = 0;
            synchronized (PENDING_CLIENT_LIGHT_REPAIR) {
                for (it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet queue : PENDING_CLIENT_LIGHT_REPAIR.values()) pending += queue.size();
            }
            long nowNs = System.nanoTime();
            long lastNs = CLIENT_LIGHT_REPAIR_LAST_DRAIN_LOG_NS.get();
            boolean queueDrained = pending == 0;
            boolean logDue = queueDrained || nowNs - lastNs >= CLIENT_LIGHT_REPAIR_DRAIN_LOG_INTERVAL_NS;
            if (logDue && (queueDrained || CLIENT_LIGHT_REPAIR_LAST_DRAIN_LOG_NS.compareAndSet(lastNs, nowNs))) {
                if (queueDrained) CLIENT_LIGHT_REPAIR_LAST_DRAIN_LOG_NS.set(nowNs);
                OceanCanvas.LOGGER.info("(Ocean Canvas) CLIENT-LIGHT-RESYNC-DRAIN build={} processed={} pending={} sent={} deferredServerRepair={} untracked={} unavailable={} elapsedMicros={} queuedTotal={} drainedTotal={} dedupedTotal={} droppedTotal={} action=bounded-server-tick-admission-to-cooperative-finalizer",
                        net.oceancanvas.mod.OceanCanvas.VERSION, processed, pending, sent, deferredServerRepair, untracked, unavailable,
                        (nowNs - started) / 1_000L, CLIENT_LIGHT_REPAIR_QUEUED.get(), CLIENT_LIGHT_REPAIR_DRAINED.get(),
                        CLIENT_LIGHT_REPAIR_DEDUPED.get(), CLIENT_LIGHT_REPAIR_DROPPED.get());
            }
        }
    }

    private static synchronized void queueTerrainCompatibilityChange(
            net.oceancanvas.mod.compat.OceanCanvasTerrainChange change) {
        // v253.69: the only current consumer is client-side near-field/Voxy repair,
        // which requires an actually resident LevelChunk. Do not even enqueue the
        // millions of far-field Pregen notifications that no client can use. A chunk
        // entering tracking later receives vanilla's current full chunk/light state.
        boolean trackedByAnyPlayer = !net.fabricmc.fabric.api.networking.v1.PlayerLookup
                .tracking(change.world(), change.chunkPos()).isEmpty();
        if (!trackedByAnyPlayer) return;
        PENDING_TERRAIN_COMPAT
                .computeIfAbsent(change.world(), ignored -> new java.util.LinkedHashMap<>())
                .computeIfAbsent(change.kind().name(), ignored -> new java.util.LinkedHashSet<>())
                .add(ChunkPos.pack(change.chunkPos().x(), change.chunkPos().z()));
    }

    private static synchronized void flushTerrainCompatibilityChanges(MinecraftServer server) {
        if (PENDING_TERRAIN_COMPAT.isEmpty()) return;
        java.util.Iterator<java.util.Map.Entry<ServerLevel, java.util.Map<String, java.util.LinkedHashSet<Long>>>> worlds =
                PENDING_TERRAIN_COMPAT.entrySet().iterator();
        while (worlds.hasNext()) {
            var worldEntry = worlds.next();
            ServerLevel world = worldEntry.getKey();
            if (world == null || world.getServer() != server) continue;
            for (var kindEntry : worldEntry.getValue().entrySet()) {
                java.util.LinkedHashSet<Long> pending = kindEntry.getValue();
                while (!pending.isEmpty()) {
                    java.util.List<Long> batch = new java.util.ArrayList<>(OceanCanvasTerrainChangedPayload.MAX_CHUNKS_PER_PACKET);
                    java.util.Iterator<Long> it = pending.iterator();
                    while (it.hasNext() && batch.size() < OceanCanvasTerrainChangedPayload.MAX_CHUNKS_PER_PACKET) {
                        batch.add(it.next());
                        it.remove();
                    }
                    // v253.69: far-field Pregen chunks are not resident on the client, so
                    // broadcasting them to every player only generated CLIENT-FINALIZE
                    // vanillaLoaded=0 work and could never feed Voxy's LevelChunk-based
                    // ingest hook. Send only the subset a player actually tracks. Vanilla
                    // supplies the current authoritative chunk/light state if an untracked
                    // chunk enters view later.
                    java.util.LinkedHashSet<Long> serverComparison = new java.util.LinkedHashSet<>();
                    java.util.LinkedHashMap<ServerPlayer, java.util.ArrayList<Long>> trackedByPlayer = new java.util.LinkedHashMap<>();
                    for (long packed : batch) {
                        int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
                        ChunkPos pos = new ChunkPos(cx, cz);
                        for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(world, pos)) {
                            trackedByPlayer.computeIfAbsent(player, ignored -> new java.util.ArrayList<>()).add(packed);
                        }
                    }
                    for (var trackedEntry : trackedByPlayer.entrySet()) {
                        java.util.ArrayList<Long> tracked = trackedEntry.getValue();
                        if (tracked.isEmpty()) continue;
                        serverComparison.addAll(tracked);
                        ServerPlayNetworking.send(trackedEntry.getKey(), new OceanCanvasTerrainChangedPayload(tracked, kindEntry.getKey()));
                    }
                    if (!serverComparison.isEmpty()) {
                        logServerTerrainSkySnapshot(world, kindEntry.getKey(), new java.util.ArrayList<>(serverComparison));
                    }
                }
            }
            worlds.remove();
        }
    }

    /** v253.25 server half of the client/server skylight comparison. */
    private static void logServerTerrainSkySnapshot(ServerLevel world, String kind, java.util.List<Long> chunks) {
        final int waterTop = net.oceancanvas.mod.config.OceanCanvasConfig.WATER_SURFACE_Y;
        int loaded = 0, samples = 0, aboveMin = 16, aboveMax = -1, waterMin = 16, waterMax = -1, aboveNot15 = 0;
        long hash = 0xcbf29ce484222325L;
        for (long packed : chunks) {
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            var chunk = world.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            loaded++;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            for (int lx : new int[]{2, 6, 10, 14}) for (int lz : new int[]{2, 6, 10, 14}) {
                int x = baseX + lx, z = baseZ + lz;
                var wp = new net.minecraft.core.BlockPos(x, waterTop, z);
                var ap = new net.minecraft.core.BlockPos(x, waterTop + 1, z);
                if (!chunk.getBlockState(wp).is(net.minecraft.world.level.block.Blocks.WATER) || !chunk.getBlockState(ap).isAir()) continue;
                int a = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, ap);
                int w = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, wp);
                samples++;
                aboveMin = Math.min(aboveMin, a); aboveMax = Math.max(aboveMax, a);
                waterMin = Math.min(waterMin, w); waterMax = Math.max(waterMax, w);
                if (a != 15) aboveNot15++;
                hash ^= (((long)x) << 32) ^ (z & 0xffffffffL) ^ ((long)a << 8) ^ w;
                hash *= 0x100000001b3L;
            }
        }
        if (samples == 0) { aboveMin = aboveMax = waterMin = waterMax = -1; }
        OceanCanvas.LOGGER.info("(Ocean Canvas) SERVER-FINALIZE-SKY build={} kind={} chunks={} loaded={} skySamples={} skyAbove={}..{} skyWater={}..{} skyAboveNot15={} skyHash={}",
                net.oceancanvas.mod.OceanCanvas.VERSION, kind, chunks.size(), loaded, samples, aboveMin, aboveMax, waterMin, waterMax, aboveNot15, Long.toUnsignedString(hash));
    }

	private static void broadcastToAll(MinecraftServer server) {
		if (server == null) {
			return;
		}
		OceanCanvasZoneSyncPayload payload = buildPayload(server);
		OceanCanvasStructureSyncPayload structures = buildStructurePayload(server);
		OceanCanvasProjectSyncPayload project = buildProjectPayload(server);
		OceanCanvasPlanningSyncPayload planning = buildPlanningPayload(server);
		OceanCanvasWorkspaceSyncPayload workspace = buildWorkspacePayload(server);

		// v253.69.2: the old five-second broadcast resent all three complete
		// aggregate metadata strings even when nothing had changed. Besides wasting
		// bandwidth during a multi-hour Pregen, that made the workspace_sync crash
		// inevitable the moment its single UTF string crossed 32,767 characters.
		// Reliable play networking means unchanged snapshots need no resend; JOIN
		// still force-sends all three below.
		MetadataBroadcastState state = METADATA_BROADCAST_STATE.computeIfAbsent(server, ignored -> new MetadataBroadcastState());
		boolean zonesChanged = !java.util.Objects.equals(state.zones, payload);
		boolean structuresChanged = !java.util.Objects.equals(state.structures, structures);
		boolean projectChanged = !java.util.Objects.equals(state.project, project.packed());
		boolean planningChanged = !java.util.Objects.equals(state.planning, planning.packed());
		boolean workspaceChanged = !java.util.Objects.equals(state.workspace, workspace.packed());
		if (zonesChanged) state.zones = payload;
		if (structuresChanged) state.structures = structures;
		if (projectChanged) state.project = project.packed();
		if (planningChanged) state.planning = planning.packed();
		if (workspaceChanged) state.workspace = workspace.packed();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (zonesChanged) ServerPlayNetworking.send(player, payload);
			if (structuresChanged) ServerPlayNetworking.send(player, structures);
			if (projectChanged) sendProjectSync(player, project.packed());
			if (planningChanged) sendPlanningSync(player, planning.packed());
			if (workspaceChanged) sendWorkspaceSync(player, workspace.packed());
		}
	}

	private static void broadcastJob(MinecraftServer server, OceanCanvasJobStatusPayload payload) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void broadcastTo(ServerPlayer player, MinecraftServer server) {
		ServerPlayNetworking.send(player, buildPayload(server));
		ServerPlayNetworking.send(player, buildConfigPayload());
		ServerPlayNetworking.send(player, buildStructurePayload(server));
		// JOIN is always a forced complete snapshot for this player, regardless of
		// what the unchanged-state suppression cache says was sent to existing peers.
		sendProjectSync(player, buildProjectPayload(server).packed());
		sendPlanningSync(player, buildPlanningPayload(server).packed());
		sendWorkspaceSync(player, buildWorkspacePayload(server).packed());
	}

	private static void sendProjectSync(ServerPlayer player, String packed) {
		sendMultipart(player, "project", packed, wire -> new OceanCanvasProjectSyncPayload(wire));
	}

	private static void sendPlanningSync(ServerPlayer player, String packed) {
		sendMultipart(player, "planning", packed, wire -> new OceanCanvasPlanningSyncPayload(wire));
	}

	private static void sendWorkspaceSync(ServerPlayer player, String packed) {
		sendMultipart(player, "workspace", packed, wire -> new OceanCanvasWorkspaceSyncPayload(wire));
	}

	private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void sendMultipart(
			ServerPlayer player, String feed, String packed, java.util.function.Function<String, T> factory) {
		long transferId = METADATA_TRANSFER_IDS.incrementAndGet();
		int maxLogicalBytes = switch (feed) {
			case "project" -> OceanCanvasMultipartSync.PROJECT_MAX_LOGICAL_BYTES;
			case "planning" -> OceanCanvasMultipartSync.PLANNING_MAX_LOGICAL_BYTES;
			default -> OceanCanvasMultipartSync.WORKSPACE_MAX_LOGICAL_BYTES;
		};
		java.util.List<String> frames;
		try {
			frames = OceanCanvasMultipartSync.encode(packed, transferId, maxLogicalBytes);
		} catch (IllegalArgumentException tooLarge) {
			OceanCanvas.LOGGER.error("(Ocean Canvas) metadata sync refused feed={} player={} reason={}", feed, player.getGameProfile().name(), tooLarge.getMessage());
			return;
		}
		if (frames.size() > 1) {
			OceanCanvas.LOGGER.debug("(Ocean Canvas) metadata sync feed={} bytes={} parts={} player={}",
					feed, OceanCanvasMultipartSync.utf8Length(packed == null ? "" : packed), frames.size(), player.getGameProfile().name());
		}
		for (String wire : frames) ServerPlayNetworking.send(player, factory.apply(wire));
	}

	/** Packs a region's structure rules into the sync payload's wire format - see {@link OceanCanvasZoneSyncPayload#formatRules}. */

	private static String b64(String value) {
		String safe = value == null ? "" : value;
		return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
				safe.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static String unb64(String value) {
		if (value == null || value.isBlank()) return "";
		return new String(java.util.Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
	}

	private static OceanCanvasProjectSyncPayload buildProjectPayload(MinecraftServer server) {
		ServerLevel world = server.overworld();
		if (world == null) return new OceanCanvasProjectSyncPayload("");
		var project = net.oceancanvas.mod.project.OceanCanvasProjectData.get(world);
		StringBuilder out = new StringBuilder();
		out.append("C\t").append(b64(project.currentProject()));
		for (var template : project.templates()) {
			out.append('\n').append("T\t").append(template.id()).append('\t').append(b64(template.displayName()));
		}
		out.append('\n').append("P\t").append(project.pregenProfile().name());
		String compatibilityBlock = net.oceancanvas.mod.project.OceanCanvasPersistenceCompatibility.destructiveOperationBlockReason(world);
		out.append('\n').append("R\t").append(compatibilityBlock.isEmpty() ? "NORMAL" : "READ_ONLY")
				.append('\t').append(b64(compatibilityBlock));
		var benchmark=project.benchmark();
		if(benchmark!=null) out.append('\n').append("B\t").append(benchmark.sustainableChunksPerSecond()).append('\t')
				.append(benchmark.healthyTickMs()).append('\t').append(benchmark.preferredOutstanding()).append('\t')
				.append(benchmark.updatedEpochMillis()).append('\t').append(benchmark.calibrationVersion()).append('\t')
				.append(benchmark.successfulRuns()).append('\t').append(benchmark.samples()).append('\t').append(benchmark.observedProfile());
		for(var row:net.oceancanvas.mod.project.OceanCanvasHarnessService.compatibilityMatrix(world))
			out.append('\n').append("V\t").append(b64(row.capability())).append('\t').append(row.status()).append('\t').append(row.epochMillis()).append('\t').append(b64(row.evidence()));

		var history=net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData.get(world).recent();
		for(int i=0;i<Math.min(12,history.size());i++){
			var e=history.get(i);
			out.append('\n').append("H\t").append(e.epochMillis()).append('\t').append(b64(e.kind())).append('\t')
					.append(e.phase()).append('\t').append(b64(e.requester())).append('\t').append(b64(e.detail())).append('\t')
					.append(e.scopeType()).append('\t').append(b64(e.scopeId())).append('\t').append(e.minChunkX()).append('\t')
					.append(e.minChunkZ()).append('\t').append(e.maxChunkX()).append('\t').append(e.maxChunkZ()).append('\t').append(b64(e.scopeDescriptor()));
		}
		var snapshots=net.oceancanvas.mod.project.OceanCanvasMetadataSnapshotData.get(world);
		var recentSnapshots=snapshots.recent();
		for(int i=0;i<Math.min(8,recentSnapshots.size());i++){
			var s=recentSnapshots.get(i);
			out.append('\n').append("S\t").append(b64(s.id())).append('\t').append(s.epochMillis()).append('\t')
					.append(b64(s.label())).append('\t').append(b64(s.reason())).append('\t')
					.append(s.project().regionCount()).append('\t').append(s.terrain().canvasCount()).append('\t').append(s.seals().physical());
		}
		for(String diff:snapshots.diffLatestTwo()){
			out.append('\n').append("D\t").append(b64(diff));
		}
		var lifecycleByRegion=new java.util.HashMap<String,String>();
		var lifecycleSeenRegion=new java.util.HashSet<String>();
		for(var e:net.oceancanvas.mod.operation.OceanCanvasOperationHistoryData.get(world).recent()){
			if(!"REGION".equalsIgnoreCase(e.scopeType())||e.scopeId().isBlank())continue;
			String kind=e.kind().toLowerCase(java.util.Locale.ROOT);
			if(!java.util.Set.of("pregen","rewipe","restore","expand").contains(kind))continue;
			String key=e.scopeId().toLowerCase(java.util.Locale.ROOT);
			// The first mutation entry is authoritative even when it is not Restore.
			// This prevents an older successful Restore from leaking through after a newer Pregen/Rewipe/Expand.
			if(!lifecycleSeenRegion.add(key))continue;
			if("restore".equals(kind)&&"COMPLETED".equalsIgnoreCase(e.phase()))lifecycleByRegion.put(key,"RESTORED");
		}
		for (OceanCanvasPlayerZones.Zone zone : OceanCanvasPlayerZones.get(world).all()) {
			var meta = project.regionMeta(zone.name());
			String stage = meta == null ? net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.RESERVED.name() : meta.stage();
			String notes = meta == null ? "" : meta.notes();
			String template = meta == null ? "" : meta.templateId();
			String lifecycle=switch(net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.parse(stage)){
				case ARCHIVED -> "ABANDONED"; case COMPLETE -> "COMPLETE"; case TERRAIN_CONSTRUCTION,DETAILING -> "PARTIAL"; default -> lifecycleByRegion.getOrDefault(zone.name().toLowerCase(java.util.Locale.ROOT),"PLANNED");
			};
			if (out.length() > 0) out.append('\n');
			out.append(b64(zone.name())).append('\t').append(stage).append('\t').append(b64(notes)).append('\t').append(template).append('\t').append(lifecycle);
		}
		return new OceanCanvasProjectSyncPayload(out.toString());
	}

	private static OceanCanvasPlanningSyncPayload buildPlanningPayload(MinecraftServer server) {
		ServerLevel world = server.overworld();
		if (world == null) return new OceanCanvasPlanningSyncPayload("");
		var data = net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world);
		StringBuilder out = new StringBuilder();
		for (var layer : data.referenceLayers()) {
			if (out.length() > 0) out.append('\n');
			StringBuilder registration = new StringBuilder();
			for (var rp : layer.registrationPoints()) {
				if (registration.length() > 0) registration.append(';');
				registration.append(rp.imageX()).append(',').append(rp.imageY()).append(',').append(rp.worldX()).append(',').append(rp.worldZ());
			}
			out.append("R\t").append(b64(layer.id())).append('\t').append(b64(layer.name())).append('\t')
					.append(b64(layer.assetId())).append('\t').append(layer.visible()).append('\t').append(layer.opacity()).append('\t')
					.append(layer.locked()).append('\t').append(layer.minX()).append('\t').append(layer.minZ()).append('\t')
					.append(layer.maxX()).append('\t').append(layer.maxZ()).append('\t').append(layer.rotationDegrees()).append('\t')
					.append(layer.drawOrder()).append('\t').append(b64(registration.toString()));
		}
		for (var object : data.objects()) {
			if (out.length() > 0) out.append('\n');
			StringBuilder points = new StringBuilder();
			for (var point : object.points()) {
				if (points.length() > 0) points.append(';');
				points.append(point.x()).append(',').append(point.z());
			}
			StringBuilder elevation = new StringBuilder();
			for (var ep : object.elevationProfile()) {
				if (elevation.length() > 0) elevation.append(';');
				elevation.append(ep.along()).append(',').append(ep.y());
			}
			out.append("V\t").append(b64(object.id())).append('\t').append(object.type()).append('\t').append(b64(object.name())).append('\t')
					.append(object.visible()).append('\t').append(object.locked()).append('\t').append(object.drawOrder()).append('\t')
					.append(b64(object.parentId())).append('\t').append(points).append('\t')
					.append(object.strokeArgb()).append('\t').append(object.fillArgb()).append('\t').append(object.widthBlocks()).append('\t')
					.append(b64(object.scenarioId())).append('\t').append(object.implemented()).append('\t').append(b64(elevation.toString()))
                    .append('\t').append(b64(object.guideData()));
		}
		var library=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);
		for(var group:library.groups()) out.append('\n').append("G\t").append(b64(group.id())).append('\t').append(b64(group.name())).append('\t').append(b64(group.parentId())).append('\t').append(group.visible()).append('\t').append(group.locked()).append('\t').append(group.opacity()).append('\t').append(group.category()).append('\t').append(group.drawOrder());
		for(var preset:library.presets()) out.append('\n').append("E\t").append(b64(preset.id())).append('\t').append(b64(preset.name())).append('\t').append(b64(String.join(",",preset.visibleGroups()))).append('\t').append(b64(String.join(",",preset.hiddenGroups()))).append('\t').append(b64(String.join(",",preset.visibleReferences()))).append('\t').append(b64(String.join(",",preset.hiddenReferences()))).append('\t').append(b64(String.join(",",preset.visibleReferenceSets()))).append('\t').append(b64(String.join(",",preset.hiddenReferenceSets())));
		for(var set:library.referenceSets()) out.append('\n').append("Q\t").append(b64(set.id())).append('\t').append(b64(set.name())).append('\t').append(b64(String.join(",",set.referenceIds()))).append('\t').append(set.visible()).append('\t').append(set.locked()).append('\t').append(set.drawOrder()).append('\t').append(set.opacity());
		for(var bookmark:library.bookmarks()) out.append('\n').append("K\t").append(b64(bookmark.id())).append('\t').append(b64(bookmark.name())).append('\t').append(bookmark.centerX()).append('\t').append(bookmark.centerZ()).append('\t').append(bookmark.zoom()).append('\t').append(b64(bookmark.selectedObjectId())).append('\t').append(b64(bookmark.presetId()));
		for(var viewpoint:library.viewpoints()) out.append('\n').append("Y\t").append(b64(viewpoint.id())).append('\t').append(b64(viewpoint.name())).append('\t').append(viewpoint.x()).append('\t').append(viewpoint.y()).append('\t').append(viewpoint.z()).append('\t').append(viewpoint.yaw()).append('\t').append(viewpoint.pitch()).append('\t').append(viewpoint.projection()).append('\t').append(viewpoint.depth()).append('\t').append(viewpoint.opacity());
		for(var asset:library.terrainAssets()) {
			out.append('\n').append("A\t").append(b64(asset.id())).append('\t').append(b64(asset.name())).append('\t').append(asset.minX()).append('\t').append(asset.minZ()).append('\t').append(asset.maxX()).append('\t').append(asset.maxZ()).append('\t').append(asset.seaLevel()).append('\t').append(asset.status()).append('\t').append(asset.revisions().size()).append('\t').append(b64(asset.approvedGaeaRevision())).append('\t').append(b64(asset.approvedWorldPainterRevision())).append('\t').append(b64(asset.placementData())).append('\t').append(b64(asset.notes())).append('\t').append(b64(asset.revisions().stream().map(r->r.id()+":"+r.stage()).collect(java.util.stream.Collectors.joining(",")))).append('\t').append(b64(String.join(",",asset.planningObjectIds())));
			for(var r:asset.revisions()) out.append('\n').append("T\t").append(b64(asset.id())).append('\t').append(b64(r.id())).append('\t').append(r.stage()).append('\t').append(b64(r.fileName())).append('\t').append(b64(r.fileHash())).append('\t').append(r.minX()).append('\t').append(r.minZ()).append('\t').append(r.maxX()).append('\t').append(r.maxZ()).append('\t').append(r.widthPx()).append('\t').append(r.heightPx()).append('\t').append(r.blocksPerPixel()).append('\t').append(r.seaLevel()).append('\t').append(b64(r.orientation())).append('\t').append(r.createdAt()).append('\t').append(b64(r.notes()));
		}
		var p4=net.oceancanvas.mod.project.OceanCanvasP4PlanningData.get(world);
		for(var a:p4.artifacts()){String pts=a.points().stream().map(q->q.x()+","+q.z()).collect(java.util.stream.Collectors.joining(";"));out.append('\n').append("4A\t").append(b64(a.id())).append('\t').append(a.kind()).append('\t').append(b64(a.name())).append('\t').append(b64(a.targetId())).append('\t').append(b64(pts)).append('\t').append(a.valueA()).append('\t').append(a.valueB()).append('\t').append(b64(a.text())).append('\t').append(a.createdAt());}
		for(var f:net.oceancanvas.mod.planning.OceanCanvasP4PlanningService.analyze(world)) out.append('\n').append("4F\t").append(f.feature()).append('\t').append(b64(f.targetId())).append('\t').append(f.severity()).append('\t').append(f.x()).append('\t').append(f.z()).append('\t').append(b64(f.message()));
		var p5=net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world);
		for(var t:p5.transforms()) out.append('\n').append("5T\t").append(b64(t.id())).append('\t').append(b64(t.name())).append('\t').append(t.sourceTool()).append('\t').append(t.targetTool()).append('\t').append(t.originX()).append('\t').append(t.originZ()).append('\t').append(t.scale()).append('\t').append(t.rotationDegrees()).append('\t').append(t.flipX()).append('\t').append(t.flipZ()).append('\t').append(t.seaLevel()).append('\t').append(t.floorY()).append('\t').append(b64(t.originConvention())).append('\t').append(t.updatedAt());
		for(var r:p5.recipes()) out.append('\n').append("5R\t").append(b64(r.id())).append('\t').append(b64(r.name())).append('\t').append(r.targetTool()).append('\t').append(b64(r.transformProfileId())).append('\t').append(r.boundsSource()).append('\t').append(r.widthPx()).append('\t').append(r.heightPx()).append('\t').append(b64(String.join(",",r.layerIds()))).append('\t').append(b64(r.namingRule())).append('\t').append(b64(String.join(",",r.validationSteps()))).append('\t').append(r.updatedAt());
		for(var p:p5.placements()) out.append('\n').append("5L\t").append(b64(p.id())).append('\t').append(b64(p.name())).append('\t').append(b64(p.projectId())).append('\t').append(b64(p.terrainAssetId())).append('\t').append(b64(p.fileName())).append('\t').append(b64(p.fileHash())).append('\t').append(p.originX()).append('\t').append(p.originY()).append('\t').append(p.originZ()).append('\t').append(p.rotationDegrees()).append('\t').append(p.mirror()).append('\t').append(p.status()).append('\t').append(b64(p.version())).append('\t').append(b64(String.join(",",p.dependencies()))).append('\t').append(b64(p.notes())).append('\t').append(p.updatedAt());
		for(var q:p5.quarantine()) out.append('\n').append("5Q\t").append(b64(q.id())).append('\t').append(b64(q.fileName())).append('\t').append(b64(q.fileHash())).append('\t').append(q.targetType()).append('\t').append(b64(q.targetId())).append('\t').append(q.minX()).append('\t').append(q.minZ()).append('\t').append(q.maxX()).append('\t').append(q.maxZ()).append('\t').append(q.widthPx()).append('\t').append(q.heightPx()).append('\t').append(q.blocksPerPixel()).append('\t').append(q.seaLevel()).append('\t').append(b64(q.orientation())).append('\t').append(q.status()).append('\t').append(b64(q.issues())).append('\t').append(q.createdAt());
		for(var m:p5.markerSchemas()) out.append('\n').append("5M\t").append(b64(m.id())).append('\t').append(b64(m.name())).append('\t').append(m.scope()).append('\t').append(b64(String.join(",",m.fields()))).append('\t').append(b64(m.icon())).append('\t').append(m.style()).append('\t').append(b64(m.notes())).append('\t').append(m.updatedAt());
		for(var f:net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.analyze(world)) out.append('\n').append("5F\t").append(f.feature()).append('\t').append(b64(f.targetId())).append('\t').append(f.severity()).append('\t').append(b64(f.message()));
		for(var c:net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.formatCapabilities()) out.append('\n').append("5C\t").append(c.target()).append('\t').append(c.elevation()).append('\t').append(c.vectors()).append('\t').append(c.semantics()).append('\t').append(c.groups()).append('\t').append(c.anchors()).append('\t').append(c.notes()).append('\t').append(c.coordinateMetadata()).append('\t').append(c.fingerprint());
		for(var c:net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.pluginCapabilities()) out.append('\n').append("5P\t").append(c.modId()).append('\t').append(c.present()).append('\t').append(b64(c.version())).append('\t').append(b64(String.join(",",c.capabilities()))).append('\t').append(c.mode());
        var program=net.oceancanvas.mod.project.OceanCanvasProgramData.get(world);
        for(var c:net.oceancanvas.mod.project.OceanCanvasProgramService.features()) out.append('\n').append("PX\t").append(c.phase()).append('\t').append(c.id()).append('\t').append(b64(c.name()));
        for(var e:program.entries()) out.append('\n').append("PE\t").append(b64(e.id())).append('\t').append(e.phase()).append('\t').append(e.featureId()).append('\t').append(e.kind()).append('\t').append(b64(e.subjectId())).append('\t').append(b64(e.label())).append('\t').append(e.x()).append('\t').append(e.z()).append('\t').append(e.state()).append('\t').append(b64(e.payload())).append('\t').append(b64(e.author())).append('\t').append(e.createdAt()).append('\t').append(e.updatedAt());
        for(var e:program.evidence()) out.append('\n').append("PV\t").append(b64(e.id())).append('\t').append(e.phase()).append('\t').append(e.featureId()).append('\t').append(b64(e.subjectId())).append('\t').append(e.state()).append('\t').append(e.severity()).append('\t').append(b64(e.summary())).append('\t').append(e.x()).append('\t').append(e.z()).append('\t').append(e.observedAt());
        for(var p:server.getPlayerList().getPlayers()) out.append('\n').append("PP\t").append(p.getUUID()).append('\t').append(b64(p.getGameProfile().name())).append('\t').append(b64(String.valueOf(p.level().dimension()))).append('\t').append(p.blockPosition().getX()).append('\t').append(p.blockPosition().getY()).append('\t').append(p.blockPosition().getZ());
        for(var c:net.oceancanvas.mod.project.OceanCanvasCollaborationPresence.snapshots()) out.append('\n').append("PC\t").append(c.uuid()).append('\t').append(b64(c.name())).append('\t').append(b64(c.dimension())).append('\t').append(c.x()).append('\t').append(c.z()).append('\t').append(b64(c.subjectId())).append('\t').append(c.updatedAt());
        if(net.oceancanvas.mod.project.OceanCanvasReadOnlyApi.running()) net.oceancanvas.mod.project.OceanCanvasReadOnlyApi.publish(world);
		return new OceanCanvasPlanningSyncPayload(out.toString());
	}

    private static void validateWorldObjectRef(ServerLevel world,net.oceancanvas.mod.project.OceanCanvasWorkspaceData data,String type,String id){
        String t=type==null?"PROJECT":type.trim().toUpperCase(java.util.Locale.ROOT);
        switch(t){
            case "WORLD","FEATURE","WORK_AREA" -> {if(id==null||id.isBlank())throw new IllegalArgumentException("World Object ID required");}
            case "REGION" -> {if(OceanCanvasPlayerZones.get(world).zoneByName(id)==null)throw new IllegalArgumentException("unknown Region World Object");}
            case "PROJECT" -> {if(data.project(id)==null)throw new IllegalArgumentException("unknown Project World Object");}
            case "PLAN" -> {if(net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(id)==null)throw new IllegalArgumentException("unknown Plan World Object");}
            case "TERRAIN_ASSET" -> {if(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(id)==null)throw new IllegalArgumentException("unknown Terrain Asset World Object");}
            default -> throw new IllegalArgumentException("unsupported World Object type");
        }
    }

	private static OceanCanvasWorkspaceSyncPayload buildWorkspacePayload(MinecraftServer server) {
		ServerLevel world = server.overworld();
		if (world == null) return new OceanCanvasWorkspaceSyncPayload("");
		var data = net.oceancanvas.mod.project.OceanCanvasWorkspaceData.get(world);
		StringBuilder out = new StringBuilder();
		out.append("A\t").append(b64(data.activeScenario())).append('\t').append(b64(data.sessionNote()));
		for (var task : data.tasks()) {
			String deps=String.join(",",task.dependencies());
			String checks=task.checklist().stream().map(c->b64(c.id())+","+c.complete()+","+b64(c.text())).collect(java.util.stream.Collectors.joining(";"));
			out.append('\n').append("T\t").append(b64(task.id())).append('\t').append(b64(task.title())).append('\t')
					.append(task.x()).append('\t').append(task.z()).append('\t').append(task.status()).append('\t').append(task.priority()).append('\t')
					.append(b64(task.regionName())).append('\t').append(b64(task.planningObjectId())).append('\t').append(b64(task.notes())).append('\t')
					.append(b64(task.projectId())).append('\t').append(b64(task.parentTaskId())).append('\t').append(b64(deps)).append('\t').append(b64(checks)).append('\t').append(task.weight()).append('\t').append(b64(task.phaseId()));
		}
		out.append('\n').append("W\t").append(b64(data.activeWorkProject()));
		out.append('\n').append("M\t").append(data.worldMapMode());
		for(var item:data.nextSession())out.append('\n').append("N\t").append(b64(item.id())).append('\t').append(item.kind()).append('\t').append(b64(item.taskId())).append('\t').append(b64(item.projectId())).append('\t').append(b64(item.label())).append('\t').append(item.x()).append('\t').append(item.z()).append('\t').append(b64(item.regionName())).append('\t').append(item.createdAt());
		for(var project:data.projects()) {
            String phases=project.phases().stream().map(v->v.id()+","+b64(v.name())+","+v.status()+","+v.order()).collect(java.util.stream.Collectors.joining(";"));
            String blockers=project.blockers().stream().map(v->v.id()+","+v.resolved()+","+b64(v.text())+","+v.scopeType()+","+b64(v.scopeId())).collect(java.util.stream.Collectors.joining(";"));
            String milestoneRecords=project.milestoneRecords().stream().map(v->v.id()+","+b64(v.name())+","+v.status()+","+b64(v.phaseId())+","+b64(v.taskId())+","+b64(v.notes())+","+v.progress()+","+b64(v.targetDate())).collect(java.util.stream.Collectors.joining(";"));
            out.append('\n').append("P\t").append(b64(project.id())).append('\t').append(b64(project.name())).append('\t')
				.append(project.status()).append('\t').append(b64(project.regionName())).append('\t').append(b64(project.templateId())).append('\t').append(b64(project.notes())).append('\t').append(data.projectProgress(project.id())).append('\t')
                .append(b64(project.parentProjectId())).append('\t').append(b64(String.join(",",project.planningObjectIds()))).append('\t').append(b64(String.join(",",project.terrainAssetIds()))).append('\t').append(b64(String.join(" | ",project.milestones())))
                .append('\t').append(b64(phases)).append('\t').append(b64(blockers)).append('\t').append(b64(project.currentTaskId())).append('\t').append(b64(milestoneRecords));
        }
		var forever=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world);
		for(var plot:forever.prototypes())out.append('\n').append(forever.clientPrototype(plot));
		for(var transition:forever.transitions())out.append('\n').append(forever.clientTransition(transition));
		for(var project:data.projects())for(var snap:net.oceancanvas.mod.project.OceanCanvasPipelineSnapshotData.get(world).recent(project.id()))out.append('\n').append("R\t").append(b64(snap.id())).append('\t').append(b64(snap.projectId())).append('\t').append(snap.epochMillis()).append('\t').append(b64(snap.label())).append('\t').append(snap.plans()).append('\t').append(snap.implemented()).append('\t').append(snap.assets()).append('\t').append(snap.revisions()).append('\t').append(snap.gaea()).append('\t').append(snap.worldPainter()).append('\t').append(snap.placement()).append('\t').append(snap.tasks()).append('\t').append(snap.complete()).append('\t').append(snap.ready()).append('\t').append(snap.phasesComplete()).append('\t').append(snap.milestonesComplete()).append('\t').append(snap.blockers());
		var atlas=net.oceancanvas.mod.project.OceanCanvasAtlasRouteData.get(world);for(var route:atlas.routes())out.append('\n').append("Y\t").append(b64(route.id())).append('\t').append(b64(route.name())).append('\t').append(b64(route.description())).append('\t').append(b64(atlas.clientStops(route)));
		for (var scenario : data.scenarios()) out.append('\n').append("S\t").append(b64(scenario.id())).append('\t').append(b64(scenario.name())).append('\t').append(scenario.visible());
		for (var feature : data.atlasFeatures()) out.append('\n').append("F\t").append(b64(feature.id())).append('\t').append(feature.type()).append('\t').append(b64(feature.name())).append('\t').append(feature.x()).append('\t').append(feature.z()).append('\t').append(b64(feature.regionName())).append('\t').append(feature.color());
        for(var policy:forever.worldPolicies()) out.append('\n').append(forever.clientWorldPolicy(policy));
        for(var intent:forever.intentResolutions()) out.append('\n').append(forever.clientIntentResolution(intent));
        for(var authorship:forever.authorshipProvenance()) out.append('\n').append(forever.clientAuthorshipProvenance(authorship));
        for(var claim:forever.worldClaims()) out.append('\n').append(forever.clientWorldClaim(claim));
        for(var relationship:forever.worldRelationships()) out.append('\n').append(forever.clientWorldRelationship(relationship));
        for(var observation:forever.worldObservations()) out.append('\n').append(forever.clientWorldObservation(observation));
        for(var event:forever.worldEvents()) out.append('\n').append(forever.clientWorldEvent(event));
        for(var candidate:forever.candidateEdits()) out.append('\n').append(forever.clientCandidateEdit(candidate)); 
        for(var revision:forever.designRevisions()) out.append('\n').append(forever.clientDesignRevision(revision));
		return new OceanCanvasWorkspaceSyncPayload(out.toString());
	}

	private static void handlePlanningEdit(OceanCanvasPlanningEditRequestPayload payload, ServerPlayer player) {
		if (!hasPlanningEditPermission(player)) { tell(player, "You do not have permission to edit planning data.", true); return; }
		ServerLevel world = player.level();
		var data = net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world);
		String action = payload.action() == null ? "" : payload.action().trim().toLowerCase(java.util.Locale.ROOT);
		String id = payload.id() == null ? "" : payload.id().trim();
		if (action.equals("history_undo")) {
			if (net.oceancanvas.mod.planning.OceanCanvasPlanningHistory.undo(player, data)) { tell(player, "Undid Plan edit."); broadcastToAll(world.getServer()); }
			else tell(player, "Nothing to undo in this Plan editing session.", true);
			return;
		}
		if (action.equals("history_redo")) {
			if (net.oceancanvas.mod.planning.OceanCanvasPlanningHistory.redo(player, data)) { tell(player, "Redid Plan edit."); broadcastToAll(world.getServer()); }
			else tell(player, "Nothing to redo in this Plan editing session.", true);
			return;
		}
        if (action.equals("program_cursor")) {
            String raw=payload.arg1()==null?"":payload.arg1().trim();
            if(raw.equalsIgnoreCase("OFF")){net.oceancanvas.mod.project.OceanCanvasCollaborationPresence.remove(player.getUUID());}
            else {String[] f=raw.split(",",-1);if(f.length!=2){tell(player,"Cursor presence needs x,z.",true);return;}try{net.oceancanvas.mod.project.OceanCanvasCollaborationPresence.update(player,Integer.parseInt(f[0]),Integer.parseInt(f[1]),payload.arg2());}catch(NumberFormatException ex){tell(player,"Cursor presence coordinates are invalid.",true);return;}}
            broadcastToAll(world.getServer());return;
        }
		var planningObjectsBefore = data.objects();
        if(net.oceancanvas.mod.project.OceanCanvasProgramService.blocksPlanMutation(action)){
            var frozenTargets=new java.util.LinkedHashSet<String>();
            if(!id.isBlank()&&data.object(id)!=null)frozenTargets.add(id);
            if(action.startsWith("batch_")||action.equals("join_paths"))frozenTargets.addAll(parsePlanningIds(payload.arg1()));
            for(String oid:frozenTargets)if(net.oceancanvas.mod.project.OceanCanvasProgramService.isFrozen(world,oid)){
                var frozen=data.object(oid);tell(player,"Plan '"+(frozen==null?oid:frozen.name())+"' is design-frozen. Open Workbench → LAB and run Design freeze / release with RELEASE before editing it.",true);return;
            }
        }
		try {
			switch (action) {
				case "reference_delete" -> {
					var ref=data.referenceLayer(id); if(ref==null){tell(player,"Reference layer no longer exists.",true);return;}
					if(ref.locked()){tell(player,"Unlock the reference layer before deleting it.",true);return;}
					data.removeReferenceLayer(id); tell(player,"Deleted reference layer '"+ref.name()+"'.");
				}
				case "reference_rename" -> {
					var ref=data.referenceLayer(id); if(ref==null)return; if(ref.locked()){tell(player,"Unlock the reference layer before renaming it.",true);return;}
					String name=payload.arg1()==null?"":payload.arg1().trim(); if(name.isBlank()||name.length()>64){tell(player,"Reference names must be 1-64 characters.",true);return;}
					data.putReferenceLayer(copyReferenceLayer(ref,name,ref.visible(),ref.opacity(),ref.locked(),ref.minX(),ref.minZ(),ref.maxX(),ref.maxZ(),ref.rotationDegrees()));
				}
				case "reference_visible", "reference_lock" -> {
					var ref=data.referenceLayer(id); if(ref==null)return; boolean value=Boolean.parseBoolean(payload.arg1());
					data.putReferenceLayer(copyReferenceLayer(ref,ref.name(),action.equals("reference_visible")?value:ref.visible(),ref.opacity(),action.equals("reference_lock")?value:ref.locked(),ref.minX(),ref.minZ(),ref.maxX(),ref.maxZ(),ref.rotationDegrees()));
				}
				case "reference_opacity" -> {
					var ref=data.referenceLayer(id); if(ref==null)return; if(ref.locked()){tell(player,"Unlock the reference layer before changing opacity.",true);return;}
					double value=Math.max(0.05D,Math.min(1.0D,Double.parseDouble(payload.arg1())));
					data.putReferenceLayer(copyReferenceLayer(ref,ref.name(),ref.visible(),value,ref.locked(),ref.minX(),ref.minZ(),ref.maxX(),ref.maxZ(),ref.rotationDegrees()));
				}
				case "reference_move" -> {
					var refs=new java.util.ArrayList<>(data.referenceLayers()); refs.sort(java.util.Comparator.comparingInt(net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer::drawOrder).thenComparing(net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer::id));
					int at=-1;for(int i=0;i<refs.size();i++)if(refs.get(i).id().equals(id)){at=i;break;} if(at<0)return; int to=payload.arg1().equalsIgnoreCase("up")?at+1:at-1; if(to<0||to>=refs.size())return;
					java.util.Collections.swap(refs,at,to); for(int i=0;i<refs.size();i++){var r=refs.get(i);data.putReferenceLayer(new net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer(r.id(),r.name(),r.assetId(),r.visible(),r.opacity(),r.locked(),r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.rotationDegrees(),i,r.registrationPoints(),r.notes()));}
				}
				case "reference_rotate" -> {
					var ref=data.referenceLayer(id); if(ref==null)return; if(ref.locked()){tell(player,"Unlock the reference layer before rotating it.",true);return;}
					double value=Double.parseDouble(payload.arg1()); while(value>180)value-=360; while(value<-180)value+=360;
					data.putReferenceLayer(copyReferenceLayer(ref,ref.name(),ref.visible(),ref.opacity(),ref.locked(),ref.minX(),ref.minZ(),ref.maxX(),ref.maxZ(),value));
				}
				case "reference_asset_replace" -> {
					var ref=data.referenceLayer(id); if(ref==null)return;
					if(ref.locked()){tell(player,"Unlock the reference layer before replacing its asset.",true);return;}
					String newAsset=payload.arg1()==null?"":payload.arg1().trim();
					if(newAsset.isBlank() || !newAsset.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("invalid reference asset id");
					data.putReferenceLayer(new net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer(ref.id(),ref.name(),newAsset,ref.visible(),ref.opacity(),ref.locked(),ref.minX(),ref.minZ(),ref.maxX(),ref.maxZ(),ref.rotationDegrees(),ref.drawOrder(),ref.registrationPoints(),ref.notes()));
					tell(player,"Replaced reference asset for '"+ref.name()+"'.");
				}
				case "reference_transform" -> {
					var ref=data.referenceLayer(id); if(ref==null)return; if(ref.locked()){tell(player,"Unlock the reference layer before transforming it.",true);return;}
					String[] b=payload.arg1().split(",",-1); if(b.length!=4) throw new IllegalArgumentException("transform requires minX,minZ,maxX,maxZ");
					int minX=Integer.parseInt(b[0]), minZ=Integer.parseInt(b[1]), maxX=Integer.parseInt(b[2]), maxZ=Integer.parseInt(b[3]);
					if(minX==maxX||minZ==maxZ) throw new IllegalArgumentException("reference bounds must have non-zero width and height");
					double rotation=Double.parseDouble(payload.arg2()); while(rotation>180)rotation-=360; while(rotation<-180)rotation+=360;
					data.putReferenceLayer(copyReferenceLayer(ref,ref.name(),ref.visible(),ref.opacity(),ref.locked(),minX,minZ,maxX,maxZ,rotation));
					tell(player,"Updated reference transform for '"+ref.name()+"'.");
				}
				case "reference_registration" -> {
					var ref=data.referenceLayer(id); if(ref==null)return; if(ref.locked()){tell(player,"Unlock the reference layer before registering it.",true);return;}
					java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.RegistrationPoint> points=new java.util.ArrayList<>();
					if(payload.arg1()!=null&&!payload.arg1().isBlank()) for(String raw:payload.arg1().split(";")){
						String[] f=raw.split(",",-1); if(f.length!=4)continue;
						points.add(new net.oceancanvas.mod.project.OceanCanvasPlanningData.RegistrationPoint(Double.parseDouble(f[0]),Double.parseDouble(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3])));
						if(points.size()>=3)break;
					}
					if(points.size()<2)throw new IllegalArgumentException("registration requires at least two point pairs");
					String[] tf=payload.arg2().split(",",-1); if(tf.length!=5)throw new IllegalArgumentException("registration transform is invalid");
					int minX=Integer.parseInt(tf[0]),minZ=Integer.parseInt(tf[1]),maxX=Integer.parseInt(tf[2]),maxZ=Integer.parseInt(tf[3]); double rotation=Double.parseDouble(tf[4]);
					data.putReferenceLayer(new net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer(ref.id(),ref.name(),ref.assetId(),ref.visible(),ref.opacity(),ref.locked(),minX,minZ,maxX,maxZ,rotation,ref.drawOrder(),points,ref.notes()));
					tell(player,"Registered reference '"+ref.name()+"' using "+points.size()+" control points.");
				}
				case "create_reference" -> {
					String[] f = payload.arg2().split("\t", -1);
					if (f.length < 5) throw new IllegalArgumentException("reference requires name and world bounds");
					String newId=data.newId("reference"); String name=f[0].isBlank()?"Reference Image":f[0];
					int minX=Integer.parseInt(f[1]), minZ=Integer.parseInt(f[2]), maxX=Integer.parseInt(f[3]), maxZ=Integer.parseInt(f[4]);
					data.putReferenceLayer(new net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer(
							newId,name,payload.arg1(),true,0.6D,true,minX,minZ,maxX,maxZ,0.0D,data.referenceLayers().size(),java.util.List.of(),""));
					tell(player,"Imported reference layer metadata '"+name+"'. The image asset remains non-critical planning data.");
				}
				case "create", "create_smooth" -> {
					var type = net.oceancanvas.mod.project.OceanCanvasPlanningData.ObjectType.parse(payload.arg1());
					java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> pts = parsePlanningPoints(payload.arg2());
					int minPoints = switch (type) {
						case LANDMARK, TEXT, CITY, PEAK -> 1;
						case RIVER, ROAD, PATH, BRIDGE, TRANSPORT_ROUTE, COASTLINE, MOUNTAIN_RANGE, RIDGELINE, VALLEY, CLIFF, TERRAIN_PROFILE, BORDER, FREEFORM_LINE -> 2;
						default -> 3;
					};
					if (pts.size() < minPoints) { tell(player, "That " + type.name().toLowerCase(java.util.Locale.ROOT).replace('_',' ') + " needs at least " + minPoints + " point(s).", true); return; }
					String newId = data.newId(type.name().toLowerCase(java.util.Locale.ROOT));
					String name = prettyPlanningName(type);
					String initialGuide=action.equals("create_smooth")?"SMOOTH":"";
					data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
							newId, type.name(), name, pts, true, false, data.objects().size(), "", "", initialGuide,
							0xD055FFFF, 0x2055FFFF, 0.0D, java.util.List.of(), "", false));
					tell(player, "Created planning object '" + name + "'.");
				}
				case "delete" -> {
					var obj = data.object(id); if (obj == null) { tell(player, "Planning object no longer exists.", true); return; }
					if (obj.locked()) { tell(player, "Unlock the planning object before deleting it.", true); return; }
					data.removeObject(id); tell(player, "Deleted planning object '" + obj.name() + "'.");
				}
				case "batch_delete" -> {
					var ids=parsePlanningIds(payload.arg1());if(ids.isEmpty())return;var objs=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject>();
					for(String oid:ids){var o=data.object(oid);if(o==null)throw new IllegalArgumentException("a selected Plan object no longer exists");if(o.locked())throw new IllegalArgumentException("unlock '"+o.name()+"' before batch delete");objs.add(o);}
					for(var o:objs)data.removeObject(o.id());tell(player,"Deleted "+objs.size()+" selected Plan object"+(objs.size()==1?"":"s")+".");
				}
				case "batch_duplicate" -> {
					var ids=parsePlanningIds(payload.arg1());if(ids.isEmpty())return;int[] d=parsePlanningDelta(payload.arg2());int count=0;
					for(String oid:ids){var o=data.object(oid);if(o==null)continue;var pts=translatePlanningPoints(o.points(),d[0],d[1]);String nid=data.newId(net.oceancanvas.mod.project.OceanCanvasPlanningData.ObjectType.parse(o.type()).name().toLowerCase(java.util.Locale.ROOT));String guide=withoutGuidePrefix(withoutGuidePrefix(o.guideData(),"LINK_START="),"LINK_END=");data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(nid,o.type(),copyPlanningName(o.name()),pts,o.visible(),false,data.objects().size(),o.parentId(),o.notes(),guide,o.strokeArgb(),o.fillArgb(),o.widthBlocks(),o.elevationProfile(),o.scenarioId(),o.implemented()));count++;}
					tell(player,"Duplicated "+count+" Plan object"+(count==1?"":"s")+".");
				}
				case "batch_move" -> {
					var ids=parsePlanningIds(payload.arg1());if(ids.isEmpty())return;int[] d=parsePlanningDelta(payload.arg2());var selected=new java.util.LinkedHashSet<>(ids);var objs=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject>();
					for(String oid:ids){var o=data.object(oid);if(o==null)throw new IllegalArgumentException("a selected Plan object no longer exists");if(o.locked())throw new IllegalArgumentException("unlock '"+o.name()+"' before moving the selection");validateBatchMoveLinks(data,o,selected);objs.add(o);}
					for(var o:objs)data.putObject(copyPlanningPoints(o,translatePlanningPoints(o.points(),d[0],d[1])));tell(player,"Moved "+objs.size()+" selected Plan object"+(objs.size()==1?"":"s")+" by "+d[0]+", "+d[1]+" blocks.");
				}
				case "batch_scale", "batch_rotate" -> {
					var ids=parsePlanningIds(payload.arg1());if(ids.isEmpty())return;double amount=Double.parseDouble(payload.arg2());var selected=new java.util.LinkedHashSet<>(ids);var objs=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject>();
					for(String oid:ids){var o=data.object(oid);if(o==null)throw new IllegalArgumentException("a selected Plan object no longer exists");if(o.locked())throw new IllegalArgumentException("unlock '"+o.name()+"' before transforming the selection");validateBatchMoveLinks(data,o,selected);objs.add(o);}double minX=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;for(var o:objs)for(var q:o.points()){minX=Math.min(minX,q.x());minZ=Math.min(minZ,q.z());maxX=Math.max(maxX,q.x());maxZ=Math.max(maxZ,q.z());}double cx=(minX+maxX)/2.0D,cz=(minZ+maxZ)/2.0D;
					for(var o:objs){java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> pts=action.equals("batch_scale")?net.oceancanvas.mod.planning.OceanCanvasGeometry.scale(o.points(),Math.max(0.01D,Math.min(100D,amount)),Math.max(0.01D,Math.min(100D,amount)),cx,cz):net.oceancanvas.mod.planning.OceanCanvasGeometry.rotate(o.points(),Math.max(-3600D,Math.min(3600D,amount)),cx,cz);data.putObject(copyPlanningPoints(o,pts));}tell(player,(action.equals("batch_scale")?"Scaled ":"Rotated ")+objs.size()+" selected Plan object"+(objs.size()==1?"":"s")+" around their combined center.");
				}
				case "cut_path" -> {
					var o=data.object(id);if(o==null)return;if(o.locked())throw new IllegalArgumentException("unlock the Plan object before cutting it");if(planningTypeClosed(o.type()))throw new IllegalArgumentException("Cut Path currently supports open paths only");int seg=Integer.parseInt(payload.arg1());String[] xy=payload.arg2().split(",",-1);if(xy.length!=2)throw new IllegalArgumentException("invalid cut point");var cut=new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point(Integer.parseInt(xy[0]),Integer.parseInt(xy[1]));var pts=o.points();if(seg<0||seg>=pts.size()-1)throw new IllegalArgumentException("cut segment is out of range");
					var a=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point>(pts.subList(0,seg+1));var b=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point>(pts.subList(seg+1,pts.size()));if(a.isEmpty()||!samePlanningPoint(a.get(a.size()-1),cut))a.add(cut);if(b.isEmpty()||!samePlanningPoint(b.get(0),cut))b.add(0,cut);if(a.size()<2||b.size()<2)throw new IllegalArgumentException("cut must leave at least two points on each path");String guide=clearPlanningTopologyGuide(o.guideData());data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(o.id(),o.type(),o.name(),java.util.List.copyOf(a),o.visible(),o.locked(),o.drawOrder(),o.parentId(),o.notes(),guide,o.strokeArgb(),o.fillArgb(),o.widthBlocks(),o.elevationProfile(),o.scenarioId(),o.implemented()));String nid=data.newId(net.oceancanvas.mod.project.OceanCanvasPlanningData.ObjectType.parse(o.type()).name().toLowerCase(java.util.Locale.ROOT));data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(nid,o.type(),copyPlanningName(o.name()),java.util.List.copyOf(b),o.visible(),false,data.objects().size(),o.parentId(),o.notes(),guide,o.strokeArgb(),o.fillArgb(),o.widthBlocks(),o.elevationProfile(),o.scenarioId(),o.implemented()));tell(player,"Split '"+o.name()+"' into two paths.");
				}
				case "join_paths" -> {
					var ids=parsePlanningIds(payload.arg1());if(ids.size()!=2)throw new IllegalArgumentException("Join Paths requires exactly two selected paths");var a=data.object(ids.get(0));var b=data.object(ids.get(1));if(a==null||b==null)throw new IllegalArgumentException("a selected Plan path no longer exists");if(a.locked()||b.locked())throw new IllegalArgumentException("unlock both paths before joining");if(planningTypeClosed(a.type())||planningTypeClosed(b.type()))throw new IllegalArgumentException("Join Paths currently supports open paths only");if(a.points().size()<2||b.points().size()<2)throw new IllegalArgumentException("both paths need at least two points");var joined=joinPlanningPoints(a.points(),b.points());String guide=clearPlanningTopologyGuide(a.guideData());data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(a.id(),a.type(),a.name(),joined,a.visible(),a.locked(),Math.min(a.drawOrder(),b.drawOrder()),a.parentId(),a.notes(),guide,a.strokeArgb(),a.fillArgb(),a.widthBlocks(),a.elevationProfile(),a.scenarioId(),a.implemented()));data.removeObject(b.id());tell(player,"Joined '"+a.name()+"' and '"+b.name()+"' into one path.");
				}
				case "rename" -> {
					var obj = data.object(id); if (obj == null) { tell(player, "Planning object no longer exists.", true); return; }
					if (obj.locked()) { tell(player, "Unlock the planning object before renaming it.", true); return; }
					String name = payload.arg1() == null ? "" : payload.arg1().trim();
					if (name.isBlank() || name.length() > 64) { tell(player, "Planning names must be 1-64 characters.", true); return; }
					data.putObject(copyPlanningObject(obj, name, obj.visible(), obj.locked(), obj.drawOrder()));
				}
				case "group" -> {
					var obj=data.object(id);if(obj==null)return;if(obj.locked()){tell(player,"Unlock the planning object before changing its group.",true);return;}
					String groupId=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);
					if(!groupId.isBlank()&&net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).group(groupId)==null)throw new IllegalArgumentException("unknown Plan group");
					data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(obj.id(),obj.type(),obj.name(),obj.points(),obj.visible(),obj.locked(),obj.drawOrder(),groupId,obj.notes(),obj.guideData(),obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),obj.elevationProfile(),obj.scenarioId(),obj.implemented()));
				}
				case "visible", "lock" -> {
					var obj = data.object(id); if (obj == null) { tell(player, "Planning object no longer exists.", true); return; }
					boolean value = Boolean.parseBoolean(payload.arg1());
					data.putObject(copyPlanningObject(obj, obj.name(), action.equals("visible") ? value : obj.visible(), action.equals("lock") ? value : obj.locked(), obj.drawOrder()));
				}
				case "order" -> {
					var obj = data.object(id); if (obj == null) return;
					if (obj.locked()) { tell(player, "Unlock the planning object before reordering it.", true); return; }
					int order = Math.max(-10000, Math.min(10000, Integer.parseInt(payload.arg1())));
					data.putObject(copyPlanningObject(obj, obj.name(), obj.visible(), obj.locked(), order));
				}
				case "points" -> {
                    var obj=data.object(id); if(obj==null)return;
                    if(obj.locked()){tell(player,"Unlock the planning object before editing vertices.",true);return;}
                    java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> pts=parsePlanningPoints(payload.arg1());
                    int minPoints=planningMinPoints(obj.type());
                    if(pts.size()<minPoints||pts.size()>512)throw new IllegalArgumentException("invalid planning vertex count");
                    var updated=new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
                            obj.id(),obj.type(),obj.name(),pts,obj.visible(),obj.locked(),obj.drawOrder(),obj.parentId(),obj.notes(),obj.guideData(),
                            obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),obj.elevationProfile(),obj.scenarioId(),obj.implemented());
                    // Shared endpoint nodes are durable Plan relationships. Refuse to tear through a locked peer, then propagate moved endpoints.
                    validateLinkedEndpointMove(data,obj,pts);
                    data.putObject(updated);
                    propagateLinkedEndpoint(data,updated,true);
                    propagateLinkedEndpoint(data,updated,false);
                }
				case "geom_smooth", "geom_jagged", "geom_expand", "geom_erode", "geom_simplify", "geom_resample", "geom_translate", "geom_scale", "geom_rotate" -> {
					var obj=data.object(id); if(obj==null)return;
					if(obj.locked()){tell(player,"Unlock the planning object before sculpting its geometry.",true);return;}
					var pts=obj.points(); if(pts.isEmpty())return;
					double amount=payload.arg1()==null||payload.arg1().isBlank()?1.0D:Double.parseDouble(payload.arg1());
					var b=net.oceancanvas.mod.planning.OceanCanvasGeometry.bounds(pts);
					double cx=(b.minX()+b.maxX())/2.0D,cz=(b.minZ()+b.maxZ())/2.0D;
					java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> next=switch(action){
						case "geom_smooth" -> net.oceancanvas.mod.planning.OceanCanvasGeometry.smooth(pts,planningTypeClosed(obj.type()),Math.max(1,Math.min(3,(int)Math.round(amount))));
						case "geom_jagged" -> net.oceancanvas.mod.planning.OceanCanvasGeometry.roughen(pts,Math.max(0.0D,Math.min(2048.0D,Math.abs(amount))),obj.id().hashCode()*31L+pts.hashCode());
						case "geom_expand" -> net.oceancanvas.mod.planning.OceanCanvasGeometry.radialOffset(pts,Math.max(0.0D,Math.min(4096.0D,Math.abs(amount))));
						case "geom_erode" -> net.oceancanvas.mod.planning.OceanCanvasGeometry.radialOffset(pts,-Math.max(0.0D,Math.min(4096.0D,Math.abs(amount))));
						case "geom_simplify" -> net.oceancanvas.mod.planning.OceanCanvasGeometry.simplify(pts,Math.max(0.0D,Math.min(1024.0D,Math.abs(amount))));
						case "geom_resample" -> net.oceancanvas.mod.planning.OceanCanvasGeometry.resample(pts,Math.max(1.0D,Math.min(4096.0D,Math.abs(amount))));
						case "geom_translate" -> {String[] f=payload.arg2().split(",",-1);if(f.length!=2)throw new IllegalArgumentException("translate requires dx,dz");yield net.oceancanvas.mod.planning.OceanCanvasGeometry.translate(pts,Integer.parseInt(f[0]),Integer.parseInt(f[1]));}
						case "geom_scale" -> net.oceancanvas.mod.planning.OceanCanvasGeometry.scale(pts,Math.max(0.01D,Math.min(100.0D,amount)),Math.max(0.01D,Math.min(100.0D,amount)),cx,cz);
						case "geom_rotate" -> net.oceancanvas.mod.planning.OceanCanvasGeometry.rotate(pts,Math.max(-3600.0D,Math.min(3600.0D,amount)),cx,cz);
						default -> pts;
					};
					if(next.size()<planningMinPoints(obj.type())||next.size()>512)throw new IllegalArgumentException("sculpt result has invalid vertex count: "+next.size());
					data.putObject(copyPlanningPoints(obj,next));
					tell(player,"Applied "+action.substring(5).replace('_',' ')+" to '"+obj.name()+"'.");
				}
				case "smooth" -> {
                    var obj=data.object(id); if(obj==null)return;
                    if(obj.locked()){tell(player,"Unlock the planning object before changing path smoothing.",true);return;}
                    boolean smooth=Boolean.parseBoolean(payload.arg1());
                    String guide=obj.guideData()==null?"":obj.guideData();
                    java.util.List<String> guideTokens=new java.util.ArrayList<>();
                    for(String token:guide.split(";"))if(!token.isBlank()&&!token.equalsIgnoreCase("SMOOTH"))guideTokens.add(token);
                    if(smooth)guideTokens.add("SMOOTH");
                    guide=String.join(";",guideTokens);
                    data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
                            obj.id(),obj.type(),obj.name(),obj.points(),obj.visible(),obj.locked(),obj.drawOrder(),obj.parentId(),obj.notes(),guide,
                            obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),obj.elevationProfile(),obj.scenarioId(),obj.implemented()));
                    tell(player,(smooth?"Enabled":"Disabled")+" smooth path rendering for '"+obj.name()+"'.");
                }
				case "bezier_auto" -> {
                    var obj=data.object(id);if(obj==null)return;if(obj.locked()){tell(player,"Unlock the planning object before editing Bezier handles.",true);return;}
                    if(obj.points().size()<2)return;String guide=withoutGuidePrefix(obj.guideData(),"BEZIER=");String encoded=autoBezierGuide(obj.points());
                    guide=appendGuideToken(guide,"BEZIER="+encoded);guide=withoutGuideToken(guide,"SMOOTH");
                    data.putObject(copyPlanningGuide(obj,guide));tell(player,"Created editable Bezier handles for '"+obj.name()+"'.");
                }
                case "bezier_handles" -> {
                    var obj=data.object(id);if(obj==null)return;if(obj.locked()){tell(player,"Unlock the planning object before editing Bezier handles.",true);return;}
                    String encoded=validateBezierGuide(payload.arg1(),obj.points().size());String guide=appendGuideToken(withoutGuidePrefix(obj.guideData(),"BEZIER="),"BEZIER="+encoded);guide=withoutGuideToken(guide,"SMOOTH");
                    data.putObject(copyPlanningGuide(obj,guide));
                }
                case "bezier_clear" -> {
                    var obj=data.object(id);if(obj==null)return;if(obj.locked()){tell(player,"Unlock the planning object before clearing Bezier handles.",true);return;}
                    data.putObject(copyPlanningGuide(obj,withoutGuidePrefix(obj.guideData(),"BEZIER=")));tell(player,"Cleared Bezier handles for '"+obj.name()+"'.");
                }
                case "link_endpoints" -> {
                    java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();for(String raw:(payload.arg1()==null?"":payload.arg1()).split(",")){String v=raw.trim();if(!v.isBlank())ids.add(v);}
                    if(!id.isBlank())ids.add(id);java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject> objs=new java.util.ArrayList<>();for(String oid:ids){var o=data.object(oid);if(o!=null&&o.points().size()>=2)objs.add(o);}
                    if(objs.size()<2){tell(player,"Select at least two path-like Plan objects before linking endpoints.",true);return;}for(var o:objs)if(o.locked()){tell(player,"Unlock every selected Plan object before linking endpoints.",true);return;}
                    var primary=data.object(id);if(primary==null||primary.points().size()<2)primary=objs.get(0);
                    int primaryEnd=nearestPrimaryEndpoint(primary,objs);var anchor=primaryEnd==0?primary.points().get(0):primary.points().get(primary.points().size()-1);String node="node_"+java.util.UUID.randomUUID().toString().substring(0,8);
                    for(var o:objs){int end=nearestEndpoint(o,anchor.x(),anchor.z());var pts=new java.util.ArrayList<>(o.points());pts.set(end==0?0:pts.size()-1,new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point(anchor.x(),anchor.z()));String prefix=end==0?"LINK_START=":"LINK_END=";String guide=appendGuideToken(withoutGuidePrefix(o.guideData(),prefix),prefix+node);var next=copyPlanningGuide(copyPlanningPoints(o,pts),guide);data.putObject(next);}
                    tell(player,"Linked "+objs.size()+" Plan endpoints at shared node "+node+".");
                }
                case "variable_width" -> {
                    var obj=data.object(id);if(obj==null)return;if(obj.locked())throw new IllegalArgumentException("unlock the Plan object before changing its variable width");double a=Math.max(0D,Math.min(10000D,Double.parseDouble(payload.arg1()))),b=Math.max(0D,Math.min(10000D,Double.parseDouble(payload.arg2())));String guide=appendGuideToken(withoutGuidePrefix(obj.guideData(),"VARWIDTH="),"VARWIDTH="+a+","+b);data.putObject(copyPlanningGuide(obj,guide));tell(player,"Updated variable width for '"+obj.name()+"' from "+(int)Math.round(a)+" to "+(int)Math.round(b)+" blocks.");
                }
                case "push_pull" -> {
                    var obj=data.object(id);if(obj==null)return;if(obj.locked())throw new IllegalArgumentException("unlock the Plan object before Push/Pull sculpting");if(obj.points().size()<2)return;int seg=Integer.parseInt(payload.arg1());String[] f=payload.arg2().split(",",-1);if(f.length!=3)throw new IllegalArgumentException("Push/Pull requires click x,z and amount");double wx=Double.parseDouble(f[0]),wz=Double.parseDouble(f[1]),amount=Math.max(1D,Math.min(4096D,Math.abs(Double.parseDouble(f[2]))));var pts=pushPullPlanningPoints(obj.points(),seg,wx,wz,amount,planningTypeClosed(obj.type()));data.putObject(copyPlanningPoints(obj,pts));tell(player,"Push/Pull sculpted '"+obj.name()+"' by up to "+(int)Math.round(amount)+" blocks.");
                }
				case "width" -> {
					var obj = data.object(id); if (obj == null) return;
					if (obj.locked()) { tell(player, "Unlock the planning object before changing its width.", true); return; }
					double width = Math.max(0.0D, Math.min(10000.0D, Double.parseDouble(payload.arg1())));
					data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
							obj.id(), obj.type(), obj.name(), obj.points(), obj.visible(), obj.locked(), obj.drawOrder(),
							obj.parentId(), obj.notes(), obj.guideData(), obj.strokeArgb(), obj.fillArgb(), width,
							obj.elevationProfile(), obj.scenarioId(), obj.implemented()));
				}
				case "elevation_linear" -> {
					var obj = data.object(id); if (obj == null) return;
					if (obj.locked()) { tell(player, "Unlock the planning object before changing elevation guides.", true); return; }
					int startY=Math.max(-2048,Math.min(4096,Integer.parseInt(payload.arg1())));
					int endY=Math.max(-2048,Math.min(4096,Integer.parseInt(payload.arg2())));
					var profile=java.util.List.of(
							new net.oceancanvas.mod.project.OceanCanvasPlanningData.ElevationPoint(0.0D,startY),
							new net.oceancanvas.mod.project.OceanCanvasPlanningData.ElevationPoint(1.0D,endY));
					data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
							obj.id(),obj.type(),obj.name(),obj.points(),obj.visible(),obj.locked(),obj.drawOrder(),obj.parentId(),obj.notes(),obj.guideData(),
							obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),profile,obj.scenarioId(),obj.implemented()));
					tell(player,"Updated elevation guide for '"+obj.name()+"'.");
				}
				case "elevation_profile" -> {
					var obj=data.object(id); if(obj==null)return; if(obj.locked()){tell(player,"Unlock the planning object before changing elevation guides.",true);return;}
					java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.ElevationPoint> profile=new java.util.ArrayList<>();
					for(String raw:payload.arg1().split(";")){String[] f=raw.split(",",-1);if(f.length!=2)continue;double along=Math.max(0,Math.min(1,Double.parseDouble(f[0])));int y=Math.max(-2048,Math.min(4096,Integer.parseInt(f[1])));profile.add(new net.oceancanvas.mod.project.OceanCanvasPlanningData.ElevationPoint(along,y));}
					profile.sort(java.util.Comparator.comparingDouble(net.oceancanvas.mod.project.OceanCanvasPlanningData.ElevationPoint::along));
				if(profile.size()<2||profile.size()>8)throw new IllegalArgumentException("elevation profile needs 2-8 points");
				data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(obj.id(),obj.type(),obj.name(),obj.points(),obj.visible(),obj.locked(),obj.drawOrder(),obj.parentId(),obj.notes(),obj.guideData(),obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),profile,obj.scenarioId(),obj.implemented()));
					tell(player,"Updated multi-point elevation guide for '"+obj.name()+"'.");
				}
				case "hydrology_meta" -> {
					var obj=data.object(id); if(obj==null)return; if(obj.locked()){tell(player,"Unlock the planning object before changing hydrology metadata.",true);return;}
					String key=payload.arg1()==null?"":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
					if(!java.util.Set.of("HYDRO_ROLE","HYDRO_DOWNSTREAM","HYDRO_CATCHMENT").contains(key))throw new IllegalArgumentException("unknown hydrology metadata");
					String value=payload.arg2()==null?"":payload.arg2().trim();
					if(key.equals("HYDRO_ROLE")&&!value.isBlank()&&!java.util.Set.of("SOURCE","TRIBUTARY","MAINSTEM","OUTLET","INFLOW","OUTFLOW").contains(value.toUpperCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("unknown hydrology role");
					if((key.equals("HYDRO_DOWNSTREAM")||key.equals("HYDRO_CATCHMENT"))&&!value.isBlank()&&data.object(value)==null)throw new IllegalArgumentException("referenced Plan object does not exist");
					if(key.equals("HYDRO_CATCHMENT")&&!value.isBlank()&&data.object(value).parsedType()!=net.oceancanvas.mod.project.OceanCanvasPlanningData.ObjectType.CATCHMENT)throw new IllegalArgumentException("HYDRO_CATCHMENT must reference a CATCHMENT object");
					String guide=withoutGuidePrefix(obj.guideData(),key+"="); if(!value.isBlank())guide=appendGuideToken(guide,key+"="+value); data.putObject(copyPlanningGuide(obj,guide)); tell(player,"Updated hydrology metadata for '"+obj.name()+"'.");
				}
				case "terrain_semantics" -> {
					var obj=data.object(id); if(obj==null)return; if(obj.locked()){tell(player,"Unlock the planning object before changing terrain semantics.",true);return;}
					String key=payload.arg1()==null?"":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
					if(!java.util.Set.of("PEAK_Y","LAKE_Y","SURFACE_Y","CLIFF_Y").contains(key))throw new IllegalArgumentException("unknown terrain semantic");
					String raw=payload.arg2()==null?"":payload.arg2().trim();String[] vals=raw.split(",",-1);
					int expected=(key.equals("PEAK_Y")||key.equals("CLIFF_Y"))?2:1;if(vals.length!=expected)throw new IllegalArgumentException("terrain semantic has wrong number of Y values");
					StringBuilder normalized=new StringBuilder();for(String v:vals){int y=Math.max(-2048,Math.min(4096,Integer.parseInt(v.trim())));if(normalized.length()>0)normalized.append(',');normalized.append(y);}
					String guide=appendGuideToken(withoutGuidePrefix(obj.guideData(),key+"="),key+"="+normalized);data.putObject(copyPlanningGuide(obj,guide));tell(player,"Updated terrain semantics for '"+obj.name()+"'.");
				}
				case "elevation_clear" -> {
					var obj = data.object(id); if (obj == null) return; if(obj.locked()){tell(player,"Unlock the planning object before clearing elevation guides.",true);return;}
					data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
							obj.id(),obj.type(),obj.name(),obj.points(),obj.visible(),obj.locked(),obj.drawOrder(),obj.parentId(),obj.notes(),obj.guideData(),
							obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),java.util.List.of(),obj.scenarioId(),obj.implemented()));
					tell(player,"Cleared elevation guide for '"+obj.name()+"'.");
				}
				case "implemented" -> {
					var obj = data.object(id); if (obj == null) return;
					boolean value = Boolean.parseBoolean(payload.arg1());
					data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
							obj.id(), obj.type(), obj.name(), obj.points(), obj.visible(), obj.locked(), obj.drawOrder(),
							obj.parentId(), obj.notes(), obj.guideData(), obj.strokeArgb(), obj.fillArgb(), obj.widthBlocks(),
							obj.elevationProfile(), obj.scenarioId(), value));
				}
				case "scenario" -> {
					var obj = data.object(id); if (obj == null) return;
					if (obj.locked()) { tell(player, "Unlock the planning object before changing its design scenario.", true); return; }
					data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
							obj.id(), obj.type(), obj.name(), obj.points(), obj.visible(), obj.locked(), obj.drawOrder(),
							obj.parentId(), obj.notes(), obj.guideData(), obj.strokeArgb(), obj.fillArgb(), obj.widthBlocks(),
							obj.elevationProfile(), payload.arg1(), obj.implemented()));
				}
				case "p4_put" -> {
					var p4=net.oceancanvas.mod.project.OceanCanvasP4PlanningData.get(world);String[] f=(payload.arg2()==null?"":payload.arg2()).split("\t",-1);if(f.length!=6)throw new IllegalArgumentException("P4 artifact payload is malformed");
					var kind=net.oceancanvas.mod.project.OceanCanvasP4PlanningData.Kind.parse(payload.arg1());String aid=id.isBlank()?p4.newId(kind.name().toLowerCase(java.util.Locale.ROOT)):id;String name=unb64(f[0]),target=unb64(f[1]),pointText=unb64(f[2]),text=unb64(f[5]);
					var pts=parsePlanningPoints(pointText);double a=Double.parseDouble(f[3]),b=Double.parseDouble(f[4]);p4.put(new net.oceancanvas.mod.project.OceanCanvasP4PlanningData.Artifact(aid,kind.name(),name,target,pts,a,b,text,System.currentTimeMillis()));tell(player,"Saved P4 "+kind.name().toLowerCase(java.util.Locale.ROOT).replace('_',' ')+" '"+name+"'.");
				}
				case "p4_delete" -> {var p4=net.oceancanvas.mod.project.OceanCanvasP4PlanningData.get(world);if(!p4.remove(id))throw new IllegalArgumentException("P4 artifact no longer exists");tell(player,"Deleted P4 planning artifact.");}
				case "p4_reference_anchor" -> {
					var ref=data.referenceLayer(id);if(ref==null)throw new IllegalArgumentException("select a reference image first");if(ref.locked())throw new IllegalArgumentException("unlock the reference layer before aligning it");int wx=Integer.parseInt(payload.arg1()),wz=Integer.parseInt(payload.arg2());int cx=(ref.minX()+ref.maxX())/2,cz=(ref.minZ()+ref.maxZ())/2,dx=wx-cx,dz=wz-cz;data.putReferenceLayer(copyReferenceLayer(ref,ref.name(),ref.visible(),ref.opacity(),ref.locked(),ref.minX()+dx,ref.minZ()+dz,ref.maxX()+dx,ref.maxZ()+dz,ref.rotationDegrees()));var p4=net.oceancanvas.mod.project.OceanCanvasP4PlanningData.get(world);p4.put(new net.oceancanvas.mod.project.OceanCanvasP4PlanningData.Artifact(p4.newId("reference_alignment"),"REFERENCE_ALIGNMENT","Anchor "+ref.name(),ref.id(),java.util.List.of(new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point(wx,wz)),0,0,"Reference center anchored to world landmark",System.currentTimeMillis()));tell(player,"Aligned reference center to "+wx+", "+wz+".");
				}
				case "p4_scenario_fork" -> {
					var obj=data.object(id);if(obj==null)throw new IllegalArgumentException("select a Plan object first");String name=payload.arg1()==null||payload.arg1().isBlank()?"Alternative":payload.arg1().trim();var ws=net.oceancanvas.mod.project.OceanCanvasWorkspaceData.get(world);String sid=ws.newId("scenario");ws.putScenario(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.DesignScenario(sid,name,true,"P4 design alternative"));ws.setActiveScenario(sid);String nid=data.newId(net.oceancanvas.mod.project.OceanCanvasPlanningData.ObjectType.parse(obj.type()).name().toLowerCase(java.util.Locale.ROOT));data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(nid,obj.type(),obj.name()+" · "+name,obj.points(),obj.visible(),false,data.objects().size(),obj.parentId(),obj.notes(),obj.guideData(),obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),obj.elevationProfile(),sid,false));tell(player,"Forked '"+obj.name()+"' into design alternative '"+name+"'.");
				}
				case "p4_organic_subdivide" -> {
					var obj=data.object(id);if(obj==null||obj.points().size()<3)throw new IllegalArgumentException("select an area with at least 3 vertices");if(obj.locked())throw new IllegalArgumentException("unlock the Plan object before subdividing it");long sx=0,sz=0;for(var q:obj.points()){sx+=q.x();sz+=q.z();}int cx=(int)(sx/obj.points().size()),cz=(int)(sz/obj.points().size());int made=0;for(int i=0;i<obj.points().size()&&made<12;i++){var a=obj.points().get(i);var b=obj.points().get((i+1)%obj.points().size());String nid=data.newId("subdivision");var pts=java.util.List.of(new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point(cx,cz),a,b);data.putObject(new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(nid,"FREEFORM_AREA",obj.name()+" Subdivision "+(made+1),pts,true,false,data.objects().size(),obj.parentId(),"P4 organic subdivision","P4_SUBDIVISION_OF="+obj.id(),0xC08FD8F0,0x188FD8F0,0D,java.util.List.of(),obj.scenarioId(),false));made++;}var p4=net.oceancanvas.mod.project.OceanCanvasP4PlanningData.get(world);p4.put(new net.oceancanvas.mod.project.OceanCanvasP4PlanningData.Artifact(p4.newId("organic_subdivision"),"ORGANIC_SUBDIVISION","Subdivision "+obj.name(),obj.id(),obj.points(),made,0,"Generated "+made+" editable child cells",System.currentTimeMillis()));tell(player,"Created "+made+" editable organic subdivision cells.");
				}
				case "p5_transform_put" -> {
					var d=net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world);String[] f=(payload.arg2()==null?"":payload.arg2()).split("\t",-1);if(f.length!=11)throw new IllegalArgumentException("Transform needs source,target,originX,originZ,scale,rotation,flipX,flipZ,seaLevel,floorY,originConvention");
					String tid=id.isBlank()?d.newId("transform"):id;d.putTransform(new net.oceancanvas.mod.project.OceanCanvasP5PipelineData.TransformProfile(tid,payload.arg1(),f[0],f[1],Integer.parseInt(f[2]),Integer.parseInt(f[3]),Double.parseDouble(f[4]),Double.parseDouble(f[5]),Boolean.parseBoolean(f[6]),Boolean.parseBoolean(f[7]),Integer.parseInt(f[8]),Integer.parseInt(f[9]),f[10],System.currentTimeMillis()));tell(player,"Saved P5 coordinate transform '"+payload.arg1()+"'.");
				}
				case "p5_transform_delete" -> {if(!net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world).removeTransform(id))throw new IllegalArgumentException("Transform no longer exists");tell(player,"Deleted P5 transform.");}
				case "p5_recipe_put" -> {
					var d=net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world);String[] f=(payload.arg2()==null?"":payload.arg2()).split("\t",-1);if(f.length!=8)throw new IllegalArgumentException("Recipe needs target,transform,bounds,width,height,layers,naming,validation");
					String rid=id.isBlank()?d.newId("recipe"):id;java.util.List<String> layers=f[5].isBlank()?java.util.List.of():java.util.Arrays.stream(f[5].split(",")).map(String::trim).filter(v->!v.isBlank()).toList();java.util.List<String> checks=f[7].isBlank()?java.util.List.of():java.util.Arrays.stream(f[7].split(",")).map(String::trim).filter(v->!v.isBlank()).toList();
					d.putRecipe(new net.oceancanvas.mod.project.OceanCanvasP5PipelineData.ExportRecipe(rid,payload.arg1(),f[0],f[1],f[2],Integer.parseInt(f[3]),Integer.parseInt(f[4]),layers,f[6],checks,System.currentTimeMillis()));tell(player,"Saved reproducible export recipe '"+payload.arg1()+"'.");
				}
				case "p5_recipe_delete" -> {if(!net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world).removeRecipe(id))throw new IllegalArgumentException("Recipe no longer exists");tell(player,"Deleted export recipe.");}
				case "p5_litematica_put" -> {
					var d=net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world);String[] f=(payload.arg2()==null?"":payload.arg2()).split("\t",-1);if(f.length!=13)throw new IllegalArgumentException("Placement needs project,asset,file,hash,x,y,z,rotation,mirror,status,version,dependencies,notes");
					String pid=id.isBlank()?d.newId("placement"):id;java.util.List<String> deps=f[11].isBlank()?java.util.List.of():java.util.Arrays.stream(f[11].split(",")).map(String::trim).filter(v->!v.isBlank()).toList();
					d.putPlacement(new net.oceancanvas.mod.project.OceanCanvasP5PipelineData.LitematicaPlacement(pid,payload.arg1(),f[0],f[1],f[2],f[3],Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Integer.parseInt(f[7]),f[8],f[9],f[10],deps,f[12],System.currentTimeMillis()));tell(player,"Saved Litematica placement registry entry '"+payload.arg1()+"'.");
				}
				case "p5_litematica_delete" -> {if(!net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world).removePlacement(id))throw new IllegalArgumentException("Placement no longer exists");tell(player,"Deleted Litematica placement entry.");}
				case "p5_marker_schema_put" -> {
					var d=net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world);String[] f=(payload.arg2()==null?"":payload.arg2()).split("\t",-1);if(f.length!=5)throw new IllegalArgumentException("Marker schema needs scope,fields,icon,style,notes");String mid=id.isBlank()?d.newId("marker_schema"):id;java.util.List<String> fields=f[1].isBlank()?java.util.List.of():java.util.Arrays.stream(f[1].split(",")).map(String::trim).filter(v->!v.isBlank()).toList();d.putMarkerSchema(new net.oceancanvas.mod.project.OceanCanvasP5PipelineData.MarkerSchema(mid,payload.arg1(),f[0],fields,f[2],f[3],f[4],System.currentTimeMillis()));tell(player,"Saved custom marker schema '"+payload.arg1()+"'.");
				}
				case "p5_marker_schema_delete" -> {if(!net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world).removeMarkerSchema(id))throw new IllegalArgumentException("Marker schema no longer exists");tell(player,"Deleted custom marker schema.");}
				case "p5_quarantine" -> {
					String[] f=(payload.arg2()==null?"":payload.arg2()).split("\t",-1);if(f.length!=9)throw new IllegalArgumentException("Quarantine metadata needs minX,minZ,maxX,maxZ,width,height,bpp,seaLevel,orientation");
					try{var q=net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.quarantine(world,id,payload.arg1(),Integer.parseInt(f[0]),Integer.parseInt(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Double.parseDouble(f[6]),Integer.parseInt(f[7]),f[8]);tellOnScreen(player,"Import quarantine "+q.item().status()+": "+q.contract().summary());}catch(java.io.IOException ex){throw new IllegalArgumentException("Quarantine inspection failed: "+ex.getMessage());}
				}
				case "p5_quarantine_delete" -> {if(!net.oceancanvas.mod.project.OceanCanvasP5PipelineData.get(world).removeQuarantine(id))throw new IllegalArgumentException("Quarantine item no longer exists");tell(player,"Deleted quarantine review item.");}
				case "p5_reconcile" -> {tellOnScreen(player,net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.reconcile(world,id,payload.arg1()));}
				case "p5_contract_check" -> {
					var a=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(id);if(a==null||a.revisions().isEmpty())throw new IllegalArgumentException("Terrain Asset needs a revision");var r=a.revisions().stream().max(java.util.Comparator.comparingLong(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision::createdAt)).orElseThrow();var c=net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.heightmapContract(a,r);tellOnScreen(player,c.summary()+(c.issues().isEmpty()?"":" · "+String.join("; ",c.issues())));
				}
				case "p5_fingerprint" -> {
					var a=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(id);if(a==null||a.revisions().isEmpty())throw new IllegalArgumentException("Terrain Asset needs a revision");var r=a.revisions().stream().max(java.util.Comparator.comparingLong(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision::createdAt)).orElseThrow();tellOnScreen(player,"P5 fingerprint "+r.id()+": "+net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.fingerprint(a,r));
				}
				case "p5_roundtrip_check" -> {
					var a=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(id);if(a==null||a.revisions().size()<2)throw new IllegalArgumentException("Round-trip check needs at least two revisions");var rs=new java.util.ArrayList<>(a.revisions());rs.sort(java.util.Comparator.comparingLong(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision::createdAt));var rt=net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.roundTrip(a,rs.get(0),rs.get(rs.size()-1),payload.arg1());tellOnScreen(player,rt.summary()+(rt.losses().isEmpty()?"":" · losses "+String.join(", ",rt.losses())));
				}
				case "p5_seams" -> {var reports=net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.inspectSeams(world);long fail=reports.stream().filter(v->!v.pass()).count();tellOnScreen(player,"P5 seam validation: "+reports.size()+" adjacent pair(s), "+fail+" failing.");}
				case "p5_sea_normalize" -> {
					String[] f=(payload.arg1()==null?"":payload.arg1()).split(",",-1);if(f.length!=6)throw new IllegalArgumentException("Sea normalization needs sourceSea,targetSea,sourceMinY,sourceMaxY,targetMinY,targetMaxY");var p=net.oceancanvas.mod.planning.OceanCanvasP5PipelineService.normalizeSeaLevel(Integer.parseInt(f[0]),Integer.parseInt(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]));tellOnScreen(player,p.summary());
				}
                case "program_run" -> {
                    var result=net.oceancanvas.mod.project.OceanCanvasProgramService.run(player,id,payload.arg1(),payload.arg2());
                    tellOnScreen(player,result.phase()+" "+result.featureId()+" · "+result.featureName()+": "+result.summary());
                }
                case "program_delete" -> {
                    if(!net.oceancanvas.mod.project.OceanCanvasProgramData.get(world).removeEntry(id)) throw new IllegalArgumentException("Program artifact no longer exists");
                    tell(player,"Deleted P6-P9 program artifact. Evidence remains available for audit.");
                }
				case "group_add" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);String gid=lib.newId("group");String name=payload.arg1()==null||payload.arg1().isBlank()?"Group":payload.arg1().trim();String parent=payload.arg2()==null?"":payload.arg2().trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');if(!parent.isBlank()&&lib.group(parent)==null)throw new IllegalArgumentException("unknown parent group");
					lib.putGroup(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup(gid,name,parent,true,false,1D,"CUSTOM",lib.groups().size()));
				}
				case "group_visible", "group_lock", "group_opacity" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var group=lib.group(id);if(group==null)return;boolean visible=group.visible(),locked=group.locked();double opacity=group.opacity();
					if(action.equals("group_visible"))visible=Boolean.parseBoolean(payload.arg1());else if(action.equals("group_lock"))locked=Boolean.parseBoolean(payload.arg1());else opacity=Math.max(0.05D,Math.min(1D,Double.parseDouble(payload.arg1())));
					lib.putGroup(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup(group.id(),group.name(),group.parentId(),visible,locked,opacity,group.category(),group.drawOrder()));
				}
				case "group_update" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var group=lib.group(id);if(group==null)return;
					String name=payload.arg1()==null?"":payload.arg1().trim();if(name.isBlank()||name.length()>96)throw new IllegalArgumentException("group name must be 1-96 characters");
					String[] f=(payload.arg2()==null?"":payload.arg2()).split("\t",-1);if(f.length!=4)throw new IllegalArgumentException("invalid group settings");
					String parent=f[0].trim().toLowerCase(java.util.Locale.ROOT).replace(' ','_');if(!parent.isBlank()&&lib.group(parent)==null)throw new IllegalArgumentException("unknown parent group");if(lib.wouldCreateGroupCycle(group.id(),parent))throw new IllegalArgumentException("that parent would create a layer-folder cycle");
					double opacity=Math.max(0.05D,Math.min(1D,Double.parseDouble(f[1])));String category=f[2].trim();if(category.isBlank())category="CUSTOM";int order=Math.max(-10000,Math.min(10000,Integer.parseInt(f[3])));
					lib.putGroup(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup(group.id(),name,parent,group.visible(),group.locked(),opacity,category,order));
				}
				case "group_duplicate" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var group=lib.group(id);if(group==null)return;String gid=lib.newId("group");
					lib.putGroup(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup(gid,group.name()+" Copy",group.parentId(),group.visible(),false,group.opacity(),group.category(),group.drawOrder()+1));
				}
				case "group_solo" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var target=lib.group(id);if(target==null)return;var keep=new java.util.HashSet<String>();String cur=target.id();while(cur!=null&&!cur.isBlank()&&keep.add(cur)){var g=lib.group(cur);if(g==null)break;cur=g.parentId();}
					for(var g:lib.groups())lib.putGroup(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup(g.id(),g.name(),g.parentId(),keep.contains(g.id()),g.locked(),g.opacity(),g.category(),g.drawOrder()));
				}
				case "group_move" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var groups=new java.util.ArrayList<>(lib.groups());groups.sort(java.util.Comparator.comparingInt(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup::drawOrder).thenComparing(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup::id));
					int at=-1;for(int i=0;i<groups.size();i++)if(groups.get(i).id().equals(id)){at=i;break;}if(at<0)return;int to=payload.arg1().equalsIgnoreCase("up")?at+1:at-1;if(to<0||to>=groups.size())return;
					java.util.Collections.swap(groups,at,to);for(int i=0;i<groups.size();i++){var g=groups.get(i);lib.putGroup(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup(g.id(),g.name(),g.parentId(),g.visible(),g.locked(),g.opacity(),g.category(),i));}
				}
				case "export_plan" -> {
					try {
						var r=net.oceancanvas.mod.planning.OceanCanvasPlanExporter.export(world,payload.arg1());
						tellOnScreen(player,"Exported Plan: "+r.objects()+" objects, "+r.references()+" references, "+r.groups()+" layers -> "+r.directory());
					} catch(java.io.IOException ex) { tell(player,"Plan export failed: "+ex.getMessage(),true); }
				}
				case "export_gaea_masks" -> {
					try {
						var r=net.oceancanvas.mod.planning.OceanCanvasPlanExporter.exportMasks(world,payload.arg2(),payload.arg1(),id);
						tellOnScreen(player,"Exported Gaea masks: "+r.objects()+" objects across "+r.layers()+" layers -> "+r.directory());
					} catch (java.io.IOException ex) { throw new IllegalArgumentException("Gaea mask export failed: "+ex.getMessage()); }
				}
				case "terrain_placement" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(id);if(asset==null)return;String placement=payload.arg1()==null?"":payload.arg1();
					lib.putTerrainAsset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.TerrainAsset(asset.id(),asset.name(),asset.planningObjectIds(),asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),asset.seaLevel(),asset.status(),asset.revisions(),asset.approvedGaeaRevision(),asset.approvedWorldPainterRevision(),placement,asset.notes()));
				}
				case "bookmark_add" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);String[] f=payload.arg2().split(",",-1);if(f.length<3)throw new IllegalArgumentException("bookmark requires x,z,zoom");
					lib.putBookmark(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanBookmark(lib.newId("bookmark"),payload.arg1(),Integer.parseInt(f[0]),Integer.parseInt(f[1]),Double.parseDouble(f[2]),id,""));
				}
				case "bookmark_delete" -> {net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).removeBookmark(id);}
                case "viewpoint_add" -> {
                    var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);String[] f=payload.arg2().split(",",-1);if(f.length<8)throw new IllegalArgumentException("viewpoint requires x,y,z,yaw,pitch,projection,depth,opacity");
                    String name=payload.arg1()==null?"":payload.arg1().trim();if(name.isBlank()||name.length()>96)throw new IllegalArgumentException("viewpoint name must be 1-96 characters");
                    lib.putViewpoint(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.BlueprintViewpoint(lib.newId("viewpoint"),name,Double.parseDouble(f[0]),Double.parseDouble(f[1]),Double.parseDouble(f[2]),Float.parseFloat(f[3]),Float.parseFloat(f[4]),f[5],f[6],Float.parseFloat(f[7])));
                    tellOnScreen(player,"Saved Blueprint viewpoint: "+name);
                }
                case "viewpoint_delete" -> {net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).removeViewpoint(id);}
				case "preset_add" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);String name=payload.arg1()==null?"":payload.arg1().trim();if(name.isBlank()||name.length()>96)throw new IllegalArgumentException("preset name must be 1-96 characters");
					var vg=new java.util.ArrayList<String>();var hg=new java.util.ArrayList<String>();for(var g:lib.groups())(g.visible()?vg:hg).add(g.id());
					var vr=new java.util.ArrayList<String>();var hr=new java.util.ArrayList<String>();for(var r:data.referenceLayers())(r.visible()?vr:hr).add(r.id());
					var vrs=new java.util.ArrayList<String>();var hrs=new java.util.ArrayList<String>();for(var s:lib.referenceSets())(s.visible()?vrs:hrs).add(s.id());
					lib.putPreset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ViewPreset(lib.newId("preset"),name,vg,hg,vr,hr,vrs,hrs,"Saved from the canonical Plan view."));tellOnScreen(player,"Saved Plan view preset: "+name);
				}
				case "preset_delete" -> {var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);if(id.equals("physical_geography")||id.equals("civilization")){tell(player,"Built-in presets cannot be deleted.",true);return;}lib.removePreset(id);}
				case "preset_apply" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var preset=lib.preset(id);if(preset==null)return;var visible=new java.util.HashSet<>(preset.visibleGroups());var hidden=new java.util.HashSet<>(preset.hiddenGroups());
					for(var g:lib.groups()){boolean on=!visible.isEmpty()?visible.contains(g.id()):g.visible();if(hidden.contains(g.id()))on=false;lib.putGroup(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.PlanGroup(g.id(),g.name(),g.parentId(),on,g.locked(),g.opacity(),g.category(),g.drawOrder()));}
					var vr=new java.util.HashSet<>(preset.visibleReferences());var hr=new java.util.HashSet<>(preset.hiddenReferences());if(!vr.isEmpty()||!hr.isEmpty())for(var r:data.referenceLayers()){boolean on=!vr.isEmpty()?vr.contains(r.id()):r.visible();if(hr.contains(r.id()))on=false;data.putReferenceLayer(copyReferenceLayer(r,r.name(),on,r.opacity(),r.locked(),r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.rotationDegrees()));}
					var vrs=new java.util.HashSet<>(preset.visibleReferenceSets());var hrs=new java.util.HashSet<>(preset.hiddenReferenceSets());if(!vrs.isEmpty()||!hrs.isEmpty())for(var s:lib.referenceSets()){boolean on=!vrs.isEmpty()?vrs.contains(s.id()):s.visible();if(hrs.contains(s.id()))on=false;lib.putReferenceSet(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet(s.id(),s.name(),s.referenceIds(),on,s.locked(),s.opacity(),s.drawOrder(),s.notes()));}
					tellOnScreen(player,"Applied Plan view preset: "+preset.name());
				}
				case "refset_add" -> {var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);String name=payload.arg1()==null?"":payload.arg1().trim();if(name.isBlank()||name.length()>96)throw new IllegalArgumentException("reference-set name must be 1-96 characters");lib.putReferenceSet(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet(lib.newId("refset"),name,java.util.List.of(),true,false,1D,lib.referenceSets().size(),""));}
				case "refset_member" -> {var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var set=lib.referenceSet(id);if(set==null)return;if(set.locked()){tell(player,"Unlock the Reference Set before changing membership.",true);return;}String rid=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);if(data.referenceLayer(rid)==null)throw new IllegalArgumentException("unknown reference layer");var ids=new java.util.ArrayList<>(set.referenceIds());if(ids.remove(rid))tell(player,"Removed reference from '"+set.name()+"'.");else{ids.add(rid);tell(player,"Added reference to '"+set.name()+"'.");}lib.putReferenceSet(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet(set.id(),set.name(),ids,set.visible(),set.locked(),set.opacity(),set.drawOrder(),set.notes()));}
				case "refset_visible" -> {var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var set=lib.referenceSet(id);if(set==null)return;boolean on=Boolean.parseBoolean(payload.arg1());for(String rid:set.referenceIds()){var r=data.referenceLayer(rid);if(r!=null)data.putReferenceLayer(copyReferenceLayer(r,r.name(),on,r.opacity(),r.locked(),r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.rotationDegrees()));}lib.putReferenceSet(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet(set.id(),set.name(),set.referenceIds(),on,set.locked(),set.opacity(),set.drawOrder(),set.notes()));}
				case "refset_opacity" -> {var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var set=lib.referenceSet(id);if(set==null)return;double opacity=Math.max(0.05D,Math.min(1D,Double.parseDouble(payload.arg1())));for(String rid:set.referenceIds()){var r=data.referenceLayer(rid);if(r!=null)data.putReferenceLayer(copyReferenceLayer(r,r.name(),r.visible(),opacity,r.locked(),r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.rotationDegrees()));}lib.putReferenceSet(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet(set.id(),set.name(),set.referenceIds(),set.visible(),set.locked(),opacity,set.drawOrder(),set.notes()));}
				case "refset_lock" -> {var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var set=lib.referenceSet(id);if(set==null)return;lib.putReferenceSet(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet(set.id(),set.name(),set.referenceIds(),set.visible(),Boolean.parseBoolean(payload.arg1()),set.opacity(),set.drawOrder(),set.notes()));}
				case "refset_solo" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var target=lib.referenceSet(id);if(target==null)return;var keep=new java.util.HashSet<>(target.referenceIds());
					for(var s:lib.referenceSets())lib.putReferenceSet(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet(s.id(),s.name(),s.referenceIds(),s.id().equals(target.id()),s.locked(),s.opacity(),s.drawOrder(),s.notes()));
					for(var r:data.referenceLayers())data.putReferenceLayer(copyReferenceLayer(r,r.name(),keep.contains(r.id()),r.opacity(),r.locked(),r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.rotationDegrees()));
				}
				case "refset_delete" -> {net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).removeReferenceSet(id);}
				case "refset_move" -> {var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var sets=new java.util.ArrayList<>(lib.referenceSets());sets.sort(java.util.Comparator.comparingInt(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet::drawOrder).thenComparing(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet::id));int at=-1;for(int i=0;i<sets.size();i++)if(sets.get(i).id().equals(id)){at=i;break;}if(at<0)return;int to=payload.arg1().equalsIgnoreCase("up")?at+1:at-1;if(to<0||to>=sets.size())return;java.util.Collections.swap(sets,at,to);for(int i=0;i<sets.size();i++){var set=sets.get(i);lib.putReferenceSet(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.ReferenceSet(set.id(),set.name(),set.referenceIds(),set.visible(),set.locked(),set.opacity(),i,set.notes()));}}
				case "terrain_asset_add" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);String[] f=payload.arg2().split(",",-1);if(f.length<5)throw new IllegalArgumentException("terrain asset requires minX,minZ,maxX,maxZ,seaLevel");String aid=lib.newId("terrain");
					lib.putTerrainAsset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.TerrainAsset(aid,payload.arg1(),id.isBlank()?java.util.List.of():java.util.List.of(id),Integer.parseInt(f[0]),Integer.parseInt(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]),"PLANNED",java.util.List.of(),"","","",""));
				}
				case "terrain_revision_add" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(id);if(asset==null)return;String[] f=payload.arg2().split("\t",-1);if(f.length<5)throw new IllegalArgumentException("revision requires stage,file,widthPx,heightPx,blocksPerPixel");
					var rev=new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision(lib.newId("revision"),f[0],f[1],"",asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Double.parseDouble(f[4]),asset.seaLevel(),"NORTH_UP",System.currentTimeMillis(),payload.arg1());var revs=new java.util.ArrayList<>(asset.revisions());revs.add(rev);
					lib.putTerrainAsset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.TerrainAsset(asset.id(),asset.name(),asset.planningObjectIds(),asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),asset.seaLevel(),f[0].toUpperCase(java.util.Locale.ROOT),revs,asset.approvedGaeaRevision(),asset.approvedWorldPainterRevision(),asset.placementData(),asset.notes()));
				}
				case "terrain_heightmap_revision" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(id);if(asset==null)return;String[] f=payload.arg2().split("\t",-1);
					if(f.length<11)throw new IllegalArgumentException("heightmap revision requires stage,file,width,height,bpp,hash,bits,min,max,minY,maxY");
					int width=Integer.parseInt(f[2]),height=Integer.parseInt(f[3]),bits=Integer.parseInt(f[6]),minY=Integer.parseInt(f[9]),maxY=Integer.parseInt(f[10]);double bpp=Double.parseDouble(f[4]);
					if(width<1||height<1||width>131072||height>131072||bits<1||bits>64||bpp<=0||!Double.isFinite(bpp)||minY>=maxY)throw new IllegalArgumentException("invalid heightmap metadata");
					String hash=f[5];if(!hash.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("invalid heightmap hash");
					String notes="HEIGHTMAP bits="+bits+" observed="+f[7]+".."+f[8]+" mappedY="+minY+".."+maxY;
					var rev=new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision(lib.newId("revision"),f[0],f[1],hash,asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),width,height,bpp,asset.seaLevel(),"NORTH_UP",System.currentTimeMillis(),notes);var revs=new java.util.ArrayList<>(asset.revisions());revs.add(rev);
					lib.putTerrainAsset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.TerrainAsset(asset.id(),asset.name(),asset.planningObjectIds(),asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),asset.seaLevel(),f[0].toUpperCase(java.util.Locale.ROOT),revs,asset.approvedGaeaRevision(),asset.approvedWorldPainterRevision(),asset.placementData(),asset.notes()));
					tell(player,"Registered analyzed heightmap revision for '"+asset.name()+"'. Terrain was not modified.");
				}
				case "terrain_review_checkpoint" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(id);if(asset==null)return;String[] f=payload.arg2().split("\t",-1);if(f.length<7)throw new IllegalArgumentException("checkpoint requires stage,file,hash,loaded,missing,mean,max");String hash=f[2];if(!hash.isBlank()&&!hash.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("invalid checkpoint hash");String notes="REVIEW status=PENDING loaded="+f[3]+" missing="+f[4]+" meanDev="+f[5]+" maxDev="+f[6]+(payload.arg1().isBlank()?"":" note="+payload.arg1().replace(' ','_'));var rev=new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision(lib.newId("revision"),f[0],f[1],hash,asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),0,0,0D,asset.seaLevel(),"NORTH_UP",System.currentTimeMillis(),notes);var revs=new java.util.ArrayList<>(asset.revisions());revs.add(rev);lib.putTerrainAsset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.TerrainAsset(asset.id(),asset.name(),asset.planningObjectIds(),asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),asset.seaLevel(),f[0].toUpperCase(java.util.Locale.ROOT),revs,asset.approvedGaeaRevision(),asset.approvedWorldPainterRevision(),asset.placementData(),asset.notes()));tell(player,"Saved terrain review checkpoint for '"+asset.name()+"'.");
				}
				case "terrain_review_state" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(id);if(asset==null)return;String[] f=payload.arg1().split(":",2);if(f.length!=2)throw new IllegalArgumentException("review state requires revisionId:APPROVED|REVISE");String state=f[1].toUpperCase(java.util.Locale.ROOT);if(!state.equals("APPROVED")&&!state.equals("REVISE")&&!state.equals("PENDING"))throw new IllegalArgumentException("invalid review state");var revs=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision>();boolean found=false;for(var r:asset.revisions()){if(r.id().equals(f[0])){found=true;String notes=r.notes().replaceFirst("REVIEW status=[A-Z]+","REVIEW status="+state);if(!notes.startsWith("REVIEW status="))notes="REVIEW status="+state+" "+notes;revs.add(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision(r.id(),r.stage(),r.fileName(),r.fileHash(),r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.widthPx(),r.heightPx(),r.blocksPerPixel(),r.seaLevel(),r.orientation(),r.createdAt(),notes));}else revs.add(r);}if(!found)throw new IllegalArgumentException("unknown revision");lib.putTerrainAsset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.TerrainAsset(asset.id(),asset.name(),asset.planningObjectIds(),asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),asset.seaLevel(),asset.status(),revs,asset.approvedGaeaRevision(),asset.approvedWorldPainterRevision(),asset.placementData(),asset.notes()));
				}
				case "terrain_review_note" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(id);if(asset==null)return;String rid=payload.arg1();String note=payload.arg2()==null?"":payload.arg2().trim();if(note.length()>512)throw new IllegalArgumentException("review note is limited to 512 characters");var revs=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision>();boolean found=false;for(var r:asset.revisions()){if(r.id().equals(rid)){found=true;String notes=r.notes().replaceAll("(?:^| )userNote=[^ ]*","").trim();if(!note.isBlank())notes=(notes+" userNote="+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(note.getBytes(java.nio.charset.StandardCharsets.UTF_8))).trim();revs.add(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.AssetRevision(r.id(),r.stage(),r.fileName(),r.fileHash(),r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.widthPx(),r.heightPx(),r.blocksPerPixel(),r.seaLevel(),r.orientation(),r.createdAt(),notes));}else revs.add(r);}if(!found)throw new IllegalArgumentException("unknown revision");lib.putTerrainAsset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.TerrainAsset(asset.id(),asset.name(),asset.planningObjectIds(),asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),asset.seaLevel(),asset.status(),revs,asset.approvedGaeaRevision(),asset.approvedWorldPainterRevision(),asset.placementData(),asset.notes()));
				}
				case "terrain_approve" -> {
					var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);var asset=lib.terrainAsset(id);if(asset==null)return;String[] f=payload.arg1().split(":",2);if(f.length!=2)throw new IllegalArgumentException("approval requires stage:revisionId");String gaea=asset.approvedGaeaRevision(),wp=asset.approvedWorldPainterRevision();if(f[0].equalsIgnoreCase("gaea"))gaea=f[1];else if(f[0].equalsIgnoreCase("worldpainter"))wp=f[1];else throw new IllegalArgumentException("unknown approval stage");
					if(asset.revision(f[1])==null)throw new IllegalArgumentException("unknown revision");lib.putTerrainAsset(new net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.TerrainAsset(asset.id(),asset.name(),asset.planningObjectIds(),asset.minX(),asset.minZ(),asset.maxX(),asset.maxZ(),asset.seaLevel(),asset.status(),asset.revisions(),gaea,wp,asset.placementData(),asset.notes()));
				}
				default -> { tell(player, "Unknown planning edit '" + action + "'.", true); return; }
			}
		} catch (RuntimeException ex) { tell(player, "Invalid planning edit: " + ex.getMessage(), true); return; }
		net.oceancanvas.mod.planning.OceanCanvasPlanningHistory.record(player, planningObjectsBefore, data.objects());
        recordPlanningArchaeology(world,player,action,id,planningObjectsBefore,data.objects());
		broadcastToAll(world.getServer());
	}

    /** OC-F142: automatically project successful Plan object lifecycle into the durable world-event timeline. */
    private static void recordPlanningArchaeology(ServerLevel world,ServerPlayer player,String action,String requestedId,java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject> before,java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject> after){
        var beforeIds=new java.util.HashSet<String>();for(var o:before)beforeIds.add(o.id());var afterIds=new java.util.HashSet<String>();for(var o:after)afterIds.add(o.id());
        var fw=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world);String actor=player==null?"system":player.getGameProfile().name();long now=System.currentTimeMillis();
        for(var o:after)if(!beforeIds.contains(o.id())){try{fw.addWorldEvent("PLAN",o.id(),"CREATED","Plan created",o.name()+" · "+o.type(),actor,"planning:"+action,now);}catch(RuntimeException ignored){}}
        for(var o:before)if(!afterIds.contains(o.id())){try{fw.addWorldEvent("PLAN",o.id(),"DELETED","Plan removed",o.name()+" · "+o.type(),actor,"planning:"+action,now);}catch(RuntimeException ignored){}}
        if(requestedId!=null&&!requestedId.isBlank()&&beforeIds.contains(requestedId)&&afterIds.contains(requestedId)){var o=after.stream().filter(v->v.id().equals(requestedId)).findFirst().orElse(null);if(o!=null)try{fw.addWorldEvent("PLAN",o.id(),"EDITED","Plan edited",o.name()+" · "+action,actor,"planning:"+action,now);}catch(RuntimeException ignored){}}
    }

	private static net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer copyReferenceLayer(
			net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer ref,String name,boolean visible,double opacity,boolean locked,
			int minX,int minZ,int maxX,int maxZ,double rotation) {
		return new net.oceancanvas.mod.project.OceanCanvasPlanningData.ReferenceLayer(ref.id(),name,ref.assetId(),visible,opacity,locked,minX,minZ,maxX,maxZ,rotation,ref.drawOrder(),ref.registrationPoints(),ref.notes());
	}

	private static net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject copyPlanningObject(
			net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject obj, String name, boolean visible, boolean locked, int order) {
		return new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
				obj.id(), obj.type(), name, obj.points(), visible, locked, order, obj.parentId(), obj.notes(), obj.guideData(),
				obj.strokeArgb(), obj.fillArgb(), obj.widthBlocks(), obj.elevationProfile(), obj.scenarioId(), obj.implemented());
	}

	private static net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject copyPlanningPoints(
			net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject obj,
			java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> points) {
		return new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(
				obj.id(),obj.type(),obj.name(),java.util.List.copyOf(points),obj.visible(),obj.locked(),obj.drawOrder(),obj.parentId(),obj.notes(),obj.guideData(),
				obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),obj.elevationProfile(),obj.scenarioId(),obj.implemented());
	}

	private static net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject copyPlanningGuide(net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject obj,String guide){
        return new net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject(obj.id(),obj.type(),obj.name(),obj.points(),obj.visible(),obj.locked(),obj.drawOrder(),obj.parentId(),obj.notes(),guide,obj.strokeArgb(),obj.fillArgb(),obj.widthBlocks(),obj.elevationProfile(),obj.scenarioId(),obj.implemented());
    }
    private static String appendGuideToken(String guide,String token){guide=guide==null?"":guide.trim();return guide.isBlank()?token:guide+";"+token;}
    private static String withoutGuideToken(String guide,String token){var out=new java.util.ArrayList<String>();if(guide!=null)for(String t:guide.split(";"))if(!t.isBlank()&&!t.equalsIgnoreCase(token))out.add(t);return String.join(";",out);}
    private static String withoutGuidePrefix(String guide,String prefix){var out=new java.util.ArrayList<String>();if(guide!=null)for(String t:guide.split(";"))if(!t.isBlank()&&!t.startsWith(prefix))out.add(t);return String.join(";",out);}
    private static String guideValue(String guide,String prefix){if(guide!=null)for(String t:guide.split(";"))if(t.startsWith(prefix))return t.substring(prefix.length());return "";}
    private static String autoBezierGuide(java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> pts){var out=new StringBuilder();for(int i=0;i<pts.size();i++){var p=pts.get(i);var prev=pts.get(Math.max(0,i-1));var next=pts.get(Math.min(pts.size()-1,i+1));double divisor=(i==0||i==pts.size()-1)?3.0:6.0;int tx=(int)Math.round((next.x()-prev.x())/divisor),tz=(int)Math.round((next.z()-prev.z())/divisor);if(out.length()>0)out.append('/');out.append(i).append(',').append(-tx).append(',').append(-tz).append(',').append(tx).append(',').append(tz);}return out.toString();}
    private static String validateBezierGuide(String raw,int pointCount){if(raw==null)throw new IllegalArgumentException("missing Bezier handles");var out=new java.util.LinkedHashMap<Integer,String>();for(String token:raw.split("/")){String[] f=token.split(",",-1);if(f.length!=5)throw new IllegalArgumentException("invalid Bezier handle");int i=Integer.parseInt(f[0]);if(i<0||i>=pointCount)throw new IllegalArgumentException("Bezier handle vertex out of range");StringBuilder b=new StringBuilder().append(i);for(int n=1;n<5;n++){int v=Math.max(-1000000,Math.min(1000000,Integer.parseInt(f[n])));b.append(',').append(v);}out.put(i,b.toString());}if(out.isEmpty())throw new IllegalArgumentException("Bezier handles cannot be empty");return String.join("/",out.values());}
    private static int nearestEndpoint(net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject obj,int x,int z){var a=obj.points().get(0);var b=obj.points().get(obj.points().size()-1);double da=Math.hypot(a.x()-x,a.z()-z),db=Math.hypot(b.x()-x,b.z()-z);return da<=db?0:1;}
    private static int nearestPrimaryEndpoint(net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject primary,java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject> all){var a=primary.points().get(0);var b=primary.points().get(primary.points().size()-1);double da=Double.POSITIVE_INFINITY,db=Double.POSITIVE_INFINITY;for(var o:all){if(o.id().equals(primary.id()))continue;for(var p:java.util.List.of(o.points().get(0),o.points().get(o.points().size()-1))){da=Math.min(da,Math.hypot(a.x()-p.x(),a.z()-p.z()));db=Math.min(db,Math.hypot(b.x()-p.x(),b.z()-p.z()));}}return da<=db?0:1;}
    private static void validateLinkedEndpointMove(net.oceancanvas.mod.project.OceanCanvasPlanningData data,net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject old,java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> next){if(next.isEmpty()||old.points().isEmpty())return;for(boolean start:new boolean[]{true,false}){int oi=start?0:old.points().size()-1,ni=start?0:next.size()-1;var a=old.points().get(oi);var b=next.get(ni);if(a.x()==b.x()&&a.z()==b.z())continue;String node=guideValue(old.guideData(),start?"LINK_START=":"LINK_END=");if(node.isBlank())continue;for(var peer:data.objects()){if(peer.id().equals(old.id()))continue;if(node.equals(guideValue(peer.guideData(),"LINK_START="))||node.equals(guideValue(peer.guideData(),"LINK_END=")))if(peer.locked())throw new IllegalArgumentException("linked endpoint belongs to locked Plan object '"+peer.name()+"'");}}}
    private static void propagateLinkedEndpoint(net.oceancanvas.mod.project.OceanCanvasPlanningData data,net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject source,boolean start){if(source.points().isEmpty())return;String node=guideValue(source.guideData(),start?"LINK_START=":"LINK_END=");if(node.isBlank())return;var p=source.points().get(start?0:source.points().size()-1);for(var peer:new java.util.ArrayList<>(data.objects())){if(peer.id().equals(source.id())||peer.points().isEmpty())continue;boolean ps=node.equals(guideValue(peer.guideData(),"LINK_START=")),pe=node.equals(guideValue(peer.guideData(),"LINK_END="));if(!ps&&!pe)continue;var pts=new java.util.ArrayList<>(peer.points());if(ps)pts.set(0,new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point(p.x(),p.z()));if(pe)pts.set(pts.size()-1,new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point(p.x(),p.z()));data.putObject(copyPlanningPoints(peer,pts));}}

	private static java.util.List<String> parsePlanningIds(String raw){var out=new java.util.ArrayList<String>();if(raw!=null)for(String x:raw.split(",")){x=x.trim();if(!x.isBlank()&&!out.contains(x))out.add(x);}return out;}
	private static int[] parsePlanningDelta(String raw){String[] f=(raw==null?"":raw).split(",",-1);if(f.length!=2)throw new IllegalArgumentException("move requires dx,dz");int dx=Math.max(-1000000,Math.min(1000000,Integer.parseInt(f[0]))),dz=Math.max(-1000000,Math.min(1000000,Integer.parseInt(f[1])));return new int[]{dx,dz};}
	private static java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> translatePlanningPoints(java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> pts,int dx,int dz){var out=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point>(pts.size());for(var p:pts)out.add(new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point(p.x()+dx,p.z()+dz));return java.util.List.copyOf(out);}
	private static String copyPlanningName(String name){String base=(name==null||name.isBlank())?"Plan Object":name;return base.length()<=59?base+" Copy":base.substring(0,59)+" Copy";}
	private static boolean samePlanningPoint(net.oceancanvas.mod.project.OceanCanvasPlanningData.Point a,net.oceancanvas.mod.project.OceanCanvasPlanningData.Point b){return a.x()==b.x()&&a.z()==b.z();}
    private static java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> pushPullPlanningPoints(java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> pts,int seg,double wx,double wz,double amount,boolean closed){if(pts.size()<2)return pts;int n=pts.size();seg=Math.max(0,Math.min(n-2,seg));var a=pts.get(seg);var b=pts.get(seg+1);double dx=b.x()-a.x(),dz=b.z()-a.z(),len=Math.hypot(dx,dz);if(len<1e-6)return pts;double nx=-dz/len,nz=dx/len,mx=(a.x()+b.x())/2.0,mz=(a.z()+b.z())/2.0;if((wx-mx)*nx+(wz-mz)*nz<0){nx=-nx;nz=-nz;}double center=seg+0.5,radius=Math.max(2.0D,Math.min(12.0D,n/4.0D+1.0D));var out=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point>(n);for(int i=0;i<n;i++){double d=Math.abs(i-center);if(closed)d=Math.min(d,n-d);double t=Math.max(0D,1D-d/radius);t=t*t*(3D-2D*t);var q=pts.get(i);out.add(new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point((int)Math.round(q.x()+nx*amount*t),(int)Math.round(q.z()+nz*amount*t)));}return java.util.List.copyOf(out);}

	private static String clearPlanningTopologyGuide(String guide){return withoutGuidePrefix(withoutGuidePrefix(withoutGuidePrefix(guide,"LINK_START="),"LINK_END="),"BEZIER=");}
	private static java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> joinPlanningPoints(java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> aa,java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> bb){var a=new java.util.ArrayList<>(aa);var b=new java.util.ArrayList<>(bb);double dSS=planningDist2(a.get(0),b.get(0)),dSE=planningDist2(a.get(0),b.get(b.size()-1)),dES=planningDist2(a.get(a.size()-1),b.get(0)),dEE=planningDist2(a.get(a.size()-1),b.get(b.size()-1));double best=Math.min(Math.min(dSS,dSE),Math.min(dES,dEE));if(best==dSS){java.util.Collections.reverse(a);}else if(best==dSE){java.util.Collections.reverse(a);java.util.Collections.reverse(b);}else if(best==dEE){java.util.Collections.reverse(b);}if(samePlanningPoint(a.get(a.size()-1),b.get(0)))b.remove(0);a.addAll(b);return java.util.List.copyOf(a);}
	private static double planningDist2(net.oceancanvas.mod.project.OceanCanvasPlanningData.Point a,net.oceancanvas.mod.project.OceanCanvasPlanningData.Point b){double dx=(double)a.x()-b.x(),dz=(double)a.z()-b.z();return dx*dx+dz*dz;}
	private static void validateBatchMoveLinks(net.oceancanvas.mod.project.OceanCanvasPlanningData data,net.oceancanvas.mod.project.OceanCanvasPlanningData.PlanningObject o,java.util.Set<String> selected){for(String prefix:java.util.List.of("LINK_START=","LINK_END=")){String node=guideValue(o.guideData(),prefix);if(node.isBlank())continue;for(var peer:data.objects()){if(peer.id().equals(o.id()))continue;boolean same=node.equals(guideValue(peer.guideData(),"LINK_START="))||node.equals(guideValue(peer.guideData(),"LINK_END="));if(same&&!selected.contains(peer.id()))throw new IllegalArgumentException("select every path linked to shared node "+node+" before moving the group");}}}

	private static int planningMinPoints(String rawType) {
		var type=net.oceancanvas.mod.project.OceanCanvasPlanningData.ObjectType.parse(rawType);
		return switch(type){case LANDMARK,TEXT,CITY,PEAK->1;case RIVER,ROAD,PATH,BRIDGE,TRANSPORT_ROUTE,COASTLINE,MOUNTAIN_RANGE,RIDGELINE,VALLEY,CLIFF,TERRAIN_PROFILE,BORDER,FREEFORM_LINE->2;default->3;};
	}

	private static boolean planningTypeClosed(String rawType) {
		return switch(net.oceancanvas.mod.project.OceanCanvasPlanningData.ObjectType.parse(rawType)){
			case CONTINENT,LAKE,CATCHMENT,BIOME_AREA,FOREST,DESERT,SETTLEMENT,DISTRICT,BUILD,PORT,HARBOR,REGION,FREEFORM_AREA,TERRAIN_ZONE,PLATEAU,BASIN -> true;
			default -> false;
		};
	}

	private static java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> parsePlanningPoints(String packed) {
		java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> out = new java.util.ArrayList<>();
		if (packed == null || packed.isBlank()) return out;
		for (String raw : packed.split(";")) {
			if (out.size() >= 2048) throw new IllegalArgumentException("too many points (max 2048)");
			String[] pair = raw.split(",", -1); if (pair.length != 2) continue;
			out.add(new net.oceancanvas.mod.project.OceanCanvasPlanningData.Point(Integer.parseInt(pair[0]), Integer.parseInt(pair[1])));
		}
		return java.util.List.copyOf(out);
	}

	private static String prettyPlanningName(net.oceancanvas.mod.project.OceanCanvasPlanningData.ObjectType type) {
		String raw = type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
	}

	private static void handleInspect(OceanCanvasInspectRequestPayload payload, ServerPlayer player) {
        if (!hasPlanningViewPermission(player)) { tell(player,"You do not have permission to inspect Ocean Canvas planning/world metadata.",true); return; }
		var snap = net.oceancanvas.mod.project.OceanCanvasInspectorService.inspect(player.level(), payload.x(), payload.z());
		StringBuilder packed = new StringBuilder();
		packed.append("X=").append(snap.blockX()).append(";Z=").append(snap.blockZ())
				.append(";chunk=").append(snap.chunkX()).append(',').append(snap.chunkZ())
				.append(";terrain=").append(snap.terrainState().name())
				.append(";processed=").append(snap.processed())
				.append(";protected=").append(snap.protectedHere())
				.append(";provenance=").append(snap.provenanceClass())
				.append(";provenanceDetail=").append(b64(snap.provenanceDetail()))
				.append(";lastOperation=").append(b64(snap.lastOperation()))
				.append(";regions=").append(b64(String.join(", ", snap.containingRegions())));
		for (var rule : snap.rules()) {
			packed.append(";rule=").append(b64(rule.label())).append(',').append(b64(rule.value())).append(',').append(b64(rule.source()));
		}
        var p1=net.oceancanvas.mod.project.OceanCanvasP1W3Data.get(player.level());
        long ck=net.minecraft.world.level.ChunkPos.pack(snap.chunkX(),snap.chunkZ());
        var ce=p1.chunk(ck);
        if(ce!=null){
            packed.append(";biomeId=").append(b64(ce.biomeId()))
                    .append(";biomeProvenance=").append(b64(ce.biomeProvenance()))
                    .append(";biomeDetail=").append(b64(ce.biomeDetail()))
                    .append(";generationVersion=").append(b64(ce.generationVersion()))
                    .append(";observedBuild=").append(b64(ce.observedBuild()))
                    .append(";vanillaBaseline=").append(ce.vanillaBaseline())
                    .append(";vanillaFingerprint=").append(Long.toUnsignedString(ce.vanillaFingerprint()));
        }else packed.append(";biomeProvenance=").append(b64("UNVERIFIED")).append(";generationVersion=").append(b64("UNVERIFIED"));
        int structureHits=0;for(var st:p1.structures())if(st.contains(snap.blockX(),snap.blockZ())&&structureHits++<8){
            packed.append(";structureProvenance=").append(b64(st.kind())).append(',').append(b64(st.provenance())).append(',').append(b64(st.detail()));
        }
		ServerPlayNetworking.send(player, new OceanCanvasInspectResponsePayload(packed.toString()));
	}

	private static void handleHealth(OceanCanvasHealthRequestPayload payload, ServerPlayer player) {
        if (!hasDiagnosticsPermission(player)) { tell(player,"You do not have permission to run Ocean Canvas health tools.",true); return; }
        String rawAction=payload.action()==null?"scan":payload.action().trim();
        if(rawAction.startsWith("physical_")){
            int page=0;
            if(rawAction.startsWith("physical_status:")){
                try { page=Math.max(0,Integer.parseInt(rawAction.substring("physical_status:".length()))); }
                catch(NumberFormatException ex){tell(player,"Invalid Health results page.",true);return;}
            } else if("physical_cancel".equals(rawAction)){
                net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.cancel(player);
            } else if("physical_pause".equals(rawAction)){
                tell(player,net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.pause(player));
            } else if("physical_resume".equals(rawAction)){
                tell(player,net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.resume(player));
            } else if(rawAction.startsWith("physical_build_protect_here:")){
                tell(player,net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.protectCurrentBuild(player,rawAction.substring("physical_build_protect_here:".length())));
            } else if("physical_build_unprotect_here".equals(rawAction)){
                tell(player,net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.unprotectCurrentBuild(player));
            } else {
                tell(player,net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.start(player,rawAction));
            }
            var physical=net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.report(player,page);
            ServerPlayNetworking.send(player,new OceanCanvasHealthResponsePayload(
                    net.oceancanvas.mod.project.OceanCanvasPhysicalHealth.encode(physical)));
            return;
        }
        String action=rawAction.toLowerCase(java.util.Locale.ROOT);
        ServerLevel world=player.level();
        // P1-W3 evidence/workflow actions. These mutate only Ocean Canvas metadata/evidence; destructive
        // recipe steps still stop at the existing server-authored operation preview boundary.
        try {
            if(action.equals("scale_capture")){var r=net.oceancanvas.mod.project.OceanCanvasP1W4Service.captureScale(world);tell(player,"World-scale snapshot captured. "+r.delta());action="scan";}
            else if(action.equals("storage_sample")){var r=net.oceancanvas.mod.project.OceanCanvasP1W4Service.sampleStorage(world);tell(player,"Storage sample: "+net.oceancanvas.mod.project.OceanCanvasP1W4Service.formatBytes(r.current().totalBytes())+"; 365-day projection "+net.oceancanvas.mod.project.OceanCanvasP1W4Service.formatBytes(r.projected365Days())+" ["+r.confidence()+"].");action="scan";}
            else if(action.equals("upgrade_accept")){if(!hasAdminPermission(player))throw new IllegalArgumentException("upgrade baseline acceptance requires Ocean Canvas admin permission");var r=net.oceancanvas.mod.project.OceanCanvasP1W4Service.acceptUpgradeBaseline(world);tell(player,"Upgrade baseline accepted for "+r.current().minecraftVersion()+".");action="scan";}
            else if(action.startsWith("maintenance_complete:")){var m=net.oceancanvas.mod.project.OceanCanvasP1W4Service.completeMaintenance(world,rawAction.substring("maintenance_complete:".length()));tell(player,"Maintenance item completed; next due "+java.time.Instant.ofEpochMilli(m.nextDueAt())+".");action="scan";}
            else if(action.startsWith("context_region:")){String name=rawAction.substring("context_region:".length());var z=net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(name);if(z==null)throw new IllegalArgumentException("unknown region");var b=z.bounds();net.oceancanvas.mod.project.OceanCanvasP1W4Service.recordRecentContext(world,"REGION",z.name(),z.name(),(b.minX()+b.maxX())/2,(b.minZ()+b.maxZ())/2);action="scan";}
            else if(action.startsWith("context_project:")){String id=rawAction.substring("context_project:".length());var p=net.oceancanvas.mod.project.OceanCanvasWorkspaceData.get(world).project(id);if(p==null)throw new IllegalArgumentException("unknown project");int x=player.blockPosition().getX(),z=player.blockPosition().getZ();if(!p.regionName().isBlank()){var rz=net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones.get(world).zoneByName(p.regionName());if(rz!=null){var b=rz.bounds();x=(b.minX()+b.maxX())/2;z=(b.minZ()+b.maxZ())/2;}}net.oceancanvas.mod.project.OceanCanvasP1W4Service.recordRecentContext(world,"PROJECT",p.id(),p.name(),x,z);action="scan";}
            else if(action.startsWith("context_plan:")){String id=rawAction.substring("context_plan:".length());var o=net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(id);if(o==null)throw new IllegalArgumentException("unknown Plan object");int x=player.blockPosition().getX(),z=player.blockPosition().getZ();if(!o.points().isEmpty()){x=o.points().get(0).x();z=o.points().get(0).z();}net.oceancanvas.mod.project.OceanCanvasP1W4Service.recordRecentContext(world,"PLAN",o.id(),o.name(),x,z);action="scan";}
            else if(action.startsWith("session_generate:")){if(!hasProjectManagePermission(player))throw new IllegalArgumentException("session planning requires project-management permission");int minutes=Integer.parseInt(rawAction.substring("session_generate:".length()));var plan=net.oceancanvas.mod.project.OceanCanvasP1W4Service.generateSessionPlan(player,minutes);tell(player,"Session plan generated with "+plan.taskIds().size()+" task(s) for "+plan.availableMinutes()+" minutes.");action="scan";}
            else if(action.startsWith("session_action:")){if(!hasProjectManagePermission(player))throw new IllegalArgumentException("session planning requires project-management permission");String[] f=rawAction.substring("session_action:".length()).split("\\|",2);if(f.length!=2)throw new IllegalArgumentException("session action requires id|action");var plan=net.oceancanvas.mod.project.OceanCanvasP1W4Service.advanceSessionPlan(world,f[0],f[1]);tell(player,"Session plan is now "+plan.state()+" at item "+plan.cursor()+"/"+plan.taskIds().size()+".");action="scan";}
            else if(action.startsWith("review_add:")){if(!hasProjectManagePermission(player))throw new IllegalArgumentException("review board changes require project-management permission");String[] f=rawAction.substring("review_add:".length()).split("\\|",4);if(f.length<3)throw new IllegalArgumentException("review add requires TYPE|id|label[|comment]");var r=net.oceancanvas.mod.project.OceanCanvasP1W4Service.addReview(world,f[0],f[1],f[2],player.getGameProfile().name(),f.length>3?f[3]:"");tell(player,"Review item created: "+r.label()+".");action="scan";}
            else if(action.startsWith("review_decide:")){if(!hasProjectManagePermission(player))throw new IllegalArgumentException("review decisions require project-management permission");String[] f=rawAction.substring("review_decide:".length()).split("\\|",3);if(f.length<2)throw new IllegalArgumentException("review decision requires id|state[|comment]");var r=net.oceancanvas.mod.project.OceanCanvasP1W4Service.decideReview(world,f[0],f[1],player.getGameProfile().name(),f.length>2?f[2]:"");tell(player,"Review '"+r.label()+"' → "+r.state()+".");action="scan";}
            else if(action.startsWith("retire_project:")){if(!hasProjectManagePermission(player))throw new IllegalArgumentException("retirement requires project-management permission");String[] f=rawAction.substring("retire_project:".length()).split("\\|",3);if(f.length<2)throw new IllegalArgumentException("retirement requires projectId|mode[|note]");var r=net.oceancanvas.mod.project.OceanCanvasP1W4Service.retireProject(world,f[0],f[1],player.getGameProfile().name(),f.length>2?f[2]:"");tell(player,"Retirement workflow: "+r.state()+" · "+r.mode()+"."+(r.state().equals("AWAITING_CONFIRMATION")?" Use the normal Restore dry-run/confirmation for the linked Region.":""));broadcastToAll(world.getServer());action="scan";}
            else if(action.equals("datapack_accept")){var st=net.oceancanvas.mod.project.OceanCanvasP1W3Service.acceptDatapackBaseline(world);tell(player,"Accepted current datapack/worldgen baseline "+st.currentSignature().substring(0,12)+".");action="scan";}
            else if(action.equals("boundary_drift")){var r=net.oceancanvas.mod.project.OceanCanvasP1W3Service.boundaryDriftAudit(world);tell(player,"Boundary drift audit: "+r.suspiciousChunks()+" suspicious chunk(s) across "+r.checkedRingChunks()+" outside-ring checks.",r.suspiciousChunks()>0);action="scan";}
            else if(action.equals("poi_integrity")){var r=net.oceancanvas.mod.project.OceanCanvasP1W3Service.poiIntegrity(world,256);tell(player,"POI/structure integrity: "+r.suspicious()+" suspicious modified chunk(s), "+r.unloaded()+" unloaded/unverified.",r.suspicious()>0);action="scan";}
            else if(action.startsWith("incident_state:")){String[] f=rawAction.split(":",3);if(f.length!=3)throw new IllegalArgumentException("incident_state needs id and state");boolean ok=net.oceancanvas.mod.project.OceanCanvasP1W3Data.get(world).setIncidentState(f[1],f[2]);tell(player,ok?"Health incident updated.":"Health incident not found.",!ok);action="scan";}
            else if(action.startsWith("recipe_add:")){String body=rawAction.substring("recipe_add:".length());int bar=body.indexOf('|');if(bar<1)throw new IllegalArgumentException("recipe format is name|STEP>STEP");var r=net.oceancanvas.mod.project.OceanCanvasMaintenanceRecipeService.add(world,body.substring(0,bar),body.substring(bar+1));tell(player,"Created maintenance recipe "+r.name()+" with "+r.steps().size()+" step(s).");action="scan";}
            else if(action.startsWith("recipe_run:")){tell(player,net.oceancanvas.mod.project.OceanCanvasMaintenanceRecipeService.run(world,rawAction.substring("recipe_run:".length())));action="scan";}
            else if(action.startsWith("recipe_resume:")){tell(player,net.oceancanvas.mod.project.OceanCanvasMaintenanceRecipeService.resumeAfterConfirmedStep(world,rawAction.substring("recipe_resume:".length())));action="scan";}
            else if(action.startsWith("recipe_remove:")){boolean ok=net.oceancanvas.mod.project.OceanCanvasMaintenanceRecipeService.remove(world,rawAction.substring("recipe_remove:".length()));tell(player,ok?"Maintenance recipe removed.":"Recipe not found.",!ok);action="scan";}
            else if(action.startsWith("snapshot:")){String[] f=rawAction.split(":",3);if(f.length<2)throw new IllegalArgumentException("snapshot requires region");var snap=net.oceancanvas.mod.project.OceanCanvasP1W3Service.captureRegionSnapshot(world,f[1],f.length>2?f[2]:"Manual snapshot");tell(player,"Captured world-state snapshot "+snap.label()+" for "+snap.scope()+"." );action="scan";}
            else if(action.startsWith("milestone_capture:")){String[] f=rawAction.split(":",3);if(f.length!=3)throw new IllegalArgumentException("milestone capture requires project and milestone");var m=net.oceancanvas.mod.project.OceanCanvasP1W3Service.captureMilestone(world,f[1],f[2]);tell(player,"Milestone state captured. "+m.summary());action="scan";}
            else if(action.startsWith("terraform_estimate:")){String[] f=rawAction.split(":",3);if(f.length!=3)throw new IllegalArgumentException("terraform estimate requires object and target Y");var e=net.oceancanvas.mod.project.OceanCanvasP1W3Service.terraformEstimate(world,f[1],Integer.parseInt(f[2]));tell(player,"Terraform estimate: add ~"+e.addBlocks()+", remove ~"+e.removeBlocks()+" block-column units across "+e.sampledColumns()+" samples ["+e.confidence()+"].");action="scan";}
            else if(action.startsWith("verify_project:")){var r=net.oceancanvas.mod.project.OceanCanvasP1W3Service.verifyProject(world,rawAction.substring("verify_project:".length()));tell(player,"Build verification: "+r.objectsChecked()+" implemented object(s), "+r.deviations()+" unresolved deviation(s), "+r.unverified()+" unverified.",r.deviations()>0);action="scan";}
            else if(action.startsWith("deviation_state:")){String[] f=rawAction.split(":",5);if(f.length<4)throw new IllegalArgumentException("deviation state requires project, object and classification");boolean ok=net.oceancanvas.mod.project.OceanCanvasP1W3Service.classifyDeviation(world,f[1],f[2],f[3],f.length>4?f[4]:"");tell(player,ok?"Deviation classified "+f[3]+".":"Invalid deviation classification.",!ok);action="scan";}
        } catch(RuntimeException ex){ tell(player,"Ocean Canvas action failed: "+ex.getMessage(),true); return; }
        if(action.startsWith("repair_metadata:")){
            String token=rawAction.substring("repair_metadata:".length());
            var preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.metadataRepair(world);
            if(preview.blocked()){tell(player,preview.summary(),true);return;}
            if(!net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.tokenMatchesMetadataRepair(world,token)){tell(player,"Metadata-repair preview is stale. Run/review the dry run again.",true);return;}
            action="repair_metadata";
        }
        if(action.startsWith("repair_link:")){
            String[] parts=rawAction.split(":",3);
            if(parts.length!=3){tell(player,"Invalid workflow-link repair request.",true);return;}
            String result=net.oceancanvas.mod.project.OceanCanvasAssetIntegrityService.repairStaleReference(world,parts[1],parts[2]);
            tell(player,"Workflow link repair: "+result);
            broadcastToAll(world.getServer());
            action="scan";
        }
        if(!"scan".equals(action)&&!"repair_metadata".equals(action)&&!"diagnostic_bundle".equals(action)&&!"stewardship_report".equals(action)&&!"canary_ack".equals(action)){tell(player,"Unknown Health action.",true);return;}
        if("canary_ack".equals(action)){tell(player,net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryData.get(world).acknowledgeLatestFailure(player.getGameProfile().name()));action="scan";}
        if("stewardship_report".equals(action)){
            try{var report=net.oceancanvas.mod.project.OceanCanvasStewardshipReportService.write(world,"manual health action");tell(player,report.summary()+" ("+report.bytes()+" bytes).",false);action="scan";}
            catch(Exception ex){tell(player,"Stewardship report failed: "+ex.getMessage(),true);return;}
        }
        if("diagnostic_bundle".equals(action)){
            try{var bundle=net.oceancanvas.mod.project.OceanCanvasDiagnosticBundle.create(world);tell(player,"Diagnostic bundle "+bundle.supportCode()+" written to "+bundle.file()+" ("+bundle.bytes()+" bytes).",false);}
            catch(Exception ex){tell(player,"OC-D001: Diagnostic export failed: "+ex.getMessage(),true);}
            return;
        }
        if("repair_metadata".equals(action)){
            var rr=net.oceancanvas.mod.project.OceanCanvasDeepHealthService.repairMetadata(world);
            tell(player,"Health metadata repair: "+rr.repaired()+" repaired, "+rr.skipped()+" skipped. "+rr.detail());
        }
        net.oceancanvas.mod.project.OceanCanvasP1W3Service.refreshHealthInbox(world);
        net.oceancanvas.mod.project.OceanCanvasP1W4Service.refreshRetirements(world);
        net.oceancanvas.mod.project.OceanCanvasP1W4Service.refreshMaintenance(world);
        var report=net.oceancanvas.mod.project.OceanCanvasDeepHealthService.scan(world,64);
        StringBuilder out=new StringBuilder();
        out.append("S\t").append(report.explicitStates()).append('\t').append(report.healthyExplicit()).append('\t')
                .append(report.mismatches()).append('\t').append(report.legacyUnverified()).append('\t').append(report.truncated());
        for(var f:report.findings()){
            out.append('\n').append("F\t").append(f.chunkX()).append('\t').append(f.chunkZ()).append('\t')
                    .append(f.classification()).append('\t').append(f.terrainState()).append('\t').append(f.processed()).append('\t')
                    .append(f.repairable()).append('\t').append(b64(f.detail()));
        }
        var links=net.oceancanvas.mod.project.OceanCanvasAssetIntegrityService.scan(world,64);
        out.append('\n').append("L\t").append(links.projects()).append('\t').append(links.tasks()).append('\t')
                .append(links.terrainAssets()).append('\t').append(links.planningObjects()).append('\t').append(links.issues()).append('\t').append(links.truncated());
        for(var issue:links.findings())out.append('\n').append("I\t").append(issue.kind()).append('\t').append(b64(issue.id())).append('\t').append(b64(issue.detail()));
        var score=net.oceancanvas.mod.project.OceanCanvasWorldHealthScorecard.snapshot(world);
        for(var c:score.components()) out.append('\n').append("W\t").append(c.id()).append('\t').append(b64(c.label())).append('\t')
                .append(c.state().name()).append('\t').append(b64(c.detail()));
        for(var c:net.oceancanvas.mod.diagnostic.OceanCanvasStallWatchdog.causeMatrix()) out.append('\n').append("D\t").append(c.id()).append('\t')
                .append(b64(c.owner())).append('\t').append(c.state()).append('\t').append(c.ageSeconds()).append('\t').append(b64(c.detail()));
        var gate=net.oceancanvas.mod.project.OceanCanvasWorkflowGateService.evaluate(world,"HEALTH");
        out.append('\n').append("G\t").append(gate.pass()).append('\t').append(b64(gate.summary()));
        var owner=net.oceancanvas.mod.project.OceanCanvasOperationOwnership.snapshot(world);
        out.append('\n').append("O\t").append(owner.active()).append('\t').append(b64(owner.kind())).append('\t').append(b64(owner.requesterDisplay())).append('\t').append(b64(owner.authority()));
        var tickets=net.oceancanvas.mod.project.OceanCanvasTicketOwnershipInspector.snapshot(world,8);
        out.append('\n').append("T\t").append(tickets.total()).append('\t').append(tickets.oldestMillis());
        for(var t:tickets.entries())out.append('\n').append("K\t").append(b64(t.pool())).append('\t').append(t.chunkX()).append('\t').append(t.chunkZ()).append('\t').append(t.ageMillis()).append('\t').append(b64(t.owner())).append('\t').append(b64(t.purpose())).append('\t').append(b64(t.releaseCondition()));
        var canary=net.oceancanvas.mod.project.OceanCanvasBoundaryCanaryData.get(world).latestResult();
        if(canary!=null)out.append('\n').append("C\t").append(canary.state()).append('\t').append(canary.checked()).append('\t').append(canary.changed()).append('\t').append(canary.skippedUnloaded()).append('\t').append(b64(canary.detail()));
        var p1=net.oceancanvas.mod.project.OceanCanvasP1W3Data.get(world);
        var dp=net.oceancanvas.mod.project.OceanCanvasP1W3Service.datapackStatus(world);
        out.append('\n').append("B\t").append(dp.baselineRecorded()).append('\t').append(dp.changed()).append('\t').append(dp.currentSignature()).append('\t').append(dp.baselineSignature()).append('\t').append(b64(dp.detail()));
        out.append('\n').append("V\t").append(b64(net.oceancanvas.mod.project.OceanCanvasP1W3Service.vanillaBaselineStatus(world,4)));
        for(var q:net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener.pregenTargetCausalitySnapshot(world,8))out.append('\n').append("Q\t").append(q.chunkX()).append('\t').append(q.chunkZ()).append('\t').append(q.lane()).append('\t').append(q.cause()).append('\t').append(q.ageMillis()).append('\t').append(q.stale()).append('\t').append(b64(q.dependency()));
        for(var lg:net.oceancanvas.mod.project.OceanCanvasP1W3Service.legacyGroups(world).stream().limit(8).toList())out.append('\n').append("J\t").append(b64(lg.generationVersion())).append('\t').append(lg.chunks()).append('\t').append(lg.confidence());
        int ei=0;for(var e:p1.incidents()){if(ei++>=12)break;out.append('\n').append("E\t").append(e.id()).append('\t').append(e.severity()).append('\t').append(e.kind()).append('\t').append(e.confidence()).append('\t').append(e.state()).append('\t').append(b64(e.subject())).append('\t').append(b64(e.detail()));}
        for(var r:p1.recipes())out.append('\n').append("R\t").append(r.id()).append('\t').append(b64(r.name())).append('\t').append(b64(String.join(" > ",r.steps())));
        var rr=p1.recipeRun();if(rr!=null)out.append('\n').append("Y\t").append(rr.recipeId()).append('\t').append(rr.stepIndex()).append('\t').append(rr.state()).append('\t').append(b64(rr.detail()));
        int hi=0;for(var ce:p1.chunks()){if(hi++>=16)break;out.append('\n').append("H\t").append(net.minecraft.world.level.ChunkPos.getX(ce.chunkKey())).append('\t').append(net.minecraft.world.level.ChunkPos.getZ(ce.chunkKey())).append('\t').append(b64(ce.biomeId())).append('\t').append(ce.biomeProvenance()).append('\t').append(b64(ce.generationVersion())).append('\t').append(ce.vanillaBaseline()).append('\t').append(Long.toUnsignedString(ce.vanillaFingerprint())).append('\t').append(b64(ce.biomeDetail()));}
        net.oceancanvas.mod.project.OceanCanvasP1W3Service.syncStructureEvidence(world);int si=0;for(var st:p1.structures()){if(si++>=8)break;out.append('\n').append("U\t").append(st.id()).append('\t').append(st.kind()).append('\t').append(st.provenance()).append('\t').append(st.minX()).append('\t').append(st.minZ()).append('\t').append(st.maxX()).append('\t').append(st.maxZ()).append('\t').append(b64(st.detail()));}
        int di=0;for(var d:p1.deviations()){if(di++>=8)break;out.append('\n').append("X\t").append(d.id()).append('\t').append(d.projectId()).append('\t').append(d.objectId()).append('\t').append(d.classification()).append('\t').append(b64(d.note()));}
        int ni=0;for(var n:p1.snapshots()){if(ni++>=6)break;out.append('\n').append("N\t").append(n.id()).append('\t').append(b64(n.label())).append('\t').append(b64(n.scope())).append('\t').append(n.epochMillis()).append('\t').append(n.authoritative()).append('\t').append(n.truncated()).append('\t').append(b64(n.packedChunks()));}
        var recap=p1.recaps().stream().findFirst().orElse(null);if(recap!=null)out.append('\n').append("Z\t").append(recap.startedAt()).append('\t').append(recap.endedAt()).append('\t').append(b64(recap.summary()));
        out.append('\n').append("CF\t").append(b64(net.oceancanvas.mod.project.OceanCanvasP1W3Service.confidenceSummary(world)));
        var p4=net.oceancanvas.mod.project.OceanCanvasP1W4Data.get(world);
        var scale4=net.oceancanvas.mod.project.OceanCanvasP1W4Service.currentScale(world);
        out.append('\n').append("SC\t").append(scale4.definedChunks()).append('\t').append(scale4.reservedChunks()).append('\t').append(scale4.protectedChunks()).append('\t').append(scale4.canvasChunks()).append('\t').append(scale4.restoredChunks()).append('\t').append(scale4.modifiedChunks()).append('\t').append(scale4.untouchedEstimate()).append('\t').append(b64(scale4.detail()));
        var storage4=net.oceancanvas.mod.project.OceanCanvasP1W4Service.storageForecast(world);
        out.append('\n').append("ST\t").append(storage4.current().epochMillis()).append('\t').append(storage4.current().totalBytes()).append('\t').append(storage4.current().oceanCanvasBytes()).append('\t').append(storage4.dailyGrowth()).append('\t').append(storage4.projected30Days()).append('\t').append(storage4.projected365Days()).append('\t').append(storage4.confidence()).append('\t').append(b64(storage4.detail()));
        int rti=0;for(var r:p4.retirements()){if(rti++>=8)break;out.append('\n').append("RT\t").append(r.id()).append('\t').append(r.subjectType()).append('\t').append(b64(r.subjectId())).append('\t').append(r.mode()).append('\t').append(r.state()).append('\t').append(b64(r.requestedBy())).append('\t').append(b64(r.note()));}
        int rvi=0;for(var r:p4.reviews()){if(rvi++>=12)break;out.append('\n').append("RV\t").append(r.id()).append('\t').append(r.subjectType()).append('\t').append(b64(r.subjectId())).append('\t').append(b64(r.label())).append('\t').append(r.state()).append('\t').append(b64(r.author())).append('\t').append(b64(r.comment()));}
        var session4=p4.activeSessionPlan();if(session4!=null)out.append('\n').append("SP\t").append(session4.id()).append('\t').append(b64(session4.label())).append('\t').append(session4.availableMinutes()).append('\t').append(session4.state()).append('\t').append(session4.cursor()).append('\t').append(b64(String.join(",",session4.taskIds()))).append('\t').append(b64(session4.rationale()));
        int rci=0;for(var c:p4.contexts()){if(rci++>=12)break;out.append('\n').append("RC\t").append(c.id()).append('\t').append(c.type()).append('\t').append(b64(c.subjectId())).append('\t').append(b64(c.label())).append('\t').append(c.x()).append('\t').append(c.z()).append('\t').append(c.updatedAt());}
        for(var m:p4.maintenance())out.append('\n').append("MT\t").append(m.id()).append('\t').append(b64(m.label())).append('\t').append(m.cadenceDays()).append('\t').append(m.lastCompletedAt()).append('\t').append(m.nextDueAt()).append('\t').append(m.state()).append('\t').append(b64(m.note()));
        var upgrade4=net.oceancanvas.mod.project.OceanCanvasP1W4Service.upgradeDiff(world);out.append('\n').append("UD\t").append(upgrade4.state()).append('\t').append(upgrade4.confidence()).append('\t').append(b64(upgrade4.current().minecraftVersion())).append('\t').append(b64(upgrade4.baseline()==null?"":upgrade4.baseline().minecraftVersion())).append('\t').append(b64(String.join(" | ",upgrade4.changes()))).append('\t').append(b64(upgrade4.current().detail()));
        var graph4=net.oceancanvas.mod.project.OceanCanvasP1W4Service.layerGraph(world);out.append('\n').append("LG\t").append(graph4.nodes().size()).append('\t').append(graph4.edges().size()).append('\t').append(graph4.missingReferences()).append('\t').append(b64(graph4.detail()));int ge=0;for(var e:graph4.edges()){if(ge++>=16)break;out.append('\n').append("LE\t").append(b64(e.from())).append('\t').append(b64(e.to())).append('\t').append(b64(e.relation()));}
        var perms4=net.oceancanvas.mod.project.OceanCanvasP1W4Service.permissions(player);out.append('\n').append("PM\t").append(perms4.viewPlans()).append('\t').append(perms4.editPlans()).append('\t').append(perms4.manageProjects()).append('\t').append(perms4.destructiveOps()).append('\t').append(perms4.diagnostics()).append('\t').append(perms4.admin());
        ServerPlayNetworking.send(player,new OceanCanvasHealthResponsePayload(out.toString()));
    }

    private static void handleAnalysis(OceanCanvasAnalysisRequestPayload payload, ServerPlayer player) {
        if (!hasPlanningViewPermission(player)) { tell(player,"You do not have permission to run Ocean Canvas planning analysis.",true); return; }
		int samples=Math.max(8,Math.min(256,payload.samples()));
		var profile=net.oceancanvas.mod.project.OceanCanvasTerrainAnalysisService.crossSection(
				player.level(),payload.x0(),payload.z0(),payload.x1(),payload.z1(),samples);
		double distance=Math.hypot(payload.x1()-payload.x0(),payload.z1()-payload.z0());
		var travel=net.oceancanvas.mod.project.OceanCanvasTerrainAnalysisService.travel(distance);
		StringBuilder packed=new StringBuilder();
		packed.append("x0=").append(payload.x0()).append(";z0=").append(payload.z0())
				.append(";x1=").append(payload.x1()).append(";z1=").append(payload.z1())
				.append(";distance=").append(String.format(java.util.Locale.ROOT,"%.1f",distance))
				.append(";walk=").append(String.format(java.util.Locale.ROOT,"%.1f",travel.walkMinutes()))
				.append(";horse=").append(String.format(java.util.Locale.ROOT,"%.1f",travel.horseMinutes()))
				.append(";boat=").append(String.format(java.util.Locale.ROOT,"%.1f",travel.boatMinutes()))
				.append(";elytra=").append(String.format(java.util.Locale.ROOT,"%.1f",travel.elytraMinutes()));
		for(var point:profile) packed.append(";p=").append((int)Math.round(point.distance())).append(',').append(point.surfaceY());
		ServerPlayNetworking.send(player,new OceanCanvasAnalysisResponsePayload(packed.toString()));
	}

	private static void handleProjectStage(OceanCanvasProjectStageRequestPayload payload, ServerPlayer player) {
		if (!hasProjectManagePermission(player)) { tell(player, "You do not have permission to change project stages.", true); return; }
		ServerLevel world = player.level();
		OceanCanvasPlayerZones.Zone zone = OceanCanvasPlayerZones.get(world).zoneByName(payload.name());
		if (zone == null) { tell(player, "No region named '" + payload.name() + "'.", true); return; }
		if (zone.owner() != null && !zone.owner().equals(player.getUUID()) && !canOverrideOwnership(player)) {
			tell(player, "Region '" + zone.name() + "' is owned by another player.", true); return;
		}
		var stage = net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.parse(payload.stage());
		net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).setRegionStage(zone.name(), stage);
		tell(player, stage == net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.ARCHIVED
				? "Archived region '" + zone.name() + "'. Destructive edits are now locked."
				: "Unlocked region '" + zone.name() + "' to stage " + stage.name().replace('_', ' ') + ".");
		broadcastToAll(world.getServer());
	}

	private static void handleProjectEdit(OceanCanvasProjectEditRequestPayload payload, ServerPlayer player) {
		if (!hasProjectManagePermission(player)) { tell(player, "You do not have permission to edit project metadata.", true); return; }
		ServerLevel world = player.level();
		OceanCanvasPlayerZones.Zone zone = OceanCanvasPlayerZones.get(world).zoneByName(payload.name());
		if (zone == null) { tell(player, "No region named '" + payload.name() + "'.", true); return; }
		if (zone.owner() != null && !zone.owner().equals(player.getUUID()) && !canOverrideOwnership(player)) {
			tell(player, "Region '" + zone.name() + "' is owned by another player.", true); return;
		}
		var data = net.oceancanvas.mod.project.OceanCanvasProjectData.get(world);
		String key = payload.key() == null ? "" : payload.key().trim().toLowerCase(java.util.Locale.ROOT);
		String value = payload.value() == null ? "" : payload.value().trim();
		switch (key) {
			case "stage" -> data.setRegionStage(zone.name(), net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.parse(value));
			case "notes" -> {
				if (value.length() > 512) { tell(player, "Region notes are limited to 512 characters.", true); return; }
				data.setRegionNotes(zone.name(), value);
			}
			case "template" -> {
				if (value.isBlank()) { data.setRegionTemplate(zone.name(), ""); break; }
				var template = data.templates().stream().filter(t -> t.id().equals(value)).findFirst().orElse(null);
				if (template == null) { tell(player, "Unknown project template: " + value, true); return; }
				if (!template.biomeOverride().isBlank()) {
					String problem = validateBiomeId(world, template.biomeOverride());
					if (problem != null) { tell(player, "Template biome is unavailable: " + problem, true); return; }
				}
				var zones = OceanCanvasPlayerZones.get(world);
				var packedRules = OceanCanvasZoneSyncPayload.parseRules(template.structureRules());
				for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) {
					String raw = packedRules.getOrDefault(kind.id(), "INHERIT");
					net.oceancanvas.mod.config.StructureOverride override;
					try { override = net.oceancanvas.mod.config.StructureOverride.valueOf(raw); }
					catch (IllegalArgumentException ex) { override = net.oceancanvas.mod.config.StructureOverride.INHERIT; }
					zones.setStructureOverride(zone.name(), kind, override, player.getUUID(), canOverrideOwnership(player));
				}
				zones.setBiomeOverride(zone.name(), template.biomeOverride().isBlank() ? null : template.biomeOverride(), player.getUUID(), canOverrideOwnership(player));
				zones.setSuppressHostileMobs(zone.name(), template.suppressHostileMobs(), player.getUUID(), canOverrideOwnership(player));
				zones.setColor(zone.name(), template.color().isBlank() ? null : template.color(), player.getUUID(), canOverrideOwnership(player));
				zones.setEnabled(zone.name(), template.protectedByDefault(), player.getUUID(), canOverrideOwnership(player));
				data.setRegionTemplate(zone.name(), template.id());
			}
			case "template_save" -> {
				String display = value.isBlank() ? zone.name() + " Template" : value;
				if (display.length() > 64) { tell(player, "Template names are limited to 64 characters.", true); return; }
				String id = display.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
				if (id.isBlank()) id = "region_template";
				String base=id; int suffix=2;
				while (true) {
					boolean exists=false; for (var t : data.templates()) if (t.id().equals(id)) { exists=true; break; }
					if (!exists) break;
					id=base+"_"+(suffix++);
				}
				data.putTemplate(new net.oceancanvas.mod.project.OceanCanvasProjectData.RegionTemplate(id, display, zone.protectedNow(), packRules(zone), zone.biomeOverride()==null?"":zone.biomeOverride(), zone.suppressHostileMobs(), zone.color()==null?"":zone.color()));
				data.setRegionTemplate(zone.name(), id);
				tellOnScreen(player, "Saved Region template: " + display + ".");
			}
			case "current" -> data.setCurrentProject(value.equalsIgnoreCase("false") ? "" : zone.name());
			default -> { tell(player, "Unknown project edit: " + key, true); return; }
		}
		tellOnScreen(player, "Saved project metadata for " + zone.name() + ".");
		broadcastToAll(world.getServer());
	}

    private static String planningGeometryPacked(java.util.List<net.oceancanvas.mod.project.OceanCanvasPlanningData.Point> pts){var b=new StringBuilder();for(var p:pts){if(b.length()>0)b.append(';');b.append(p.x()).append(',').append(p.z());}return b.toString();}
    private static String sha256Hex(String raw){try{var md=java.security.MessageDigest.getInstance("SHA-256");byte[] d=md.digest((raw==null?"":raw).getBytes(java.nio.charset.StandardCharsets.UTF_8));var b=new StringBuilder(64);for(byte x:d)b.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return b.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
	private static void handleWorkspaceEdit(OceanCanvasWorkspaceEditRequestPayload payload, ServerPlayer player) {
		if (!hasProjectManagePermission(player)) { tell(player, "You do not have permission to edit project workspace data.", true); return; }
		ServerLevel world = player.level();
		String action = payload.action() == null ? "" : payload.action().trim().toLowerCase(java.util.Locale.ROOT);
        if(action.startsWith("harness_")){
            if(!canOverrideOwnership(player)){tell(player,"Harness/fault tools require Ocean Canvas admin permission.",true);return;}
            try{
                String text;String stem;String feature;String kind;boolean pass=true;long checksum=0L;double metric=Double.NaN;
                switch(action){
                    case "harness_fault" -> {
                        String id=payload.arg1()==null?"BASELINE":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
                        var run=net.oceancanvas.mod.diagnostic.OceanCanvasHarnessCore.runSyntheticLoad(id,512);
                        text=net.oceancanvas.mod.project.OceanCanvasHarnessService.simulatorProfileText(id,512);stem="fault-"+id.toLowerCase(java.util.Locale.ROOT);feature="OC-F047";kind="SYNTHETIC_FAULT";pass=run.passed();checksum=run.checksum();metric=run.completed();
                    }
                    case "harness_torture" -> {var run=net.oceancanvas.mod.diagnostic.OceanCanvasHarnessCore.runSaveQuitTorture();text=net.oceancanvas.mod.project.OceanCanvasHarnessService.saveQuitTortureText();stem="save-quit-torture";feature="OC-F227";kind="SAVE_QUIT_TORTURE";pass=run.passed();checksum=run.checksum();metric=run.cases();}
                    case "harness_crash" -> {var run=net.oceancanvas.mod.diagnostic.OceanCanvasHarnessCore.runCrashConsistencyMatrix();text=net.oceancanvas.mod.project.OceanCanvasHarnessService.crashConsistencyText();stem="crash-consistency";feature="OC-F232";kind="CRASH_CONSISTENCY";pass=run.passed();checksum=run.checksum();metric=run.cases();}
                    case "harness_matrix" -> {var rows=net.oceancanvas.mod.project.OceanCanvasHarnessService.recordCompatibilityMatrix(world);long verified=rows.stream().filter(r->!"UNVERIFIED".equals(r.status())).count();tell(player,"Compatibility matrix recorded for this exact Minecraft/Fabric/mod signature: "+verified+"/"+rows.size()+" rows have evidence.");ServerPlayNetworking.send(player,buildProjectPayload(world.getServer()));return;}
                    case "harness_compatibility" -> {var run=net.oceancanvas.mod.project.OceanCanvasHarnessService.recordCompatibility(world);tell(player,"Compatibility fingerprint "+run.signature()+" · "+run.verdict());ServerPlayNetworking.send(player,buildProjectPayload(world.getServer()));return;}
                    case "harness_boundary" -> {var run=net.oceancanvas.mod.diagnostic.OceanCanvasHarnessCore.runBoundaryFuzz(net.oceancanvas.mod.diagnostic.OceanCanvasHarnessCore.DEFAULT_SEED,5000);text=net.oceancanvas.mod.project.OceanCanvasHarnessService.boundaryText(5000);stem="boundary-fuzz";feature="OC-F231";kind="BOUNDARY_FUZZ";pass=run.passed();checksum=run.checksum();metric=run.executedCases();}
                    default -> {tell(player,"Unknown harness action: "+action,true);return;}
                }
                var report=net.oceancanvas.mod.project.OceanCanvasHarnessService.writeReport(world,stem,text);
                net.oceancanvas.mod.diagnostic.OceanCanvasHarnessData.get(world).record(feature,kind,pass?"PASS":"FAIL",checksum,metric,"",report.toString());
                tell(player,(pass?"PASS ":"FAIL ")+kind.replace('_',' ')+" · report "+report,!pass);
            }catch(Exception ex){tell(player,"Harness action failed: "+ex.getMessage(),true);}
            return;
        }
        if(action.startsWith("queue_")){
            String result=net.oceancanvas.mod.pregen.OceanCanvasPregenQueue.action(player,action,payload.id(),payload.arg1());
            if(!action.equals("queue_status"))tell(player,result);
            ServerPlayNetworking.send(player,new OceanCanvasPregenQueuePayload(
                    net.oceancanvas.mod.pregen.OceanCanvasPregenQueue.packed(world)));
            return;
        }
		var data = net.oceancanvas.mod.project.OceanCanvasWorkspaceData.get(world);
		try {
			switch (action) {
				case "world_map_mode" -> {data.setWorldMapMode(payload.arg1());tell(player,"World map mode: "+data.worldMapMode().replace('_',' ')+".");}
				case "next_session_task" -> {var item=data.pinTask(payload.id());tell(player,"Pinned '"+item.label()+"' for next session.");}
				case "next_session_location" -> {String[] f=(payload.arg2()==null?"":payload.arg2()).split(",",-1);if(f.length<2)throw new IllegalArgumentException("next-session location requires x,z");int x=Integer.parseInt(f[0]),z=Integer.parseInt(f[1]);String region="";for(var zone:OceanCanvasPlayerZones.get(world).all())if(zone.contains(x,zone.bounds().minY(),z)){region=zone.name();break;}var item=data.pinLocation(payload.arg1(),x,z,region);tell(player,"Pinned '"+item.label()+"' at "+x+", "+z+" for next session.");}
				case "next_session_remove" -> {if(!data.removeNextSession(payload.id()))throw new IllegalArgumentException("unknown next-session item");}
				case "next_session_clear" -> {data.clearNextSession();tell(player,"Cleared next-session queue.");}
				case "task_add" -> {
					String[] pos = payload.arg2().split(",", -1); if (pos.length < 2) throw new IllegalArgumentException("task position requires x,z");
					int x=Integer.parseInt(pos[0]), z=Integer.parseInt(pos[1]); long now=System.currentTimeMillis();
					String title=payload.arg1()==null||payload.arg1().isBlank()?"Task":payload.arg1().trim();
					String region=""; for (var zone : OceanCanvasPlayerZones.get(world).all()) if (zone.contains(x, zone.bounds().minY(), z)) { region=zone.name(); break; }
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(data.newId("task"), title, "", x,z,region,"","PLANNED",1,now,now));
					data.addJournal("Created task: " + title, region, x, z);
				}
				case "task_done", "task_delete" -> {
					var task=data.task(payload.id()); if (task==null) return;
					if (action.equals("task_delete")) data.removeTask(task.id());
					else data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),"COMPLETE",task.priority(),task.createdAt(),System.currentTimeMillis(),task.projectId(),task.parentTaskId(),task.dependencies(),task.checklist(),task.weight(),task.phaseId()));
				}
				case "task_status" -> {
					// Full task list/status editing, promised in the v44 accepted scope but never
					// wired up beyond task_add/task_done/task_delete - see OceanCanvasTaskListScreen.
					var task=data.task(payload.id()); if (task==null) return;
					var status=net.oceancanvas.mod.project.OceanCanvasWorkspaceData.TaskStatus.parse(payload.arg1());
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),status.name(),task.priority(),task.createdAt(),System.currentTimeMillis(),task.projectId(),task.parentTaskId(),task.dependencies(),task.checklist(),task.weight(),task.phaseId()));
				}
				case "task_title" -> {
					var task=data.task(payload.id()); if (task==null) return;
					String title=payload.arg1()==null?"":payload.arg1().trim();
					if(title.isBlank()) throw new IllegalArgumentException("task title cannot be blank");
					if(title.length()>96) throw new IllegalArgumentException("task title is limited to 96 characters");
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),title,task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),task.status(),task.priority(),task.createdAt(),System.currentTimeMillis(),task.projectId(),task.parentTaskId(),task.dependencies(),task.checklist(),task.weight(),task.phaseId()));
				}
				case "task_notes" -> {
					var task=data.task(payload.id()); if (task==null) return;
					String notes=payload.arg1()==null?"":payload.arg1();
					if(notes.length()>512) throw new IllegalArgumentException("task notes are limited to 512 characters");
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),notes,task.x(),task.z(),task.regionName(),task.planningObjectId(),task.status(),task.priority(),task.createdAt(),System.currentTimeMillis(),task.projectId(),task.parentTaskId(),task.dependencies(),task.checklist(),task.weight(),task.phaseId()));
				}
				case "task_priority" -> {
					var task=data.task(payload.id()); if (task==null) return;
					int priority=Integer.parseInt(payload.arg1());
					if(priority<0||priority>3) throw new IllegalArgumentException("task priority must be 0-3");
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),task.status(),priority,task.createdAt(),System.currentTimeMillis(),task.projectId(),task.parentTaskId(),task.dependencies(),task.checklist(),task.weight(),task.phaseId()));
				}
				case "task_parent" -> {
					var task=data.task(payload.id());if(task==null)return;String parent=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);if(parent.equals(task.id()))throw new IllegalArgumentException("task cannot be its own parent");if(!parent.isBlank()){var pt=data.task(parent);if(pt==null)throw new IllegalArgumentException("unknown parent task");if(!java.util.Objects.equals(pt.projectId(),task.projectId()))throw new IllegalArgumentException("parent task must belong to the same project");var seen=new java.util.HashSet<String>();String cur=parent;while(!cur.isBlank()&&seen.add(cur)){if(cur.equals(task.id()))throw new IllegalArgumentException("parent task would create a cycle");var ct=data.task(cur);cur=ct==null?"":ct.parentTaskId();}}
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),task.status(),task.priority(),task.createdAt(),System.currentTimeMillis(),task.projectId(),parent,task.dependencies(),task.checklist(),task.weight(),task.phaseId()));
				}
				case "task_weight" -> {
					var task=data.task(payload.id());if(task==null)return;double weight=Double.parseDouble(payload.arg1());if(weight<0.1||weight>100)throw new IllegalArgumentException("task weight must be 0.1-100");
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),task.status(),task.priority(),task.createdAt(),System.currentTimeMillis(),task.projectId(),task.parentTaskId(),task.dependencies(),task.checklist(),weight,task.phaseId()));
				}
				case "task_project" -> {
					var task=data.task(payload.id()); if(task==null)return;
					String projectId=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);
					if(!projectId.isBlank() && data.project(projectId)==null) throw new IllegalArgumentException("unknown project");
                    String parent=task.parentTaskId(),phase=task.phaseId();var deps=new java.util.ArrayList<String>();
                    if(!java.util.Objects.equals(projectId,task.projectId())){parent="";phase="";for(String dep:task.dependencies()) {var dt=data.task(dep);if(dt!=null&&java.util.Objects.equals(projectId,dt.projectId()))deps.add(dep);}}
                    else deps.addAll(task.dependencies());
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),task.status(),task.priority(),task.createdAt(),System.currentTimeMillis(),projectId,parent,deps,task.checklist(),task.weight(),phase));
				}
				case "task_phase" -> {
                    var task=data.task(payload.id());if(task==null)return;String phase=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);
                    if(!phase.isBlank()){var project=data.project(task.projectId());if(project==null||project.phases().stream().noneMatch(v->v.id().equals(phase)))throw new IllegalArgumentException("unknown phase for this task's project");}
                    data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),task.status(),task.priority(),task.createdAt(),System.currentTimeMillis(),task.projectId(),task.parentTaskId(),task.dependencies(),task.checklist(),task.weight(),phase));
                }
				case "task_dependencies" -> {
					var task=data.task(payload.id()); if(task==null)return;
					java.util.List<String> deps=new java.util.ArrayList<>();
					if(payload.arg1()!=null&&!payload.arg1().isBlank()) for(String dep:payload.arg1().split(",")){String d=dep.trim().toLowerCase(java.util.Locale.ROOT);if(!d.isBlank()&&!d.equals(task.id())&&data.task(d)!=null&&!deps.contains(d))deps.add(d);if(deps.size()>=64)break;}
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(task.id(),task.title(),task.notes(),task.x(),task.z(),task.regionName(),task.planningObjectId(),task.status(),task.priority(),task.createdAt(),System.currentTimeMillis(),task.projectId(),task.parentTaskId(),deps,task.checklist(),task.weight(),task.phaseId()));
				}
				case "task_check_add" -> {
					var task=data.task(payload.id()); if(task==null)return; String text=payload.arg1()==null?"":payload.arg1().trim(); if(text.isBlank())throw new IllegalArgumentException("checklist text cannot be blank");
					var items=new java.util.ArrayList<>(task.checklist()); items.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ChecklistItem(data.newId("item"),text,false));
					data.putTask(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyTask(task,task.status(),items));
				}
				case "task_check_toggle" -> {
					var task=data.task(payload.id()); if(task==null)return; var items=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ChecklistItem>();
					for(var item:task.checklist())items.add(item.id().equals(payload.arg1())?new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ChecklistItem(item.id(),item.text(),!item.complete()):item);
					data.putTask(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyTask(task,task.status(),items));
				}
				case "task_check_remove" -> {
					var task=data.task(payload.id()); if(task==null)return; var items=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ChecklistItem>();
					for(var item:task.checklist())if(!item.id().equals(payload.arg1()))items.add(item);
					data.putTask(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyTask(task,task.status(),items));
				}
				case "project_add" -> {
					long now=System.currentTimeMillis(); String name=payload.arg1()==null||payload.arg1().isBlank()?"Project":payload.arg1().trim(); if(name.length()>96)throw new IllegalArgumentException("project name is limited to 96 characters");
					String id=data.newId("project"); data.putProject(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.WorkProject(id,name,"",java.util.List.of(),java.util.List.of(),payload.arg2(),"PLANNED","custom","",java.util.List.of(),now,now)); data.setActiveWorkProject(id);
				}
				case "project_from_plan" -> {
					var plan=net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(payload.id());
					if(plan==null)throw new IllegalArgumentException("unknown Plan object");
					for(var existing:data.projects()) if(existing.planningObjectIds().contains(plan.id())) {
						data.setActiveWorkProject(existing.id());
						tell(player,"Plan '"+plan.name()+"' is already linked to Project '"+existing.name()+"'; opened the existing Project instead.");
						broadcastToAll(world.getServer());
						return;
					}
					long now=System.currentTimeMillis();
					String region=payload.arg2()==null?"":payload.arg2().trim();
					String name=(payload.arg1()==null||payload.arg1().isBlank()?plan.name():payload.arg1().trim());
					if(name.length()>96)name=name.substring(0,96);
					String projectId=data.newId("project");
					data.putProject(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.WorkProject(projectId,name,"",java.util.List.of(plan.id()),java.util.List.of(),region,"PLANNED","plan","Created from Plan object '"+plan.name()+"'.",java.util.List.of(),now,now));
					int x=player.blockPosition().getX(),z=player.blockPosition().getZ();
					if(!plan.points().isEmpty()){long sx=0,sz=0;for(var pt:plan.points()){sx+=pt.x();sz+=pt.z();}x=(int)Math.round((double)sx/plan.points().size());z=(int)Math.round((double)sz/plan.points().size());}
					String taskId=data.newId("task");
					data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(taskId,"Implement "+plan.name(),"Starter execution task created from the selected Plan object.",x,z,region,plan.id(),"PLANNED",1,now,now,projectId,"",java.util.List.of(),java.util.List.of(),1.0D));
					data.setActiveWorkProject(projectId);
					data.addJournal("Created execution project from Plan: "+plan.name(),region,x,z);
					tell(player,"Created Project '"+name+"' from Plan '"+plan.name()+"' with one Ready starter task.");
				}
				case "project_status" -> {
					var project=data.project(payload.id());if(project==null)return;var st=net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectStatus.parse(payload.arg1());
					data.putProject(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.WorkProject(project.id(),project.name(),project.parentProjectId(),project.planningObjectIds(),project.terrainAssetIds(),project.regionName(),st.name(),project.templateId(),project.notes(),project.milestones(),project.createdAt(),System.currentTimeMillis(),project.phases(),project.blockers(),project.currentTaskId(),project.milestoneRecords()));
				}
				case "project_active" -> data.setActiveWorkProject(payload.id());
				case "project_delete" -> { if(!data.removeProject(payload.id())) throw new IllegalArgumentException("unknown project"); }
				case "project_pipeline_snapshot" -> {var snap=net.oceancanvas.mod.project.OceanCanvasPipelineSnapshotData.get(world).capture(world,payload.id(),payload.arg1());tell(player,"Saved pipeline checkpoint '"+snap.label()+"'.");}
				case "prototype_create" -> {
					if(data.project(payload.id())==null)throw new IllegalArgumentException("unknown project");
					int size=Integer.parseInt(payload.arg2());var bp=player.blockPosition();
					var plot=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).createPrototype(payload.id(),payload.arg1(),bp.getX(),bp.getZ(),size);
					tell(player,"Created "+size+"×"+size+" Prototype Plot '"+plot.name()+"' around your current position. No terrain was changed.");
				}
				case "prototype_status" -> {net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).setPrototypeStatus(payload.arg1(),payload.arg2());tell(player,"Prototype Plot status updated to "+payload.arg2()+".");}
				case "prototype_asset" -> {
					var asset=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(payload.arg2());if(asset==null)throw new IllegalArgumentException("unknown Terrain Asset");
					net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).linkPrototypeAsset(payload.arg1(),payload.arg2());
				}
				case "transition_create" -> {
					var project=data.project(payload.id());if(project==null)throw new IllegalArgumentException("unknown project");if(project.regionName().isBlank())throw new IllegalArgumentException("link a Region before creating a Transition Zone");
					var zone=OceanCanvasPlayerZones.get(world).zoneByName(project.regionName());if(zone==null)throw new IllegalArgumentException("linked Region is unavailable");int feather=Integer.parseInt(payload.arg2());var b=zone.bounds();
					net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).createTransition(project.id(),payload.arg1(),b.minX(),b.minZ(),b.maxX(),b.maxZ(),feather);tell(player,"Created Transition Zone metadata around '"+zone.name()+"'. No terrain was changed.");
				}
				case "transition_status" -> net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).setTransitionStatus(payload.arg1(),payload.arg2());
				case "transition_feather" -> net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).setTransitionFeather(payload.arg1(),Integer.parseInt(payload.arg2()));
				case "transition_layer" -> net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).toggleTransitionLayer(payload.arg1(),payload.arg2());
				case "atlas_route_create" -> {var route=net.oceancanvas.mod.project.OceanCanvasAtlasRouteData.get(world).create(payload.arg1());tell(player,"Created Atlas route '"+route.name()+"'.");}
				case "atlas_route_description" -> {net.oceancanvas.mod.project.OceanCanvasAtlasRouteData.get(world).updateDescription(payload.id(),payload.arg1());tell(player,"Saved Atlas chapter introduction.");}
				case "atlas_route_delete" -> {net.oceancanvas.mod.project.OceanCanvasAtlasRouteData.get(world).delete(payload.id());tell(player,"Deleted Atlas route.");}
				case "atlas_route_add" -> {String[] f=(payload.arg2()==null?"":payload.arg2()).split("\t",-1);if(f.length!=4)throw new IllegalArgumentException("Atlas stop requires target,title,x,z");String title=new String(java.util.Base64.getUrlDecoder().decode(f[1]),java.nio.charset.StandardCharsets.UTF_8);net.oceancanvas.mod.project.OceanCanvasAtlasRouteData.get(world).add(payload.id(),payload.arg1(),f[0],title,Integer.parseInt(f[2]),Integer.parseInt(f[3]));tell(player,"Added '"+title+"' to Atlas route.");}
				case "atlas_route_remove" -> net.oceancanvas.mod.project.OceanCanvasAtlasRouteData.get(world).remove(payload.id(),payload.arg1());
				case "atlas_route_move" -> net.oceancanvas.mod.project.OceanCanvasAtlasRouteData.get(world).move(payload.id(),payload.arg1(),"UP".equalsIgnoreCase(payload.arg2())?-1:1);
				case "project_mission_apply" -> {
					var project=data.project(payload.id());if(project==null)throw new IllegalArgumentException("unknown project");
					var mission=net.oceancanvas.mod.project.OceanCanvasMissionTemplates.byId(payload.arg1());if(mission==null)throw new IllegalArgumentException("unknown mission template");
					var existingTasks=data.tasks().stream().filter(t->project.id().equals(t.projectId())).toList();
					if(!project.phases().isEmpty()||!project.milestoneRecords().isEmpty()||existingTasks.size()>1)throw new IllegalArgumentException("Project already has an execution structure; Mission application will not duplicate or overwrite it");
					int x=player.blockPosition().getX(),z=player.blockPosition().getZ();String planId=project.planningObjectIds().isEmpty()?"":project.planningObjectIds().get(0);
					if(!planId.isBlank()){var plan=net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(planId);if(plan!=null&&!plan.points().isEmpty()){long sx=0,sz=0;for(var pt:plan.points()){sx+=pt.x();sz+=pt.z();}x=(int)Math.round((double)sx/plan.points().size());z=(int)Math.round((double)sz/plan.points().size());}}
					var phases=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase>();var milestones=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone>();String previous="",firstTask="";long now=System.currentTimeMillis();int order=0;
					for(var step:mission.steps()){
						String phaseId=data.newId("phase"),taskId=data.newId("task");phases.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase(phaseId,step.phase(),order==0?"ACTIVE":"PLANNED",order++));
						var checks=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ChecklistItem>();for(String check:step.checklist())checks.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ChecklistItem(data.newId("item"),check,false));
						data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(taskId,step.task(),step.purpose(),x,z,project.regionName(),planId,"PLANNED",1,now,now,project.id(),"",previous.isBlank()?java.util.List.of():java.util.List.of(previous),checks,1.0D,phaseId));if(firstTask.isBlank())firstTask=taskId;
						milestones.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone(data.newId("milestone"),step.phase()+" ready","PLANNED",phaseId,taskId,"Mission gate: "+step.purpose(),0,""));previous=taskId;
					}
					if(!existingTasks.isEmpty()){var t=existingTasks.get(0);String finalPhase=phases.get(phases.size()-1).id();data.putTask(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.GeoTask(t.id(),t.title(),t.notes(),t.x(),t.z(),t.regionName(),t.planningObjectId(),t.status(),t.priority(),t.createdAt(),now,t.projectId(),t.parentTaskId(),previous.isBlank()?t.dependencies():java.util.List.of(previous),t.checklist(),t.weight(),finalPhase));}
					String notes=project.notes()+(project.notes().isBlank()?"":"\n")+"Mission: "+mission.name()+" — "+mission.summary();
					data.putProject(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.WorkProject(project.id(),project.name(),project.parentProjectId(),project.planningObjectIds(),project.terrainAssetIds(),project.regionName(),"ACTIVE",project.templateId(),notes,project.milestones(),project.createdAt(),now,phases,project.blockers(),firstTask,milestones));
					data.recomputeReadyStates();data.setActiveWorkProject(project.id());data.addJournal("Applied Project Mission: "+mission.name(),project.regionName(),x,z);tell(player,"Applied Mission '"+mission.name()+"': "+phases.size()+" phases, "+mission.steps().size()+" tasks, and "+milestones.size()+" milestones created.");
				}
                case "project_phase_add" -> {
                    var project=data.project(payload.id());if(project==null)return;String name=payload.arg1()==null?"":payload.arg1().trim();if(name.isBlank())throw new IllegalArgumentException("phase name cannot be blank");
                    var phases=new java.util.ArrayList<>(project.phases());int order=phases.stream().mapToInt(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase::order).max().orElse(-1)+1;
                    phases.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase(data.newId("phase"),name,"PLANNED",order));data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProject(project,phases,project.blockers(),project.currentTaskId()));
                }
                case "project_phase_status" -> {
                    var project=data.project(payload.id());if(project==null)return;String phaseId=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);String st=payload.arg2()==null?"PLANNED":payload.arg2().trim().toUpperCase(java.util.Locale.ROOT);if(!java.util.Set.of("PLANNED","ACTIVE","COMPLETE","SKIPPED").contains(st))throw new IllegalArgumentException("invalid phase status");
                    var phases=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase>();boolean found=false;for(var v:project.phases()){if(v.id().equals(phaseId)){phases.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase(v.id(),v.name(),st,v.order()));found=true;}else phases.add(v);}if(!found)throw new IllegalArgumentException("unknown phase");data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProject(project,phases,project.blockers(),project.currentTaskId()));
                }
                case "project_phase_rename" -> {
                    var project=data.project(payload.id());if(project==null)return;String phaseId=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);String name=payload.arg2()==null?"":payload.arg2().trim();if(name.isBlank()||name.length()>96)throw new IllegalArgumentException("phase name must be 1-96 characters");var phases=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase>();boolean found=false;for(var v:project.phases()){if(v.id().equals(phaseId)){phases.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase(v.id(),name,v.status(),v.order()));found=true;}else phases.add(v);}if(!found)throw new IllegalArgumentException("unknown phase");data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProject(project,phases,project.blockers(),project.currentTaskId()));
                }
                case "project_phase_move" -> {
                    var project=data.project(payload.id());if(project==null)return;String phaseId=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);int dir="UP".equalsIgnoreCase(payload.arg2())?-1:1;var phases=new java.util.ArrayList<>(project.phases());phases.sort(java.util.Comparator.comparingInt(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase::order));int idx=-1;for(int i=0;i<phases.size();i++)if(phases.get(i).id().equals(phaseId)){idx=i;break;}if(idx<0)throw new IllegalArgumentException("unknown phase");int other=idx+dir;if(other<0||other>=phases.size())return;java.util.Collections.swap(phases,idx,other);var normalized=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase>();for(int i=0;i<phases.size();i++){var v=phases.get(i);normalized.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase(v.id(),v.name(),v.status(),i));}data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProject(project,normalized,project.blockers(),project.currentTaskId()));
                }
                case "project_phase_remove" -> {
                    var project=data.project(payload.id());if(project==null)return;String phaseId=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);for(var task:data.tasks())if(project.id().equals(task.projectId())&&phaseId.equals(task.phaseId()))throw new IllegalArgumentException("reassign tasks before removing this phase");var phases=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase>();boolean found=false;for(var v:project.phases()){if(v.id().equals(phaseId))found=true;else phases.add(v);}if(!found)throw new IllegalArgumentException("unknown phase");for(int i=0;i<phases.size();i++){var v=phases.get(i);phases.set(i,new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectPhase(v.id(),v.name(),v.status(),i));}data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProject(project,phases,project.blockers(),project.currentTaskId()));
                }
                case "project_milestone_create" -> {
                    var project=data.project(payload.id());if(project==null)return;String name=payload.arg1()==null?"":payload.arg1().trim();if(name.isBlank()||name.length()>96)throw new IllegalArgumentException("milestone name must be 1-96 characters");String spec=payload.arg2()==null?"":payload.arg2();String phaseId="",taskId="",notes="",targetDate="";int progress=0;for(String part:spec.split("\\|",-1)){int eq=part.indexOf('=');if(eq<0)continue;String k=part.substring(0,eq).trim().toUpperCase(java.util.Locale.ROOT),v=part.substring(eq+1).trim();if(k.equals("PHASE"))phaseId=v.toLowerCase(java.util.Locale.ROOT);else if(k.equals("TASK"))taskId=v.toLowerCase(java.util.Locale.ROOT);else if(k.equals("NOTES"))notes=v;else if(k.equals("PROGRESS"))try{progress=Math.max(0,Math.min(100,Integer.parseInt(v)));}catch(NumberFormatException ignored){}else if(k.equals("TARGET"))targetDate=v;}if(!targetDate.isBlank())try{java.time.LocalDate.parse(targetDate);}catch(java.time.format.DateTimeParseException ex){throw new IllegalArgumentException("target date must be YYYY-MM-DD");}if(!phaseId.isBlank()){boolean phaseFound=false;for(var phase:project.phases()){if(phase.id().equals(phaseId)){phaseFound=true;break;}}if(!phaseFound)throw new IllegalArgumentException("unknown milestone phase");}if(!taskId.isBlank()){var task=data.task(taskId);if(task==null||!project.id().equals(task.projectId()))throw new IllegalArgumentException("milestone task must belong to this project");}var ms=new java.util.ArrayList<>(project.milestoneRecords());ms.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone(data.newId("milestone"),name,"PLANNED",phaseId,taskId,notes,progress,targetDate));data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProjectMilestones(project,ms));
                }
                case "project_milestone_status" -> {
                    var project=data.project(payload.id());if(project==null)return;String mid=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT),status=payload.arg2()==null?"PLANNED":payload.arg2().trim().toUpperCase(java.util.Locale.ROOT);if(!java.util.Set.of("PLANNED","ACTIVE","COMPLETE","SKIPPED").contains(status))throw new IllegalArgumentException("invalid milestone status");var ms=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone>();boolean found=false;for(var m:project.milestoneRecords()){if(m.id().equals(mid)){ms.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone(m.id(),m.name(),status,m.phaseId(),m.taskId(),m.notes(),m.progress(),m.targetDate()));found=true;}else ms.add(m);}if(!found)throw new IllegalArgumentException("unknown milestone");data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProjectMilestones(project,ms));
                }
                case "project_milestone_edit" -> {
                    var project=data.project(payload.id());if(project==null)return;String mid=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT),spec=payload.arg2()==null?"":payload.arg2();String name="",phaseId="",taskId="",notes="",target="";int progress=0;for(String part:spec.split("\\|",-1)){int eq=part.indexOf('=');if(eq<0)continue;String k=part.substring(0,eq).trim().toUpperCase(java.util.Locale.ROOT),v=part.substring(eq+1).trim();if(k.equals("NAME"))name=v;else if(k.equals("PHASE"))phaseId=v.toLowerCase(java.util.Locale.ROOT);else if(k.equals("TASK"))taskId=v.toLowerCase(java.util.Locale.ROOT);else if(k.equals("NOTES"))notes=v;else if(k.equals("PROGRESS"))try{progress=Math.max(0,Math.min(100,Integer.parseInt(v)));}catch(NumberFormatException ignored){}else if(k.equals("TARGET"))target=v;}if(name.isBlank()||name.length()>96)throw new IllegalArgumentException("milestone name must be 1-96 characters");if(!phaseId.isBlank()){boolean found=false;for(var phase:project.phases())if(phase.id().equals(phaseId)){found=true;break;}if(!found)throw new IllegalArgumentException("unknown milestone phase");}if(!taskId.isBlank()){var task=data.task(taskId);if(task==null||!project.id().equals(task.projectId()))throw new IllegalArgumentException("milestone task must belong to this project");}if(!target.isBlank())try{java.time.LocalDate.parse(target);}catch(java.time.format.DateTimeParseException ex){throw new IllegalArgumentException("target date must be YYYY-MM-DD");}var ms=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone>();boolean found=false;for(var m:project.milestoneRecords()){if(m.id().equals(mid)){ms.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone(m.id(),name,m.status(),phaseId,taskId,notes,progress,target));found=true;}else ms.add(m);}if(!found)throw new IllegalArgumentException("unknown milestone");data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProjectMilestones(project,ms));
                }
                case "project_milestone_metadata" -> {
                    var project=data.project(payload.id());if(project==null)return;String mid=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT),spec=payload.arg2()==null?"":payload.arg2();int progress=0;String target="";for(String part:spec.split("\\|",-1)){int eq=part.indexOf('=');if(eq<0)continue;String k=part.substring(0,eq).trim().toUpperCase(java.util.Locale.ROOT),v=part.substring(eq+1).trim();if(k.equals("PROGRESS"))try{progress=Math.max(0,Math.min(100,Integer.parseInt(v)));}catch(NumberFormatException ignored){}else if(k.equals("TARGET"))target=v;}if(!target.isBlank())try{java.time.LocalDate.parse(target);}catch(java.time.format.DateTimeParseException ex){throw new IllegalArgumentException("target date must be YYYY-MM-DD");}var ms=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone>();boolean found=false;for(var m:project.milestoneRecords()){if(m.id().equals(mid)){ms.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone(m.id(),m.name(),m.status(),m.phaseId(),m.taskId(),m.notes(),progress,target));found=true;}else ms.add(m);}if(!found)throw new IllegalArgumentException("unknown milestone");data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProjectMilestones(project,ms));
                }
                case "project_milestone_remove" -> {
                    var project=data.project(payload.id());if(project==null)return;String mid=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);var ms=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectMilestone>();boolean found=false;for(var m:project.milestoneRecords()){if(m.id().equals(mid))found=true;else ms.add(m);}if(!found)throw new IllegalArgumentException("unknown milestone");data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProjectMilestones(project,ms));
                }
                case "project_blocker_add" -> {
                    var project=data.project(payload.id());if(project==null)return;String text=payload.arg1()==null?"":payload.arg1().trim();if(text.isBlank())throw new IllegalArgumentException("blocker cannot be blank");String spec=payload.arg2()==null?"":payload.arg2().trim();String scopeType="PROJECT",scopeId="";if(!spec.isBlank()){int c=spec.indexOf(':');scopeType=(c<0?spec:spec.substring(0,c)).trim().toUpperCase(java.util.Locale.ROOT);scopeId=c<0?"":spec.substring(c+1).trim().toLowerCase(java.util.Locale.ROOT);}if(!java.util.Set.of("PROJECT","TASK","ASSET","PLAN").contains(scopeType))throw new IllegalArgumentException("invalid blocker scope");if(!scopeType.equals("PROJECT")&&scopeId.isBlank())throw new IllegalArgumentException("scoped blocker requires a target ID");if(scopeType.equals("TASK")){var task=data.task(scopeId);if(task==null||!project.id().equals(task.projectId()))throw new IllegalArgumentException("task blocker target must belong to this project");}if(scopeType.equals("PLAN")&&net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(scopeId)==null)throw new IllegalArgumentException("unknown Plan blocker target");if(scopeType.equals("ASSET")&&net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(scopeId)==null)throw new IllegalArgumentException("unknown Terrain Asset blocker target");var blockers=new java.util.ArrayList<>(project.blockers());blockers.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectBlocker(data.newId("blocker"),text,false,scopeType,scopeId));data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProject(project,project.phases(),blockers,project.currentTaskId()));
                }
                case "project_blocker_toggle" -> {
                    var project=data.project(payload.id());if(project==null)return;String bid=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);var blockers=new java.util.ArrayList<net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectBlocker>();boolean found=false;for(var v:project.blockers()){if(v.id().equals(bid)){blockers.add(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.ProjectBlocker(v.id(),v.text(),!v.resolved(),v.scopeType(),v.scopeId()));found=true;}else blockers.add(v);}if(!found)throw new IllegalArgumentException("unknown blocker");data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProject(project,project.phases(),blockers,project.currentTaskId()));
                }
                case "project_bookmark" -> {
                    var project=data.project(payload.id());if(project==null)return;String tid=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);if(!tid.isBlank()){var task=data.task(tid);if(task==null||!project.id().equals(task.projectId()))throw new IllegalArgumentException("bookmark must reference a task in this project");}data.putProject(net.oceancanvas.mod.project.OceanCanvasWorkspaceData.copyProject(project,project.phases(),project.blockers(),tid));
                }
				case "project_export" -> {
					try {
						var r=net.oceancanvas.mod.project.OceanCanvasProjectPackageExporter.export(world,payload.id());
						tell(player,"Exported .oceanproject: "+r.file().getFileName()+" ("+r.tasks()+" tasks, "+r.planObjects()+" Plan objects, "+r.terrainAssets()+" Terrain Assets).");
					} catch (java.io.IOException e) {
						net.oceancanvas.mod.OceanCanvas.LOGGER.error("Failed to export Ocean Canvas project {}", payload.id(), e);
						tell(player,"Project export failed: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
					}
				}
				case "project_import", "project_import_merge" -> {
					try {
						var file=net.oceancanvas.mod.project.OceanCanvasProjectPackageImporter.resolve(world,payload.arg1());
						String mergeTarget=action.equals("project_import_merge")?payload.arg2():"";
						var r=net.oceancanvas.mod.project.OceanCanvasProjectPackageImporter.importPackage(world,file,mergeTarget);
						tell(player,(r.merged()?"Merged into '":"Imported '")+r.projectName()+"' ("+r.tasks()+" tasks, "+r.planObjects()+" Plan objects, "+r.terrainAssets()+" Terrain Assets)."
								+(r.warnings().isEmpty()?"":" "+r.warnings().size()+" reference(s) could not be resolved and were cleared - see the log for detail."));
						for(String w:r.warnings()) net.oceancanvas.mod.OceanCanvas.LOGGER.warn("[Ocean Canvas] Project import ({}): {}",payload.arg1(),w);
					} catch (IllegalArgumentException e) {
						tell(player,"Project import failed: "+e.getMessage());
					} catch (java.io.IOException e) {
						net.oceancanvas.mod.OceanCanvas.LOGGER.error("Failed to import Ocean Canvas project package {}", payload.arg1(), e);
						tell(player,"Project import failed: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
					}
				}
				case "project_name", "project_notes", "project_parent", "project_plan_links", "project_terrain_links", "project_milestone_add" -> {
					var project=data.project(payload.id());if(project==null)return;String name=project.name(),notes=project.notes(),parent=project.parentProjectId();var plans=new java.util.ArrayList<>(project.planningObjectIds());var terrain=new java.util.ArrayList<>(project.terrainAssetIds());var milestones=new java.util.ArrayList<>(project.milestones());
					if(action.equals("project_name")){name=payload.arg1()==null?"":payload.arg1().trim();if(name.isBlank()||name.length()>96)throw new IllegalArgumentException("project name must be 1-96 characters");}
					else if(action.equals("project_notes")){notes=payload.arg1()==null?"":payload.arg1();if(notes.length()>1024)throw new IllegalArgumentException("project notes are limited to 1024 characters");}
					else if(action.equals("project_parent")){parent=payload.arg1()==null?"":payload.arg1().trim().toLowerCase(java.util.Locale.ROOT);if(parent.equals(project.id()))throw new IllegalArgumentException("project cannot be its own parent");if(!parent.isBlank()){if(data.project(parent)==null)throw new IllegalArgumentException("unknown parent project");var seen=new java.util.HashSet<String>();String cur=parent;while(!cur.isBlank()&&seen.add(cur)){if(cur.equals(project.id()))throw new IllegalArgumentException("parent project would create a cycle");var cp=data.project(cur);cur=cp==null?"":cp.parentProjectId();}}}
					else if(action.equals("project_plan_links")){plans.clear();if(payload.arg1()!=null&&!payload.arg1().isBlank())for(String v:payload.arg1().split(",")){String idv=v.trim().toLowerCase(java.util.Locale.ROOT);if(net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(idv)!=null&&!plans.contains(idv))plans.add(idv);}}
					else if(action.equals("project_terrain_links")){terrain.clear();var lib=net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world);if(payload.arg1()!=null&&!payload.arg1().isBlank())for(String v:payload.arg1().split(",")){String idv=v.trim().toLowerCase(java.util.Locale.ROOT);if(lib.terrainAsset(idv)!=null&&!terrain.contains(idv))terrain.add(idv);}}
					else {String m=payload.arg1()==null?"":payload.arg1().trim();if(m.isBlank())throw new IllegalArgumentException("milestone cannot be blank");if(milestones.size()<128)milestones.add(m);}
					data.putProject(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.WorkProject(project.id(),name,parent,plans,terrain,project.regionName(),project.status(),project.templateId(),notes,milestones,project.createdAt(),System.currentTimeMillis(),project.phases(),project.blockers(),project.currentTaskId(),project.milestoneRecords()));
				}
				case "journal" -> data.addJournal(payload.arg1(), "", player.blockPosition().getX(), player.blockPosition().getZ());
				case "session_note" -> data.setSessionNote(payload.arg1());
				case "scenario_add" -> { String id=data.newId("scenario"); data.putScenario(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.DesignScenario(id,payload.arg1(),true,payload.arg2())); data.setActiveScenario(id); }
				case "scenario_active" -> data.setActiveScenario(payload.id());
                case "pregen_profile" -> {
                    var profile=net.oceancanvas.mod.project.OceanCanvasProjectData.PregenProfile.parse(payload.arg1());
                    net.oceancanvas.mod.project.OceanCanvasProjectData.get(world).setPregenProfile(profile);
                    tell(player,"Adaptive pregen profile set to "+profile.name()+".");
                }
                case "world_policy_put" -> {
                    String[] a=(payload.arg1()==null?"":payload.arg1()).split("\\t",-1);
                    String[] b=(payload.arg2()==null?"":payload.arg2()).split("\\t",-1);
                    if(a.length!=3||b.length!=3)throw new IllegalArgumentException("world policy payload is malformed");
                    String scope=a[0];
                    String scopeId=new String(java.util.Base64.getUrlDecoder().decode(a[1]),java.nio.charset.StandardCharsets.UTF_8);
                    String key=new String(java.util.Base64.getUrlDecoder().decode(a[2]),java.nio.charset.StandardCharsets.UTF_8);
                    String value=new String(java.util.Base64.getUrlDecoder().decode(b[0]),java.nio.charset.StandardCharsets.UTF_8);
                    boolean enabled=Boolean.parseBoolean(b[1]);
                    String rationale=new String(java.util.Base64.getUrlDecoder().decode(b[2]),java.nio.charset.StandardCharsets.UTF_8);
                    if("REGION".equalsIgnoreCase(scope)&&!scopeId.isBlank()&&OceanCanvasPlayerZones.get(world).zoneByName(scopeId)==null)throw new IllegalArgumentException("unknown Region policy scope");
                    if("PROJECT".equalsIgnoreCase(scope)&&!scopeId.isBlank()&&data.project(scopeId)==null)throw new IllegalArgumentException("unknown Project policy scope");
                    var policy=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).putWorldPolicy(payload.id(),scope,scopeId,key,value,enabled,rationale);
                    tell(player,(payload.id()==null||payload.id().isBlank()?"Created":"Updated")+" World Policy '"+policy.key()+"'.");
                }
                case "world_policy_delete" -> {
                    if(!net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).removeWorldPolicy(payload.id()))throw new IllegalArgumentException("unknown World Policy");
                    tell(player,"Deleted World Policy.");
                }
                case "intent_resolution_put" -> {
                    String targetType=payload.arg1()==null?"PROJECT":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
                    String[] f=(payload.arg2()==null?"":payload.arg2()).split("\\t",-1);
                    if(f.length!=3)throw new IllegalArgumentException("intent-resolution payload is malformed");
                    String targetId=new String(java.util.Base64.getUrlDecoder().decode(f[0]),java.nio.charset.StandardCharsets.UTF_8);
                    int level=Integer.parseInt(f[1]);
                    String rationale=new String(java.util.Base64.getUrlDecoder().decode(f[2]),java.nio.charset.StandardCharsets.UTF_8);
                    if(level<0||level>5)throw new IllegalArgumentException("intent level must be L0-L5");
                    switch(targetType){
                        case "REGION" -> {if(OceanCanvasPlayerZones.get(world).zoneByName(targetId)==null)throw new IllegalArgumentException("unknown Region intent target");}
                        case "PROJECT" -> {if(data.project(targetId)==null)throw new IllegalArgumentException("unknown Project intent target");}
                        case "PLAN" -> {if(net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(targetId)==null)throw new IllegalArgumentException("unknown Plan intent target");}
                        case "TERRAIN_ASSET" -> {if(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(targetId)==null)throw new IllegalArgumentException("unknown Terrain Asset intent target");}
                        case "WORLD","FEATURE","WORK_AREA" -> {}
                        default -> throw new IllegalArgumentException("unsupported intent target type");
                    }
                    var v=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).putIntentResolution(payload.id(),targetType,targetId,level,rationale);
                    tell(player,"Saved "+v.targetType()+" "+v.targetId()+" at L"+v.level()+" "+v.levelName()+".");
                }
                case "intent_resolution_delete" -> {
                    if(!net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).removeIntentResolution(payload.id()))throw new IllegalArgumentException("unknown Resolution of Intent record");
                    tell(player,"Removed Resolution of Intent declaration.");
                }
                case "authorship_put" -> {
                    String targetType=payload.arg1()==null?"PROJECT":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
                    String[] f=(payload.arg2()==null?"":payload.arg2()).split("\\t",-1);
                    if(f.length!=5)throw new IllegalArgumentException("authorship payload is malformed");
                    String targetId=new String(java.util.Base64.getUrlDecoder().decode(f[0]),java.nio.charset.StandardCharsets.UTF_8);
                    String layer=f[1],authorship=f[2];
                    String source=new String(java.util.Base64.getUrlDecoder().decode(f[3]),java.nio.charset.StandardCharsets.UTF_8);
                    String notes=new String(java.util.Base64.getUrlDecoder().decode(f[4]),java.nio.charset.StandardCharsets.UTF_8);
                    switch(targetType){
                        case "REGION" -> {if(OceanCanvasPlayerZones.get(world).zoneByName(targetId)==null)throw new IllegalArgumentException("unknown Region authorship target");}
                        case "PROJECT" -> {if(data.project(targetId)==null)throw new IllegalArgumentException("unknown Project authorship target");}
                        case "PLAN" -> {if(net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(targetId)==null)throw new IllegalArgumentException("unknown Plan authorship target");}
                        case "TERRAIN_ASSET" -> {if(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(targetId)==null)throw new IllegalArgumentException("unknown Terrain Asset authorship target");}
                        case "WORLD","FEATURE","WORK_AREA" -> {}
                        default -> throw new IllegalArgumentException("unsupported authorship target type");
                    }
                    var v=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).putAuthorshipProvenance(payload.id(),targetType,targetId,layer,authorship,source,notes);
                    tell(player,"Saved "+v.layer()+" authorship as "+v.authorship()+".");
                }
                case "authorship_delete" -> {
                    if(!net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).removeAuthorshipProvenance(payload.id()))throw new IllegalArgumentException("unknown Authorship Provenance record");
                    tell(player,"Removed Authorship Provenance declaration.");
                }
                case "world_claim_put" -> {
                    String targetType=payload.arg1()==null?"PROJECT":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
                    String[] f=(payload.arg2()==null?"":payload.arg2()).split("\\t",-1);
                    if(f.length!=8)throw new IllegalArgumentException("world claim payload is malformed");
                    String targetId=new String(java.util.Base64.getUrlDecoder().decode(f[0]),java.nio.charset.StandardCharsets.UTF_8);
                    String context=f[1],key=new String(java.util.Base64.getUrlDecoder().decode(f[2]),java.nio.charset.StandardCharsets.UTF_8);
                    String value=new String(java.util.Base64.getUrlDecoder().decode(f[3]),java.nio.charset.StandardCharsets.UTF_8);
                    String epistemic=f[4],evidenceType=f[5];
                    String evidenceRef=new String(java.util.Base64.getUrlDecoder().decode(f[6]),java.nio.charset.StandardCharsets.UTF_8);
                    String notes=new String(java.util.Base64.getUrlDecoder().decode(f[7]),java.nio.charset.StandardCharsets.UTF_8);
                    switch(targetType){
                        case "REGION" -> {if(OceanCanvasPlayerZones.get(world).zoneByName(targetId)==null)throw new IllegalArgumentException("unknown Region claim target");}
                        case "PROJECT" -> {if(data.project(targetId)==null)throw new IllegalArgumentException("unknown Project claim target");}
                        case "PLAN" -> {if(net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world).object(targetId)==null)throw new IllegalArgumentException("unknown Plan claim target");}
                        case "TERRAIN_ASSET" -> {if(net.oceancanvas.mod.project.OceanCanvasPlanLibraryData.get(world).terrainAsset(targetId)==null)throw new IllegalArgumentException("unknown Terrain Asset claim target");}
                        case "WORLD","FEATURE","WORK_AREA" -> {}
                        default -> throw new IllegalArgumentException("unsupported claim target type");
                    }
                    var v=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).putWorldClaim(payload.id(),targetType,targetId,context,key,value,epistemic,evidenceType,evidenceRef,notes);
                    tell(player,"Saved "+v.context()+" claim '"+v.key()+"'.");
                }
                case "world_claim_delete" -> {
                    if(!net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).removeWorldClaim(payload.id()))throw new IllegalArgumentException("unknown World Claim");
                    tell(player,"Removed World Claim.");
                }
                case "world_relationship_put" -> {
                    String[] f=(payload.arg2()==null?"":payload.arg2()).split("\\t",-1);
                    if(f.length!=6)throw new IllegalArgumentException("relationship payload is malformed");
                    String fromType=payload.arg1()==null?"PROJECT":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
                    String fromId=new String(java.util.Base64.getUrlDecoder().decode(f[0]),java.nio.charset.StandardCharsets.UTF_8);
                    String relation=f[1],toType=f[2];
                    String toId=new String(java.util.Base64.getUrlDecoder().decode(f[3]),java.nio.charset.StandardCharsets.UTF_8);
                    String notes=new String(java.util.Base64.getUrlDecoder().decode(f[4]),java.nio.charset.StandardCharsets.UTF_8);
                    String validation=f[5];
                    validateWorldObjectRef(world,data,fromType,fromId);
                    validateWorldObjectRef(world,data,toType,toId);
                    var v=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).putWorldRelationship(payload.id(),fromType,fromId,relation,toType,toId,notes);
                    tell(player,"Saved relationship "+v.relation()+".");
                }
                case "world_relationship_delete" -> {
                    if(!net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).removeWorldRelationship(payload.id()))throw new IllegalArgumentException("unknown World Relationship");
                    tell(player,"Removed World Relationship.");
                }
                case "world_observation_put" -> {
                    String[] f=(payload.arg2()==null?"":payload.arg2()).split("\\t",-1);if(f.length!=9)throw new IllegalArgumentException("observation payload is malformed");
                    String type=payload.arg1()==null?"PROJECT":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
                    String targetId=new String(java.util.Base64.getUrlDecoder().decode(f[0]),java.nio.charset.StandardCharsets.UTF_8);validateWorldObjectRef(world,data,type,targetId);
                    int x=Integer.parseInt(f[3]),y=Integer.parseInt(f[4]),z=Integer.parseInt(f[5]);
                    String revision=new String(java.util.Base64.getUrlDecoder().decode(f[6]),java.nio.charset.StandardCharsets.UTF_8),notes=new String(java.util.Base64.getUrlDecoder().decode(f[7]),java.nio.charset.StandardCharsets.UTF_8);long observedAt=Long.parseLong(f[8]);
                    var v=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).putWorldObservation(payload.id(),type,targetId,f[1],f[2],x,y,z,revision,notes,observedAt);tell(player,"Saved "+v.observationType()+" observation.");
                }
                case "world_observation_delete" -> {
                    if(!net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).removeWorldObservation(payload.id()))throw new IllegalArgumentException("unknown World Observation");tell(player,"Removed World Observation.");
                }
                case "candidate_edit_put" -> {
                    String[] f=(payload.arg2()==null?"":payload.arg2()).split("\\t",-1);if(f.length!=15)throw new IllegalArgumentException("candidate edit payload is malformed");
                    String type=payload.arg1()==null?"PROJECT":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
                    String targetId=new String(java.util.Base64.getUrlDecoder().decode(f[0]),java.nio.charset.StandardCharsets.UTF_8);validateWorldObjectRef(world,data,type,targetId);
                    String observationId=new String(java.util.Base64.getUrlDecoder().decode(f[1]),java.nio.charset.StandardCharsets.UTF_8);
                    String proposedType=f[7],proposedId=new String(java.util.Base64.getUrlDecoder().decode(f[8]),java.nio.charset.StandardCharsets.UTF_8);
                    if(!proposedType.isBlank())validateWorldObjectRef(world,data,proposedType,proposedId);
                    String geometry=new String(java.util.Base64.getUrlDecoder().decode(f[9]),java.nio.charset.StandardCharsets.UTF_8),baseFingerprint=f[10],instruction=new String(java.util.Base64.getUrlDecoder().decode(f[11]),java.nio.charset.StandardCharsets.UTF_8),rationale=new String(java.util.Base64.getUrlDecoder().decode(f[12]),java.nio.charset.StandardCharsets.UTF_8),comparison=new String(java.util.Base64.getUrlDecoder().decode(f[13]),java.nio.charset.StandardCharsets.UTF_8);
                    var v=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).putCandidateEdit(payload.id(),type,targetId,observationId,f[2],f[3],Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),proposedType,proposedId,geometry,baseFingerprint,instruction,rationale,comparison);
                    tell(player,"Saved "+v.status()+" candidate "+v.editType()+".");
                }
                case "candidate_edit_delete" -> {
                    if(!net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).removeCandidateEdit(payload.id()))throw new IllegalArgumentException("unknown Candidate Edit");tell(player,"Removed Candidate Edit.");
                }
                case "design_revision_restore_candidate" -> {
                    var forever=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world);net.oceancanvas.mod.project.OceanCanvasForeverWorldData.DesignRevision revision=null;
                    for(var v:forever.designRevisions())if(v.id().equals(payload.id())){revision=v;break;}
                    if(revision==null)throw new IllegalArgumentException("unknown Design Revision");
                    if(!"PLAN".equals(revision.targetType()))throw new IllegalArgumentException("only Plan revision recovery is supported");
                    if(revision.beforeGeometry().isBlank())throw new IllegalArgumentException("revision has no recoverable Before geometry");
                    var planning=net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world);var obj=planning.object(revision.targetId());
                    if(obj==null)throw new IllegalArgumentException("revision target Plan no longer exists");
                    String currentGeometry=planningGeometryPacked(obj.points()),currentFingerprint=sha256Hex(currentGeometry);
                    var candidate=forever.putCandidateEdit("","PLAN",obj.id(),"","REVIEW_AREA","DRAFT",0,0,0,"PLAN",obj.id(),revision.beforeGeometry(),currentFingerprint,"Recover the Before geometry from Design Revision "+revision.id()+".","Revision recovery requested by "+player.getGameProfile().name()+". Current Design remains canonical until this Candidate completes review and promotion.","Recovered from revision "+revision.id()+"; historical before="+revision.beforeFingerprint()+"; current base="+currentFingerprint+".");
                    forever.addWorldEvent("PLAN",obj.id(),"NOTE","Revision restored as Candidate","Revision "+revision.id()+" created draft Candidate "+candidate.id()+"; canonical Design was not changed.",player.getGameProfile().name(),revision.id(),System.currentTimeMillis());
                    tell(player,"Created DRAFT Candidate "+candidate.id()+" from revision "+revision.id()+". Review and approve it before promotion.");
                }
                case "candidate_promote_design" -> {
                    var forever=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world);var c=forever.candidateEdit(payload.id());if(c==null)throw new IllegalArgumentException("unknown Candidate Edit");
                    if(!"APPROVED".equals(c.status()))throw new IllegalArgumentException("Candidate must be APPROVED before promotion");
                    if(!"PLAN".equals(c.proposedTargetType()))throw new IllegalArgumentException("only Plan Candidate promotion is supported");
                    if(c.proposedTargetId().isBlank()||c.candidateGeometry().isBlank()||c.baseGeometryFingerprint().isBlank())throw new IllegalArgumentException("Candidate is missing target, geometry, or base provenance");
                    var planning=net.oceancanvas.mod.project.OceanCanvasPlanningData.get(world);var obj=planning.object(c.proposedTargetId());if(obj==null)throw new IllegalArgumentException("target Plan no longer exists");if(obj.locked())throw new IllegalArgumentException("unlock the target Plan before promotion");
                    String beforeGeometry=planningGeometryPacked(obj.points()),currentFingerprint=sha256Hex(beforeGeometry);if(!currentFingerprint.equals(c.baseGeometryFingerprint()))throw new IllegalArgumentException("STALE BASE: canonical Plan changed after this Candidate was based");
                    var pts=parsePlanningPoints(c.candidateGeometry());int min=planningMinPoints(obj.type());if(pts.size()<min||pts.size()>512)throw new IllegalArgumentException("Candidate geometry has invalid vertex count for "+obj.type());
                    var beforeObjects=planning.objects();validateLinkedEndpointMove(planning,obj,pts);var updated=copyPlanningPoints(obj,pts);planning.putObject(updated);propagateLinkedEndpoint(planning,updated,true);propagateLinkedEndpoint(planning,updated,false);
                    net.oceancanvas.mod.planning.OceanCanvasPlanningHistory.record(player,beforeObjects,planning.objects());
                    String afterGeometry=planningGeometryPacked(updated.points()),afterFingerprint=sha256Hex(afterGeometry),detail="Candidate "+c.id()+" promoted to Plan "+obj.id()+"; before="+currentFingerprint+"; after="+afterFingerprint+"; vertices "+obj.points().size()+"→"+updated.points().size();
                    var revision=forever.addDesignRevision("PLAN",obj.id(),c.id(),player.getGameProfile().name(),beforeGeometry,afterGeometry,currentFingerprint,afterFingerprint,"Approved Candidate promoted to canonical Design.");
                    forever.addWorldEvent("PLAN",obj.id(),"DESIGN_CHANGED","Candidate promoted to Design",detail+"; revision="+revision.id(),player.getGameProfile().name(),c.id(),System.currentTimeMillis());forever.setCandidateStatus(c.id(),"SUPERSEDED","Promoted to canonical Design as revision "+revision.id()+". Before "+currentFingerprint.substring(0,12)+"… → after "+afterFingerprint.substring(0,12)+"…");tell(player,"Promoted approved Candidate to Design for '"+obj.name()+"'. Revision "+revision.id()+" recorded.");
                }
                case "world_event_add" -> {
                    String[] f=(payload.arg2()==null?"":payload.arg2()).split("\\t",-1);if(f.length!=7)throw new IllegalArgumentException("world event payload is malformed");
                    String type=payload.arg1()==null?"PROJECT":payload.arg1().trim().toUpperCase(java.util.Locale.ROOT);
                    String targetId=new String(java.util.Base64.getUrlDecoder().decode(f[0]),java.nio.charset.StandardCharsets.UTF_8);validateWorldObjectRef(world,data,type,targetId);
                    String title=new String(java.util.Base64.getUrlDecoder().decode(f[2]),java.nio.charset.StandardCharsets.UTF_8),detail=new String(java.util.Base64.getUrlDecoder().decode(f[3]),java.nio.charset.StandardCharsets.UTF_8),actor=new String(java.util.Base64.getUrlDecoder().decode(f[4]),java.nio.charset.StandardCharsets.UTF_8),source=new String(java.util.Base64.getUrlDecoder().decode(f[5]),java.nio.charset.StandardCharsets.UTF_8);
                    var v=net.oceancanvas.mod.project.OceanCanvasForeverWorldData.get(world).addWorldEvent(type,targetId,f[1],title,detail,actor,source,Long.parseLong(f[6]));tell(player,"Added history event '"+v.title()+"'.");
                }
				case "viewpoint_add" -> {
					var bp=player.blockPosition(); String name=payload.arg1()==null||payload.arg1().isBlank()?"Viewpoint":payload.arg1();
					data.putViewpoint(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.Viewpoint(data.newId("view"),name,player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot(),"",payload.arg2()));
				}
				case "atlas_add" -> {
					String[] pos=payload.arg2().split(",",-1); int x=Integer.parseInt(pos[0]), z=Integer.parseInt(pos[1]);
					data.putAtlasFeature(new net.oceancanvas.mod.project.OceanCanvasWorkspaceData.AtlasFeature(data.newId("feature"),"LANDMARK",payload.arg1(),"","",x,z,"",""));
				}
				case "atlas_rename" -> {
					if(payload.arg1()==null||payload.arg1().isBlank())throw new IllegalArgumentException("a waypoint name cannot be blank");
					var v=data.renameAtlasFeature(payload.id(),payload.arg1().trim());
					if(v==null)throw new IllegalArgumentException("unknown waypoint");
					tell(player,"Renamed waypoint to "+v.name()+".");
				}
				case "atlas_delete" -> {
					if(!data.removeAtlasFeature(payload.id()))throw new IllegalArgumentException("unknown waypoint");
					tell(player,"Deleted waypoint.");
				}
				case "atlas_color" -> {
					var v=data.recolorAtlasFeature(payload.id(),payload.arg1());
					if(v==null)throw new IllegalArgumentException("unknown waypoint");
				}
				case "atlas_move" -> {
					String[] pos=(payload.arg1()==null?"":payload.arg1()).split(",",-1);
					if(pos.length!=2)throw new IllegalArgumentException("waypoint position needs x,z");
					var v=data.moveAtlasFeature(payload.id(),Integer.parseInt(pos[0].trim()),Integer.parseInt(pos[1].trim()));
					if(v==null)throw new IllegalArgumentException("unknown waypoint");
				}
				default -> { tell(player, "Unknown workspace edit '" + action + "'.", true); return; }
			}
		} catch (RuntimeException ex) { tell(player, "Invalid workspace edit: " + ex.getMessage(), true); return; }
		broadcastToAll(world.getServer());
	}

	private static OceanCanvasConfigSyncPayload buildConfigPayload() {
		OceanCanvasConfig c = OceanCanvasConfig.get();
		String packed = String.join(";",
				"canvasSize=" + c.canvasSize(), "centerX=" + c.centerX(), "centerZ=" + c.centerZ(),
				"expansionEnabled=" + c.expansionEnabled(), "oceanFloorY=" + c.oceanFloorY(),
				"oceanFloorVariation=" + c.oceanFloorVariation(),
				"oceanFloorTransitionThickness=" + c.oceanFloorTransitionThickness(),
				"pregenEnabled=" + c.pregenEnabled(), "pregenChunksPerTick=" + c.pregenChunksPerTick(),
				"foreverWorldTargetHours=" + c.foreverWorldTargetHours(),
				"biomeMaskEnabled=" + c.biomeMaskEnabled(), "biomeMaskBiome=" + c.biomeMaskBiome(),
				"guaranteeSpawnOceanRuin=" + c.guaranteeSpawnOceanRuin(), "hudEnabled=" + c.hudEnabled(),
				"worldBorderSyncEnabled=" + c.worldBorderSyncEnabled(), "undoDepthPerPlayer=" + c.undoDepthPerPlayer(),
				"shipwrecksEnabled=" + c.shipwrecksRule().name(), "journeyMapOverlayEnabled=" + c.journeyMapOverlayEnabled(),
				"flattenerChunksPerTick=" + c.flattenerChunksPerTick(), "backupEnabled=" + c.backupEnabled(),
				"backupThresholdChunks=" + c.backupThresholdChunks(), "backupRetentionCount=" + c.backupRetentionCount(),
				"taperEnabled=" + c.taperEnabled(), "taperWidthChunks=" + c.taperWidthChunks(),
				"naturalOceanRuinsProtected=" + c.naturalOceanRuinsRule().name(),
				"buriedTreasureEnabled=" + c.buriedTreasureRule().name(),
				"naturalOceanMonumentsProtected=" + c.naturalOceanMonumentsRule().name(),
				"naturalRuinedPortalsProtected=" + c.naturalRuinedPortalsRule().name());
		return new OceanCanvasConfigSyncPayload(packed);
	}

	/**
	 * Parses one of the five world-wide structure-rule settings sent by the client (v125's
	 * {@code OceanCanvasSettingsScreen}, which now cycles Default/Always/Never the same way the
	 * map's per-region rule buttons already do) - the enum's own name ("INHERIT"/"FORCE_ON"/
	 * "FORCE_OFF"). Falls back to the setting's current value on anything unrecognized, rather
	 * than silently defaulting to INHERIT, so a stray/garbled client message can't accidentally
	 * clear an explicit Always/Never choice.
	 */
	private static net.oceancanvas.mod.config.StructureOverride parseStructureOverride(
			String raw, net.oceancanvas.mod.config.StructureOverride fallback) {
		if (raw == null || raw.isBlank()) return fallback;
		try {
			return net.oceancanvas.mod.config.StructureOverride.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			return fallback;
		}
	}

	private static void handleConfigUpdate(OceanCanvasConfigUpdateRequestPayload payload, ServerPlayer player) {
		if (!hasBasePermission(player)) { tell(player, "You do not have permission to edit Ocean Canvas settings.", true); return; }
		String k = payload.key() == null ? "" : payload.key();
		String v = payload.value() == null ? "" : payload.value().trim();
		ServerLevel world=player.level();
		if(net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.isImpactfulConfigKey(k)){
			var preview=net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.config(world,k,v);
			if(preview.blocked()){tell(player,preview.summary(),true);return;}
			if(!net.oceancanvas.mod.project.OceanCanvasOperationPreviewService.tokenMatchesConfig(world,k,v,payload.previewToken())){
				tell(player,"Change-impact preview is missing or stale. Review the server-authored impact before applying '"+k+"'.",true);return;
			}
		}
		OceanCanvasConfig c = OceanCanvasConfig.get();
		OceanCanvasConfig.Builder b = c.toBuilder();
		try {
			switch (k) {
				case "canvasSize" -> b.canvasSize(Integer.parseInt(v));
				case "centerX" -> b.center(Integer.parseInt(v), c.centerZ());
				case "centerZ" -> b.center(c.centerX(), Integer.parseInt(v));
				case "expansionEnabled" -> b.expansionEnabled(Boolean.parseBoolean(v));
				case "oceanFloorY" -> b.oceanFloorY(Integer.parseInt(v));
				case "oceanFloorVariation" -> b.oceanFloorVariation(Integer.parseInt(v));
				case "oceanFloorTransitionThickness" -> b.oceanFloorTransitionThickness(Integer.parseInt(v));
				case "pregenEnabled" -> b.pregenEnabled(Boolean.parseBoolean(v));
				case "pregenChunksPerTick" -> b.pregenChunksPerTick(Integer.parseInt(v));
				case "foreverWorldTargetHours" -> b.foreverWorldTargetHours(Integer.parseInt(v));
				case "biomeMaskEnabled" -> b.biomeMaskEnabled(Boolean.parseBoolean(v));
				case "biomeMaskBiome" -> b.biomeMaskBiome(v);
				case "guaranteeSpawnOceanRuin" -> b.guaranteeSpawnOceanRuin(Boolean.parseBoolean(v));
				case "hudEnabled" -> b.hudEnabled(Boolean.parseBoolean(v));
				case "worldBorderSyncEnabled" -> b.worldBorderSyncEnabled(Boolean.parseBoolean(v));
				case "undoDepthPerPlayer" -> b.undoDepthPerPlayer(Integer.parseInt(v));
				case "shipwrecksEnabled" -> b.shipwrecksRule(parseStructureOverride(v, c.shipwrecksRule()));
				case "journeyMapOverlayEnabled" -> b.journeyMapOverlayEnabled(Boolean.parseBoolean(v));
				case "flattenerChunksPerTick" -> b.flattenerChunksPerTick(Integer.parseInt(v));
				case "backupEnabled" -> b.backupEnabled(Boolean.parseBoolean(v));
				case "backupThresholdChunks" -> b.backupThresholdChunks(Integer.parseInt(v));
				case "backupRetentionCount" -> b.backupRetentionCount(Integer.parseInt(v));
				case "taperEnabled" -> b.taperEnabled(Boolean.parseBoolean(v));
				case "taperWidthChunks" -> b.taperWidthChunks(Integer.parseInt(v));
				case "naturalOceanRuinsProtected" -> b.naturalOceanRuinsRule(parseStructureOverride(v, c.naturalOceanRuinsRule()));
				case "buriedTreasureEnabled" -> b.buriedTreasureRule(parseStructureOverride(v, c.buriedTreasureRule()));
				case "naturalOceanMonumentsProtected" -> b.naturalOceanMonumentsRule(parseStructureOverride(v, c.naturalOceanMonumentsRule()));
				case "naturalRuinedPortalsProtected" -> b.naturalRuinedPortalsRule(parseStructureOverride(v, c.naturalRuinedPortalsRule()));
				default -> { tell(player, "Unknown setting: " + k, true); return; }
			}
			OceanCanvasConfig updated = b.save();
			if ("worldBorderSyncEnabled".equals(k) && player.level().getServer().overworld() != null) {
				net.oceancanvas.mod.command.BoundaryCommand.apply(player.level().getServer().overworld(), updated);
			}
			tellOnScreen(player, "Saved setting: " + k);
			broadcastToAll(player.level().getServer());
		} catch (RuntimeException ex) {
			tell(player, "Invalid value for " + k + ": " + v, true);
		}
	}

	private static String packRules(OceanCanvasPlayerZones.Zone zone) {
		java.util.Map<String, String> rules = new java.util.LinkedHashMap<>();
		for (OceanCanvasStructureKind kind : OceanCanvasStructureKind.values()) {
			net.oceancanvas.mod.config.StructureOverride override = zone.overrideFor(kind);
			if (override != net.oceancanvas.mod.config.StructureOverride.INHERIT) {
				rules.put(kind.id(), override.name());
			}
		}
		return OceanCanvasZoneSyncPayload.formatRules(rules);
	}

	/**
	 * The structures layer - see {@link OceanCanvasStructureSyncPayload}.
	 * Truncated at that payload's own cap rather than sent whole: this
	 * goes to every player every few seconds, and a long-explored canvas
	 * can accumulate a great many protected boxes.
	 */
	private static OceanCanvasStructureSyncPayload buildStructurePayload(MinecraftServer server) {
		List<OceanCanvasStructureSyncPayload.StructureEntry> entries = new ArrayList<>();
		ServerLevel overworld = server.overworld();
		if (overworld != null) {
			var data = net.oceancanvas.mod.worldgen.OceanCanvasProtectedData.get(overworld);
			java.util.Map<String,Integer> typed = new java.util.HashMap<>();
			for (var snap : data.relocatedStructureSnapshots()) {
				int code = switch (snap.kind()) {
					case "shipwreck" -> 1;
					case "ocean_monument" -> 2;
					case "ruined_portal" -> 3;
					default -> 0;
				};
				var b=snap.bounds();
				typed.put(b.minX()+":"+b.minY()+":"+b.minZ()+":"+b.maxX()+":"+b.maxY()+":"+b.maxZ(), code);
			}
			for (net.minecraft.world.level.levelgen.structure.BoundingBox box : data.protectedRegions()) {
				if (entries.size() >= OceanCanvasStructureSyncPayload.MAX_ENTRIES) break;
				String key=box.minX()+":"+box.minY()+":"+box.minZ()+":"+box.maxX()+":"+box.maxY()+":"+box.maxZ();
				int code=typed.getOrDefault(key,0);
				entries.add(new OceanCanvasStructureSyncPayload.StructureEntry(
						box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), code));
			}
		}
		return new OceanCanvasStructureSyncPayload(entries);
	}

	private static OceanCanvasZoneSyncPayload buildPayload(MinecraftServer server) {
		OceanCanvasConfig config = OceanCanvasConfig.get();
		int radius = config.radius();
		int canvasMinX = config.centerX() - radius;
		int canvasMinZ = config.centerZ() - radius;
		int canvasMaxX = config.centerX() + radius;
		int canvasMaxZ = config.centerZ() + radius;
		// 0 rather than the configured width when the taper is off, so the
		// client has a single unambiguous "is there a taper ring to draw"
		// signal and never needs its own copy of the enabled flag.
		int taperWidthBlocks = config.taperEnabled() ? config.taperWidthBlocks() : 0;

		long flattenedChunks = 0L;
		List<OceanCanvasZoneSyncPayload.ZoneEntry> entries = new ArrayList<>();
		ServerLevel overworld = server.overworld();
		if (overworld != null) {
			flattenedChunks = net.oceancanvas.mod.worldgen.OceanCanvasProtectedData.get(overworld).flattenedChunkCount();
			for (OceanCanvasPlayerZones.Zone zone : OceanCanvasPlayerZones.get(overworld).all()) {
				entries.add(new OceanCanvasZoneSyncPayload.ZoneEntry(
						zone.name(),
						zone.bounds().minX(), zone.bounds().minY(), zone.bounds().minZ(),
						zone.bounds().maxX(), zone.bounds().maxY(), zone.bounds().maxZ(),
						zone.protectedNow(),
						zone.ownerName(),
						packRules(zone),
						zone.biomeOverride() == null ? "" : zone.biomeOverride(),
						zone.suppressHostileMobs(),
						zone.color() == null ? "" : zone.color(),
						java.util.List.of(), // v253.78: never expand explicit masks to boxed chunk IDs on sync
						zone.syncChunkRuns().stream().map(r -> new OceanCanvasZoneSyncPayload.ZoneRun(r.z(), r.minX(), r.maxX())).toList(),
                        new java.util.ArrayList<>(zone.shapeVertices())
				));
			}
		}

		return new OceanCanvasZoneSyncPayload(canvasMinX, canvasMinZ, canvasMaxX, canvasMaxZ,
				taperWidthBlocks, flattenedChunks, entries);
	}
}
