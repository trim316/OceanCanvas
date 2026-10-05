package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

/**
 * {@code /oceancanvas coverage} - the "world-gen coverage map" half of this
 * round's follow-up request: an at-a-glance ASCII grid of how much of the
 * canvas has actually been flattened so far, without needing to fly the
 * whole thing yourself or trust {@code /oceancanvas stats}'s single
 * cumulative counter (which can't tell you WHERE the remaining work is).
 *
 * <p><b>Ground-truth sampling, not persisted tracking state.</b> Every
 * cell in the grid is classified by reading the ACTUAL block state at one
 * representative column right now, the same "just ask the world" approach
 * {@code isInsideCanvas}/{@code hasChunk} checks already use elsewhere in
 * this mod - not by recording a running "flattened chunks" set as work
 * happens. A tracked-set alternative was considered and rejected: a large
 * canvas (the whole point of this mod) means up to millions of chunk
 * entries, real persisted memory/disk cost, for a feature that only ever
 * needs to be an approximate, human-readable overview, never an exact
 * audit log - see {@code anyChange} in {@code OceanCanvasSurfaceFlattener}
 * for the same tradeoff already made for {@code /oceancanvas stats}.</p>
 *
 * <p><b>Bounded grid resolution ({@link #GRID_SIZE}x{@link #GRID_SIZE}),
 * not one sample per chunk.</b> A 20,000-block canvas is up to 1,250
 * chunks across - one sample per chunk would mean over a million block
 * reads and a chat message far too large to be readable anyway. Each grid
 * cell instead represents a square block of the canvas roughly {@code
 * canvasSize / GRID_SIZE} blocks on a side, sampled at its center column
 * only. This is a genuine approximation - a cell can legitimately contain
 * a mix of flattened and natural terrain and only report whichever the
 * center happens to be - honestly documented rather than presented as
 * exact.</p>
 *
 * <p><b>Only reads already-loaded chunks - never force-loads anything.</b>
 * Unlike {@code /oceancanvas pregen}, this command is meant to be cheap
 * and instant, safe to run at any time without kicking off real server
 * work. A cell whose chunk isn't currently loaded (checked via {@code
 * ServerLevel#hasChunk}, the same non-blocking check {@code
 * OceanCanvasSurfaceFlattener} already relies on) is reported as unknown
 * rather than guessed at.</p>
 *
 * <p><b>Classification approximation:</b> a cell counts as flattened if
 * its sample column reads vanilla water at {@code WATER_SURFACE_Y - 1} -
 * the one Y level that's always inside the flattener's target "water"
 * range ({@code floorY} to {@code waterTop}) for every column on the
 * canvas, regardless of that column's own floor-height variation (see
 * {@code OceanCanvasSurfaceFlattener#flattenChunk}'s target-state loop).
 * A player-built structure sitting exactly on that one column of an
 * otherwise-flattened cell could misreport as "natural" - a real but
 * minor false-negative risk for a purely informational overview command,
 * not worth a full-column scan per cell.</p>
 *
 * <p><b>Protected zones overlay on top</b> - a cell whose center falls
 * inside a currently-protected {@link OceanCanvasPlayerZones} zone is
 * always shown as protected regardless of what's actually there, since
 * that's the more useful fact for planning around it (it won't change
 * from a reset/expand either way).</p>
 *
 * <p><b>Rendered as plain-text ASCII, not client-rendered map art</b> -
 * simplest possible approach, and honestly caveated in the output itself:
 * vanilla's default chat font isn't monospace, so a grid built from
 * character width alone can visually skew on some clients/resource
 * packs. Good enough for "where's the work left", not pixel-precise.</p>
 */
public final class CoverageCommand {

	private static final int GRID_SIZE = 32;

	private CoverageCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("oceancanvas")
						.then(Commands.literal("coverage")
								// Read-only, cheap (bounded to GRID_SIZE^2 block
								// reads of already-loaded chunks, no force-loading)
								// - same "safe for any player" rationale as
								// status/here/stats.
								.executes(CoverageCommand::run))
		);
	}

	private static int run(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerLevel world = source.getLevel();
		OceanCanvasConfig config = OceanCanvasConfig.get();
		var zones = OceanCanvasPlayerZones.get(world).all();

		int radius = config.radius();
		int minCorner = config.centerX() - radius;
		int minCornerZ = config.centerZ() - radius;
		double cellSize = (radius * 2.0) / GRID_SIZE;
		int sampleY = OceanCanvasConfig.WATER_SURFACE_Y - 1;

		int flattened = 0;
		int natural = 0;
		int unloaded = 0;
		int protectedCount = 0;

		StringBuilder grid = new StringBuilder();
		for (int row = 0; row < GRID_SIZE; row++) {
			for (int col = 0; col < GRID_SIZE; col++) {
				int x = (int) (minCorner + (col + 0.5) * cellSize);
				int z = (int) (minCornerZ + (row + 0.5) * cellSize);
				BlockPos pos = new BlockPos(x, sampleY, z);

				boolean protectedHere = false;
				for (OceanCanvasPlayerZones.Zone zone : zones) {
					if (zone.protectedNow() && zone.contains(pos.getX(), pos.getY(), pos.getZ())) {
						protectedHere = true;
						break;
					}
				}

				char symbol;
				if (protectedHere) {
					symbol = 'P';
					protectedCount++;
				} else if (!world.hasChunk(x >> 4, z >> 4)) {
					symbol = '?';
					unloaded++;
				} else if (world.getBlockState(pos).is(Blocks.WATER)) {
					symbol = '#';
					flattened++;
				} else {
					symbol = '.';
					natural++;
				}
				grid.append(symbol);
			}
			grid.append('\n');
		}

		int totalCells = GRID_SIZE * GRID_SIZE;
		String summary = "[Ocean Canvas] Coverage sample (" + GRID_SIZE + "x" + GRID_SIZE + " grid, "
				+ (int) cellSize + "-block cells): " + flattened + "/" + totalCells + " flattened, "
				+ natural + " natural/unflattened, " + protectedCount + " protected, "
				+ unloaded + " unloaded (unknown). '#'=flattened '.'=natural 'P'=protected zone "
				+ "'?'=chunk not currently loaded. Approximate - see this command's class doc for why (one "
				+ "sample column per cell, and vanilla's default chat font isn't monospace so the grid may "
				+ "skew visually on some clients).";

		// Never broadcast - purely informational, doesn't change anything,
		// and a 32-line grid is exactly the kind of thing only the
		// requesting player needs to see, not the whole server.
		source.sendSuccess(() -> Component.literal(summary), false);
		source.sendSuccess(() -> Component.literal(grid.toString()), false);
		return 1;
	}
}
