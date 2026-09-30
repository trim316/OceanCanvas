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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ocean Canvas Core architectural restart bootstrap. */
public final class OceanCanvas implements ModInitializer {
    public static final String MOD_ID = "oceancanvas";
    public static final String VERSION = "core-v0.2.26";
    public static final Logger LOGGER = LoggerFactory.getLogger("Ocean Canvas Core");

    @Override
    public void onInitialize() {
        try {
            var configDir = FabricLoader.getInstance().getConfigDir();
            CoreConfig config = CoreConfig.loadOrCreate(configDir);

            // v0.2.25: retain the inert server lifecycle adapter and bounded G16 residency recovery. It reloads
            // the config exactly once for each newly-created integrated/dedicated
            // server instance. This lets the one-click harness arm while Minecraft
            // remains at the title screen without requiring a full client restart.
            // No authoring is possible unless the complete destructive gate is valid
            // when that server instance starts.
            SingleChunkServerRuntime.register(configDir);
            // Separate opt-in config; registration alone grants no authority.
            // Every new server revalidates both explicit destructive tokens.
            TwoChunkServerRuntime.register(configDir);
            // Four-chunk lane is independently armed and mutually exclusive
            // with the smaller scale canary. Registration alone is inert.
            FourChunkServerRuntime.register(configDir);
            // Nine-chunk 3x3 lane is independently armed, immutable before
            // first authoring, and mutually exclusive with smaller scale gates.
            NineChunkServerRuntime.register(configDir);
            // Sixteen-chunk 4x4 lane is separately armed and preserves the
            // same one-active-ticket authority boundary proven at smaller scales.
            SixteenChunkServerRuntime.register(configDir);
            // Larger bounded campaign lane remains separately armed, exact-square scoped,
            // mutually exclusive with all smaller scale gates, and inert without explicit consent.
            BoundedCampaignServerRuntime.register(configDir);

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
