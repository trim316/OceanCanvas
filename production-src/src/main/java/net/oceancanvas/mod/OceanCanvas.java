package net.oceancanvas.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.oceancanvas.mod.command.AdminCommand;
import net.oceancanvas.mod.command.AliasCommand;
import net.oceancanvas.mod.command.BoundaryCommand;
import net.oceancanvas.mod.command.ChunkyExportCommand;
import net.oceancanvas.mod.command.CoverageCommand;
import net.oceancanvas.mod.command.DiagnosticCommand;
import net.oceancanvas.mod.command.DeploymentCommand;
import net.oceancanvas.mod.command.ExpandCommand;
import net.oceancanvas.mod.command.HealthCommand;
import net.oceancanvas.mod.command.HarnessCommand;
import net.oceancanvas.mod.command.ForeverWorldCommand;
import net.oceancanvas.mod.command.InspectCommand;
import net.oceancanvas.mod.command.PlanCommand;
import net.oceancanvas.mod.command.PregenCommand;
import net.oceancanvas.mod.command.ProtectCommand;
import net.oceancanvas.mod.command.ProjectCommand;
import net.oceancanvas.mod.command.RedoCommand;
import net.oceancanvas.mod.command.RewipeCommand;
import net.oceancanvas.mod.command.ReleaseCommand;
import net.oceancanvas.mod.command.ReportCommand;
import net.oceancanvas.mod.command.RestoreCommand;
import net.oceancanvas.mod.command.SetupCommand;
import net.oceancanvas.mod.command.SelfTestCommand;
import net.oceancanvas.mod.command.StatusCommand;
import net.oceancanvas.mod.command.StructureRulesCommand;
import net.oceancanvas.mod.command.UndoCommand;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.network.OceanCanvasNetworking;
import net.oceancanvas.mod.pregen.OceanCanvasUndoManager;
import net.oceancanvas.mod.pregen.PregenManager;
import net.oceancanvas.mod.worldgen.ModStarterStructures;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OceanCanvas implements ModInitializer {

	public static final String MOD_ID = "oceancanvas";
	public static final String VERSION = "v253.125.60";
	public static final Logger LOGGER = LoggerFactory.getLogger("Ocean Canvas");

	@Override
	public void onInitialize() {
		verifyRuntimeBuildIdentity();
		OceanCanvasSurfaceFlattener.register();
		net.oceancanvas.mod.diagnostic.OceanCanvasStallWatchdog.register();
		net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.register();
		net.oceancanvas.mod.diagnostic.OceanCanvasPerformanceReplayRecorder.register();
		net.oceancanvas.mod.diagnostic.OceanCanvasOperationDecisionRecorder.register();
		ModStarterStructures.register();
		net.oceancanvas.mod.worldgen.OceanCanvasLoot.register();
		// Drafted, off-by-default future work - both registrations here
		// are cheap and inert (event listeners that immediately check
		// their own config flag and return) until explicitly enabled.
		// See PregenManager's class doc and OceanCanvasConfig's
		// pregenEnabled/biomeMaskEnabled field docs.
		PregenManager.register();
		// Keep worldgen structure-rule resolution independent of the concrete
		// Pregen controller. The provider defaults to INHERIT until this bridge is
		// installed, so initialization failure cannot invent structure policy.
		net.oceancanvas.mod.worldgen.OceanCanvasActiveStructureRuleProvider.install(
				PregenManager::activeJobStructureOverride);
		net.oceancanvas.mod.worldgen.OceanCanvasActiveTerrainOperationBridge.install(
				new net.oceancanvas.mod.worldgen.OceanCanvasActiveTerrainOperationBridge.Controller() {
					@Override public boolean coversStructureFootprint(net.minecraft.world.level.levelgen.structure.BoundingBox box) { return PregenManager.activeJobCoversStructureFootprint(box); }
					@Override public boolean columnInMutationScope(int chunkX, int chunkZ, int blockX, int blockZ) { return PregenManager.activeJobColumnInMutationScope(chunkX, chunkZ, blockX, blockZ); }
					@Override public boolean fullyCoversChunkBlocks(int chunkX, int chunkZ) { return PregenManager.activeJobFullyCoversChunkBlocks(chunkX, chunkZ); }
					@Override public boolean pregenIncludesChunk(int chunkX, int chunkZ) { return PregenManager.activePregenJobIncludesChunk(chunkX, chunkZ); }
					@Override public boolean pregenChunkMayStillMutate(int chunkX, int chunkZ) { return PregenManager.activePregenJobChunkMayStillMutate(chunkX, chunkZ); }
					@Override public void authoritativeCommit(net.minecraft.server.level.ServerLevel world, net.minecraft.world.level.ChunkPos pos) { PregenManager.onPregenChunkAuthoritativelyCommitted(world, pos); }
					@Override public void recoveryExemptionCommitted(net.minecraft.server.level.ServerLevel world, net.minecraft.world.level.ChunkPos pos) { PregenManager.onPregenRecoveryExemptionCommitted(world, pos); }
					@Override public void recoveryLightOnlyEnteredFinalizer(net.minecraft.server.level.ServerLevel world, net.minecraft.world.level.ChunkPos pos) { PregenManager.onPregenRecoveryLightOnlyEnteredFinalizer(world, pos); }
				});
		// Undo (drafted this round, /oceancanvas undo) - see
		// OceanCanvasUndoManager's class doc. Cheap and inert (a tick
		// listener that returns immediately when nothing is undoing) the
		// same way PregenManager's own registration is, so unconditional
		// registration here is fine.
		OceanCanvasUndoManager.register();
		net.oceancanvas.mod.worldgen.OceanCanvasUndoRecorderBridge.install(
				OceanCanvasUndoManager::record);
		net.oceancanvas.mod.restore.OceanCanvasRestoreManager.register();
		// Normalize controller-specific read models once at composition time.
		// Downstream history/UI/diagnostic services consume only this neutral view.
		net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.install(
				new net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Provider() {
					@Override public net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Overlay overlaySnapshot() {
						var p = PregenManager.overlaySnapshot();
						if (p != null) return new net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Overlay(
								p.kind(), p.scopeName(), p.minChunkX(), p.minChunkZ(), p.maxChunkX(), p.maxChunkZ(),
								p.cursorChunkX(), p.cursorChunkZ(), p.submittedChunks(), p.totalChunks());
						var r = net.oceancanvas.mod.restore.OceanCanvasRestoreManager.overlaySnapshot();
						return r == null ? null : new net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Overlay(
								r.kind(), "", r.minChunkX(), r.minChunkZ(), r.maxChunkX(), r.maxChunkZ(),
								r.cursorChunkX(), r.cursorChunkZ(), r.completed(), r.total());
					}
					@Override public net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Owner ownerSnapshot() {
						var p = PregenManager.ownerSnapshot();
						if (p != null) return new net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Owner(
								p.kind(), p.requesterId(), p.requesterDisplay());
						var r = net.oceancanvas.mod.restore.OceanCanvasRestoreManager.ownerSnapshot();
						return r == null ? null : new net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.Owner(
								r.kind(), r.requesterId(), r.requesterDisplay());
					}
				});
		net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.install(
				new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationActivity.Provider() {
					@Override public boolean pregenRunning() { return PregenManager.isRunning(); }
					@Override public boolean restoreRunning() { return net.oceancanvas.mod.restore.OceanCanvasRestoreManager.running(); }
					@Override public boolean undoRunning() { return OceanCanvasUndoManager.isRunning(); }
					@Override public int outstandingPregenTargets() { return OceanCanvasSurfaceFlattener.outstandingPregenTargetCount(); }
					@Override public int pendingRegenerationCount() { return OceanCanvasSurfaceFlattener.pendingRegenerationCount(); }
				});
		net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.install(
				new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Provider() {
					@Override public net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Telemetry telemetrySnapshot() {
						var t = PregenManager.telemetrySnapshot();
						return t == null ? null : new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Telemetry(
								t.kind(), t.submittedChunks(), t.totalChunks(), t.adaptiveRatePerTick(),
								t.outstandingChunks(), t.heapUseFraction(), t.tickMsEma());
					}
					@Override public net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Performance performanceSnapshot() {
						var p = PregenManager.performanceSnapshot();
						return p == null ? net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Performance.idle()
								: new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.Performance(
								p.kind(), p.profile(), p.phase(), p.reason(), p.handled(), p.total(), p.skipped(), p.settled(),
								p.rate(), p.rateCap(), p.outstanding(), p.queued(), p.sharedQueue(), p.finalDrainTickets(),
								p.tickWorkMs(), p.tickIntervalMs(), p.heapFraction(), p.heapUsedMiB(), p.heapMaxMiB(),
								p.chunksPerSecond(), p.elapsedSeconds(), p.noProgressSeconds(), p.etaLow(), p.etaHigh(), p.etaQuality());
					}
					@Override public net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.ResourceBudget resourceBudgetSnapshot() {
						var b = PregenManager.resourceBudgetSnapshot();
						return b == null ? net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.ResourceBudget.idle()
								: new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPerformance.ResourceBudget(
								b.state().name(), b.limiter().name(), b.requestedRate(), b.admissionCap(), b.cpuWorkMs(),
								b.cpuSoftBudgetMs(), b.cpuHardBudgetMs(), b.heapUseFraction(), b.heapSoftLimit(), b.heapHardLimit(),
								b.transientTickets(), b.ticketSoftLimit(), b.ticketHardLimit(), b.reason());
					}
					@Override public String status() { return PregenManager.status(); }
				});
		// Persisted checkpoint schemas remain owned by their concrete controllers;
		// diagnostics consume only this read-only identity view.
		net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.install(
				new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.Provider() {
					@Override public net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.Checkpoint pregenCheckpoint(net.minecraft.server.level.ServerLevel world) {
						var saved = net.oceancanvas.mod.pregen.OceanCanvasJobState.get(world).get();
						return saved == null ? null : new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.Checkpoint(saved.kind(), saved.queueEntryId());
					}
					@Override public net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.Checkpoint restoreCheckpoint(net.minecraft.server.level.ServerLevel world) {
						var saved = net.oceancanvas.mod.restore.OceanCanvasRestoreJobState.get(world).get();
						return saved == null ? null : new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainOperationPersistence.Checkpoint("restore", saved.regionName());
					}
				});
		// SurfaceFlattener still owns all transient queues/tickets/recovery state.
		// Convert its read-only records here so diagnostics/project code no longer
		// compiles against the god class or Restore controller implementation.
		net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.install(
				new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.Provider() {
					@Override public net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.Queue queueSnapshot(net.minecraft.server.level.ServerLevel world) {
						var q = OceanCanvasSurfaceFlattener.pregenQueueDiagnostics(world);
						return new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.Queue(
								q.queued(), q.ready(), q.missingNeighbor(), q.missingNeighborStale(), q.missingStructureOwner(),
								q.loadingNotQueued(), q.loadingStale(), q.oldestLoadingMs(), q.ownedNeighborMisses(), q.farthestMissingFromAnchor());
					}
					@Override public net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.PregenTickets pregenTicketSnapshot() {
						var t = OceanCanvasSurfaceFlattener.pregenTicketDiagnostics();
						return new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.PregenTickets(
								t.selfActive(), t.selfInstalls(), t.selfReleases(), t.carveLaneActive(),
								t.processingLeaseActive(), t.processingLeaseInstalls(), t.processingLeaseReleases(),
								t.finalDrainActive(), t.finalDrainInstalls(), t.finalDrainReleases(),
								t.targetFutures(), t.supportFutures(), t.supportFuturesDone(),
								t.fullDemandRequests(), t.fullDemandCompletions(), t.fullDemandFailures(), t.fullDemandCancellations(),
								t.loadRescueActive(), t.loadRescueRequests(), t.loadRescueSuccesses(),
								t.auditPending(), t.auditRepeatPending(), t.auditRejections(), t.auditRepairs(), t.auditRepeatFailures());
					}
					@Override public java.util.List<net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.TicketOwnership> ticketOwnershipSnapshot(net.minecraft.server.level.ServerLevel world) {
						java.util.ArrayList<net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.TicketOwnership> out = new java.util.ArrayList<>();
						for (var e : OceanCanvasSurfaceFlattener.ticketOwnershipSnapshot()) out.add(new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.TicketOwnership(
								e.pool(), e.chunkX(), e.chunkZ(), e.ageMillis(), e.owner(), e.purpose(), e.releaseCondition()));
						var r = net.oceancanvas.mod.restore.OceanCanvasRestoreManager.ticketOwnershipSnapshot();
						if (r != null) out.add(new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.TicketOwnership(
								"restore-forced", r.chunkX(), r.chunkZ(), r.ageMillis(), r.owner(), r.purpose(), r.releaseCondition()));
						return java.util.List.copyOf(out);
					}
					@Override public net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.Lighting lightingSnapshot() {
						return new net.oceancanvas.mod.lifecycle.OceanCanvasTerrainRuntimeDiagnostics.Lighting(
								OceanCanvasSurfaceFlattener.pendingLightSyncCount(), OceanCanvasSurfaceFlattener.activeLightSyncCount(),
								OceanCanvasSurfaceFlattener.persistentSkyBackoffCount(), OceanCanvasSurfaceFlattener.activeLightResidencyTicketCount(),
								OceanCanvasSurfaceFlattener.lightFinalizationPublishCount(), OceanCanvasSurfaceFlattener.activeLightOnlyRecoveryWorkCount(),
								OceanCanvasSurfaceFlattener.trackedLightOnlyRecoveryWorkCount(), OceanCanvasSurfaceFlattener.activePhysicalRecoveryWorkCount(),
								OceanCanvasSurfaceFlattener.trackedPhysicalRecoveryWorkCount(), OceanCanvasSurfaceFlattener.newTerrainRetirementCount());
					}
				});
		net.oceancanvas.mod.project.OceanCanvasPhysicalHealthScanner.register();
        net.oceancanvas.mod.pregen.OceanCanvasPregenQueue.register();

		// Real feature request: minimap integration showing protected
		// zones - see OceanCanvasZoneSyncPayload's class doc for why
		// this needed to be built from scratch (zero networking existed
		// in this project before this).
		OceanCanvasNetworking.register();

		// Per-region "keep hostile mobs out" rule - inert (one boolean
		// check per entity load) until a region actually asks for it.
		// See OceanCanvasMobSuppressor's class doc for exactly what it
		// touches and, more importantly, what it deliberately does not.
		net.oceancanvas.mod.worldgen.OceanCanvasMobSuppressor.register();

		// Touch the config once at startup so oceancanvas.properties gets
		// created (with defaults) on the very first run, before anyone
		// creates a world.
		OceanCanvasConfig config = OceanCanvasConfig.get();
		LOGGER.info(
				"Ocean Canvas development build {} loaded - canvas {}x{} centered at ({}, {}), expansion {}",
				VERSION, config.canvasSize(), config.canvasSize(),
				config.centerX(), config.centerZ(),
				config.expansionEnabled() ? "enabled" : "disabled"
		);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			StatusCommand.register(dispatcher);
			PregenCommand.register(dispatcher);
			RewipeCommand.register(dispatcher);
			RestoreCommand.register(dispatcher);
			ProtectCommand.register(dispatcher);
			ExpandCommand.register(dispatcher);
			StructureRulesCommand.register(dispatcher);
			UndoCommand.register(dispatcher);
			RedoCommand.register(dispatcher);
			BoundaryCommand.register(dispatcher);
			CoverageCommand.register(dispatcher);
			HealthCommand.register(dispatcher);
			InspectCommand.register(dispatcher);
			PlanCommand.register(dispatcher);
			ProjectCommand.register(dispatcher);

			// Round 9 additions (2026-08-19) - see docs/roadmap.md for the
			// full round log. AdminCommand/ChunkyExportCommand/SetupCommand
			// are ordinary new subcommands, order-independent like every
			// other class here.
			AdminCommand.register(dispatcher);
			ChunkyExportCommand.register(dispatcher);
			SetupCommand.register(dispatcher);
			SelfTestCommand.register(dispatcher);
			ForeverWorldCommand.register(dispatcher);
			DeploymentCommand.register(dispatcher);
			ReleaseCommand.register(dispatcher);
			DiagnosticCommand.register(dispatcher);
			ReportCommand.register(dispatcher);
			HarnessCommand.register(dispatcher);
			// AliasCommand MUST be registered LAST - it redirects "/oc" to
			// the "oceancanvas" node as it exists at THIS moment, so every
			// other oceancanvas command class above needs to have already
			// registered its subtree first. See AliasCommand's class doc.
			AliasCommand.register(dispatcher);
		});

		// Real, likely-major fix for the "gravel/sand/ice blob" reports
		// that kept recurring across multiple test rounds despite every
		// structure/material-based fix attempt - none of which could
		// ever have addressed the actual cause. Confirmed via a log line
		// noticed way back in this project ("Changing simulation
		// distance to 12, from 0" vs. "Changing view distance to 16"):
		// vanilla, by default, keeps a ring of chunks (the gap between
		// simulation and view distance - 4 chunks in that log) LOADED
		// AND VISIBLE to the player, but NOT "ticking". The flattener's
		// isPositionTicking check exists specifically because placing
		// water needs a chunk to be ticking (see
		// OceanCanvasSurfaceFlattener's class doc for the full
		// thread-dump-based history on why) - so any chunk the player
		// can see but that sits in that gap was PERMANENTLY unable to
		// pass the check, no matter how long anyone waited or how the
		// force-load logic was tuned, since simulation distance is a
		// separate, harder limit that force-loading chunk data doesn't
		// affect at all. Fixed by raising simulation distance to match
		// view distance at server start, closing that gap entirely.
		// v253.72.7: reset the cross-thread preemption latch for each integrated/
		// dedicated server session. SERVER_STARTING is intentionally earlier than
		// any Ocean Canvas tick worker.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime.open(server);
			net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.resetForServerStart();
			net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder.clearSession();
		});

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			net.oceancanvas.mod.lifecycle.OceanCanvasShutdownCoordinator.request("fabric-server-stopping");
			// Save & Quit must not wait on Ocean Canvas' transient chunk queues OR
			// final-drain FORCED tickets. Release those tickets while the level is
			// still alive, before clearing our bookkeeping.
			PregenManager.onServerStopping();
			net.oceancanvas.mod.pregen.OceanCanvasUndoManager.onServerStopping();
			net.oceancanvas.mod.restore.OceanCanvasRestoreManager.onServerStopping(server);
			net.oceancanvas.mod.project.OceanCanvasDeepHealthService.onServerStopping();
			OceanCanvasSurfaceFlattener.onServerStopping(server.overworld());
			net.oceancanvas.mod.diagnostic.OceanCanvasAutoDiagnostics.finishSession(server.overworld());
			net.oceancanvas.mod.project.OceanCanvasP1W3Service.finishSession(server.overworld());
			net.oceancanvas.mod.project.OceanCanvasReadOnlyApi.stop();
			net.oceancanvas.mod.project.OceanCanvasCollaborationPresence.clear();
			net.oceancanvas.mod.worldgen.OceanCanvasBiomeMasker.clearCache();
			net.oceancanvas.mod.lifecycle.OceanCanvasServerRuntime.close(server);
		});

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// v75: do NOT globally raise simulation distance to view distance. That
			// old workaround made exploration significantly more expensive by
			// turning the entire visible radius into ticking chunks. Correct Pregen
			// now physically prepares the region ahead of play; ordinary processed
			// chunks are skipped immediately on load. Structure relocation that
			// genuinely requires ticking remains deferred until its local chunks are
			// naturally ticking instead of changing the player's global setting.
			LOGGER.info("(Ocean Canvas) v230.5 performance policy: preserving configured simulation distance {} (view distance {}).",
					server.getPlayerList().getSimulationDistance(), server.getPlayerList().getViewDistance());

			// The biome masker caches registry holders, which belong to the
			// world that resolved them - clearing on start means a
			// singleplayer session that loads a second world never hands
			// out a biome reference from the first one's registries.
			net.oceancanvas.mod.worldgen.OceanCanvasBiomeMasker.clearCache();

			// Re-apply the persisted world border sync flag every start -
			// vanilla's WorldBorder isn't itself persisted by this mod
			// (only whether/where it should sync is), and a fresh/vanilla
			// world otherwise starts with an unsynced border even if this
			// was previously turned on. Safe/cheap when the flag is off
			// too - see BoundaryCommand.apply's doc.
			BoundaryCommand.apply(server.overworld(), OceanCanvasConfig.get());
			net.oceancanvas.mod.project.OceanCanvasP1W3Service.startSession(server.overworld());
		});
        // Registered after Ocean Canvas tick workers so its END hook includes their work.
        net.oceancanvas.mod.pregen.OceanCanvasTickTelemetry.register();
        // P2 sampler is registered after all Ocean Canvas tick workers so its one-second
        // black-box samples include the completed controller/flattener phase spans.
        net.oceancanvas.mod.project.OceanCanvasPerformanceEngine.register();
	}
	private static void verifyRuntimeBuildIdentity() {
		String expectedMetadataVersion = "26.2-" + VERSION;
		String metadataVersion = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("missing");
		String gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().toString();
		if (!expectedMetadataVersion.equals(metadataVersion)) {
			throw new IllegalStateException("Ocean Canvas build identity mismatch: code=" + VERSION
					+ " metadata=" + metadataVersion + " expected=" + expectedMetadataVersion
					+ " gameDir=" + gameDir);
		}
		LOGGER.warn(
				"(Ocean Canvas) RUNTIME-BUILD-IDENTITY build={} metadata={} gameDir={} action=verify-this-line-before-runtime-testing",
				VERSION, metadataVersion, gameDir);
		try {
			java.nio.file.Path marker = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("oceancanvas-runtime-build.txt");
			java.nio.file.Files.createDirectories(marker.getParent());
			java.nio.file.Files.writeString(marker,
					"build=" + VERSION + "\nmetadata=" + metadataVersion + "\ngameDir=" + gameDir + "\n",
					java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			LOGGER.warn("(Ocean Canvas) RUNTIME-BUILD-IDENTITY marker-write-failed build={} reason={}", VERSION, e.toString());
		}
	}

}
