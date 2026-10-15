package net.oceancanvas.test;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.oceancanvas.mod.worldgen.OceanCanvasOceanVegetation;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import java.nio.file.*;
import java.util.*;
/** Diagnostic companion only. The immutable engine and original world stay untouched. */
public final class DecorationRetryProbe implements ModInitializer {
 private boolean done;
 private static boolean aquatic(BlockState s){return s.is(Blocks.SEAGRASS)||s.is(Blocks.TALL_SEAGRASS)||s.is(Blocks.KELP)||s.is(Blocks.KELP_PLANT);}
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{
  if(done||!Files.exists(Path.of("run-focused-vegetation-probe.flag")))return;
  done=true;
  try {
   var world=server.overworld(); world.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,server);
   for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)world.getChunk(x,z);
   var cells=new ArrayList<BlockPos>();
   var cfg=OceanCanvasConfig.get();
   for(int x=-16;x<32;x++)for(int z=-16;z<32;z++)for(int y=cfg.oceanFloorY()-cfg.oceanFloorVariation();y<=62;y++)cells.add(new BlockPos(x,y,z));
   // Explicit controlled rewipe, solely in the verified copied diagnostic world.
   int cleared=0;for(var at:cells)if(aquatic(world.getBlockState(at))){world.setBlock(at,Blocks.WATER.defaultBlockState(),2);cleared++;}
   System.out.println("DECORATION_RETRY_START cleared="+cleared+" scope=controlled-copy-only");
   var rows=new ArrayList<String>();
   for(int pass=0;pass<8;pass++){
    var before=new HashMap<BlockPos,BlockState>();for(var at:cells)before.put(at,world.getBlockState(at));
    long started=System.nanoTime();boolean ready=OceanCanvasOceanVegetation.decorateCommittedChunk(world,new ChunkPos(0,0));
    long nanos=System.nanoTime()-started;int changed=0,plants=0;
    for(var at:cells){var state=world.getBlockState(at);if(!state.equals(before.get(at)))changed++;if(aquatic(state))plants++;}
    String row="pass="+pass+" ready="+ready+" changedCells="+changed+" plantCells="+plants+" elapsedNanos="+nanos;
    rows.add(row);System.out.println("DECORATION_RETRY "+row);
   }
   Files.write(Path.of("decoration-retry.tsv"),rows);
   System.out.println("DECORATION_RETRY_DONE verdict=HOLD freshWorld=false");
  }catch(Throwable failure){failure.printStackTrace();System.out.println("FOCUSED_VEGETATION_PROBE_FAILED "+failure);}
 });}
}
