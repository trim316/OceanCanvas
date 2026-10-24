package net.oceancanvas.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.oceancanvas.mod.OceanCanvas;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v253.69.5 packaging hardening.
 *
 * The actual root cause of the world-creation hang: vanilla's world-spawn
 * search doesn't just avoid ocean *biomes* - it checks the real block at
 * the surface and rejects liquid. Since {@link
 * net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener} deliberately
 * turns the entire canvas into water, there is no dry land anywhere near
 * spawn for that search to ever find - the biome-source fix earlier
 * addressed a real secondary issue, but this is the actual reason the
 * game still hung afterward. Reverting the "everything is ocean" design
 * isn't the right fix; the player is *supposed* to spawn stranded in
 * open water. The right fix is to stop vanilla from searching for land
 * at all and just tell it directly where to spawn.
 *
 * <p><b>Confirmed against the real 26.2 source (via IntelliJ's
 * decompiler), not guessed:</b> the actual signature is
 * {@code private static void setInitialSpawn(ServerLevel, ServerLevelData,
 * boolean, boolean, LevelLoadListener)} - one more parameter
 * ({@code LevelLoadListener}) than the first attempt had, which is why
 * the Mixin failed to apply with an "Invalid descriptor" error on that
 * attempt. Both the method signature and the {@code LevelData.RespawnData}
 * shape used below are now confirmed straight from the decompiled jar.</p>
 * <p><b>Second confirmed issue, found after the Mixin successfully
 * applied and the world actually loaded:</b> the client's "Loading
 * terrain..." screen hung forever afterward, even though the server
 * itself finished cleanly (no exception - it saved and shut down
 * normally). Cause: vanilla's real {@code setInitialSpawn} calls
 * {@code levelLoadListener.start(Stage.PREPARE_GLOBAL_SPAWN, ...)} and
 * (almost certainly) {@code finish(...)} to signal the loading screen
 * that this stage is done. Cancelling the whole method at {@code HEAD}
 * skipped that signal entirely - the world was ready, but the client
 * was never told, so it waited forever. Fixed by sending the same
 * start/updateFocus/finish sequence ourselves (confirmed against the
 * real decompiled {@code LevelLoadListener} interface) before setting
 * the spawn point and cancelling.</p>
 * <p><b>Third confirmed issue, found once the world was genuinely
 * playable:</b> the flattened water surface and the player's spawn
 * height were computed from two different calls to
 * {@code level.getSeaLevel()} (one here, one independently in
 * {@code OceanCanvasSurfaceFlattener}) that ended up disagreeing with
 * each other and with actual vanilla sea level (y=62) - the player
 * reported drowning unexpectedly because the water around them sat at
 * a different height than where they were placed. Fixed by using the
 * same fixed {@code OceanCanvasConfig.WATER_SURFACE_Y} constant here
 * that the flattener now uses, instead of each independently deriving
 * (and disagreeing on) a "sea level" from the dynamic API.</p>
 */
@Mixin(MinecraftServer.class)
public class OceanCanvasMinecraftServerMixin {

	@Inject(method = "setInitialSpawn", at = @At("HEAD"), cancellable = true)
	private static void oceancanvas$skipVanillaSpawnSearch(
			ServerLevel level, ServerLevelData levelData, boolean spawnBonusChest, boolean isDebug,
			LevelLoadListener levelLoadListener, CallbackInfo ci) {
		if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			return;
		}
		if (isDebug) {
			return; // let vanilla handle debug worlds normally
		}

		OceanCanvasConfig config = OceanCanvasConfig.get();
		if (!config.isInsideCanvas(0, 0)) {
			return; // spawn isn't inside the configured canvas - let vanilla search as normal
		}

		BlockPos spawnPos = new BlockPos(0, OceanCanvasConfig.WATER_SURFACE_Y + 1, 0);

		// Send the same loading-screen signals vanilla would have sent,
		// just without the expensive search in between - the client
		// needs to see this stage start and finish or it hangs forever
		// waiting for a signal that will never come.
		levelLoadListener.start(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN, 0);
		levelLoadListener.updateFocus(level.dimension(), ChunkPos.containing(spawnPos));
		levelLoadListener.finish(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN);

		LevelData.RespawnData respawnData = LevelData.RespawnData.of(level.dimension(), spawnPos, 0.0F, 0.0F);
		levelData.setSpawn(respawnData);
		OceanCanvas.LOGGER.info("Skipped vanilla's land search - spawn forced to {}", spawnPos);

		ci.cancel();
	}
}
