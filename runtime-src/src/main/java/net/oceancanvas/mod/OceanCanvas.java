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
import net.oceancanvas.mod.server.ShipwreckStructureRuntime;
import net.oceancanvas.mod.server.MonumentStructureRuntime;
import net.oceancanvas.mod.server.OperatorControlRuntime;
import net.oceancanvas.mod.server.StructureRuleCompatibilityRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ocean Canvas Core architectural restart bootstrap. */
public final class OceanCanvas implements ModInitializer {
    public static final String MOD_ID = "oceancanvas";
    public static final String VERSION = "core-v0.2.30-dev";
    public static final Logger LOGGER = LoggerFactory.getLogger("Ocean Canvas Core");

    @Override
    public void onInitialize() {
        try {
            var configDir = FabricLoader.getInstance().getConfigDir();
            CoreConfig config = CoreConfig.loadOrCreate(configDir);

            // Existing restart-era proof/runtime lanes.
            SingleChunkServerRuntime.register(configDir);
            TwoChunkServerRuntime.register(configDir);
            FourChunkServerRuntime.register(configDir);
            NineChunkServerRuntime.register(configDir);
            SixteenChunkServerRuntime.register(configDir);
            BoundedCampaignServerRuntime.register(configDir);

            // Production terrain path: command-driven full configured Canvas with durable preimages.
            FullCanvasServerRuntime.register(configDir);

            // Optional post-flatten structures use vanilla 26.2 placement grids and the world's seed.
            ShipwreckStructureRuntime.register(configDir);
            MonumentStructureRuntime.register(configDir);

            // Required P0-P2 power-user command surface.
            OperatorControlRuntime.register(configDir);

            // Registered last so changing shipwreck mode preserves monument/portal keys in the shared file.
            StructureRuleCompatibilityRuntime.register(configDir);

            if (config.singleChunkAuthorityEnabled()) {
                LOGGER.warn("(Ocean Canvas Core) ARCHITECTURAL-RESTART build={} startupConfiguredMode={} startupAuthority={} action=server-start-will-revalidate-exact-gate target={},{}",
                        VERSION, config.mode(), OperationMode.CORE_AUTHORING, config.singleChunkX(), config.singleChunkZ());
            } else {
                LOGGER.warn("(Ocean Canvas Core) ARCHITECTURAL-RESTART build={} startupConfiguredMode={} startupAuthority={} action=inert-adapter-registered-server-start-will-reload-config",
                        VERSION, config.mode(), OperationMode.SAFE_HOLD);
            }
            LOGGER.info("Ocean Canvas Core {} loaded: canvas={}x{} center={},{} waterY={} floorY={} variation={} expansion={} singleChunkEnabled={} legacyRuntimeImported=false clientRuntime=false",
                    VERSION, config.canvasSize(), config.canvasSize(), config.centerX(), config.centerZ(),
                    config.waterSurfaceY(), config.oceanFloorY(), config.oceanFloorVariation(), config.expansionEnabled(), config.singleChunkEnabled());
        } catch (Exception e) {
            LOGGER.error("Ocean Canvas Core could not initialize; runtime remains fail-closed and no world mutation was registered.", e);
        }
    }
}
