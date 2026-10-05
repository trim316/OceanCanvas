package net.oceancanvas.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.oceancanvas.core.config.CoreConfig;
import net.oceancanvas.core.pipeline.OperationMode;
import net.oceancanvas.mod.server.SingleChunkServerRuntime;
import net.oceancanvas.mod.server.TwoChunkServerRuntime;
import net.oceancanvas.mod.server.FourChunkServerRuntime;
import net.oceancanvas.mod.server.NineChunkServerRuntime;
import net.oceancanvas.mod.server.SixteenChunkServerRuntime;
import net.oceancanvas.mod.server.BoundedCampaignServerRuntime;
import net.oceancanvas.mod.server.FullCanvasServerRuntime;
import net.oceancanvas.mod.server.ProductionScaleServerRuntime;
import net.oceancanvas.mod.server.ProductionFingerprintCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ocean Canvas Core runtime bootstrap. */
public final class OceanCanvas implements ModInitializer {
    public static final String MOD_ID = "oceancanvas";
    public static final String VERSION = "core-v1.0-rc.1";
    public static final Logger LOGGER = LoggerFactory.getLogger("Ocean Canvas Core");

    @Override
    public void onInitialize() {
        try {
            var configDir = FabricLoader.getInstance().getConfigDir();
            CoreConfig config = CoreConfig.loadOrCreate(configDir);

            // Recovery/correctness lanes remain registered but inert unless their
            // separate destructive gates are explicitly armed.
            SingleChunkServerRuntime.register(configDir);
            TwoChunkServerRuntime.register(configDir);
            FourChunkServerRuntime.register(configDir);
            NineChunkServerRuntime.register(configDir);
            SixteenChunkServerRuntime.register(configDir);
            BoundedCampaignServerRuntime.register(configDir);

            // v0.2.28 exact-preimage full-Canvas command remains available as a
            // bounded recovery/correctness canary. It is not the v1 production
            // scheduler and is deliberately refused when a production lifecycle
            // overlaps the same world.
            FullCanvasServerRuntime.register(configDir);

            // v1 production lane: bounded multi-chunk terrain admission,
            // decoupled lighting, coarse durable checkpoints and native
            // seed-bound Restore, reconciled from the v253.125.54 architecture.
            ProductionScaleServerRuntime.register(configDir);
            // Read-only, hard-bounded oracle for disposable native-regeneration
            // proofs. Refuses regions larger than 16 chunks.
            ProductionFingerprintCommand.register(configDir);

            if (config.singleChunkAuthorityEnabled()) {
                LOGGER.warn("(Ocean Canvas Core) ARCHITECTURAL-RESTART build={} startupConfiguredMode={} startupAuthority={} action=server-start-will-revalidate-exact-gate target={},{}",
                        VERSION, config.mode(), OperationMode.CORE_AUTHORING, config.singleChunkX(), config.singleChunkZ());
            } else {
                LOGGER.warn("(Ocean Canvas Core) ARCHITECTURAL-RESTART build={} startupConfiguredMode={} startupAuthority={} action=inert-adapters-registered-server-start-will-reload-config",
                        VERSION, config.mode(), OperationMode.SAFE_HOLD);
            }
            LOGGER.info("Ocean Canvas Core {} loaded: canvas={}x{} center={},{} waterY={} floorY={} variation={} expansion={} singleChunkEnabled={} productionBaseline={} productionSourceSha256={} clientRuntime=false",
                    VERSION, config.canvasSize(), config.canvasSize(), config.centerX(), config.centerZ(),
                    config.waterSurfaceY(), config.oceanFloorY(), config.oceanFloorVariation(), config.expansionEnabled(), config.singleChunkEnabled(),
                    ProductionScaleServerRuntime.ENGINE_BASELINE, ProductionScaleServerRuntime.ENGINE_SOURCE_SHA256);
        } catch (Exception e) {
            LOGGER.error("Ocean Canvas Core could not initialize; runtime remains fail-closed and no world mutation was registered.", e);
        }
    }
}
