package net.oceancanvas.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.oceancanvas.mod.config.OceanCanvasConfig;
import net.oceancanvas.mod.worldgen.OceanCanvasActiveTerrainOperationBridge;
import net.oceancanvas.mod.worldgen.OceanCanvasOceanVegetation;
import net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener;
import java.nio.file.*;
import java.util.*;

/** Test-only companion for the immutable candidate; never packaged in its JAR. */
public final class FocusedVegetationProbe implements ModInitializer {
    private int phase;
    private List<BlockPos> cells;
    private final Path flag = Path.of("run-focused-vegetation-probe.flag");
    private final Path snapshot = Path.of("focused-vegetation-probe-snapshot.tsv");
    private static boolean aquatic(BlockState s) {
        return s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS)
                || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT);
    }
    private static List<BlockPos> fixture(ServerLevel world) {
        var config = OceanCanvasConfig.get();
        for (int cx=-1;cx<=1;cx++) for(int cz=-1;cz<=1;cz++) world.getChunk(cx,cz);
        var cells = new ArrayList<BlockPos>();
        for(int x=-16;x<32;x++) for(int z=-16;z<32;z++)
            for(int y=config.oceanFloorY()-config.oceanFloorVariation()-2;y<=62;y++)
                cells.add(new BlockPos(x,y,z));
        return cells;
    }
    private static TreeMap<String,String> plants(ServerLevel world, List<BlockPos> cells) {
        var result = new TreeMap<String,String>();
        for(var at:cells) {
            var state=world.getBlockState(at);
            if(aquatic(state)) result.put(at.getX()+","+at.getY()+","+at.getZ(),state.toString());
        }
        return result;
    }
    private static TreeMap<String,String> underground(ServerLevel world) {
        var result = new TreeMap<String,String>();
        var config=OceanCanvasConfig.get();
        int y=config.oceanFloorY()-config.oceanFloorVariation()-20;
        for(int x=-16;x<32;x+=4) for(int z=-16;z<32;z+=4)
            result.put(x+","+y+","+z,world.getBiome(new BlockPos(x,y,z)).toString());
        return result;
    }
    private static void require(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
    private static void decorate(ServerLevel world) {
        require(OceanCanvasOceanVegetation.decorateCommittedChunk(world,new ChunkPos(0,0)),"decoration returned false");
    }
    private static void clear(ServerLevel world,List<BlockPos> cells) {
        for(var at:cells) if(aquatic(world.getBlockState(at)))
            world.setBlock(at,Blocks.WATER.defaultBlockState(),2);
    }
    private static void failureWithholding(ServerLevel world) throws Exception {
        var field=OceanCanvasActiveTerrainOperationBridge.class.getDeclaredField("controller");
        field.setAccessible(true);
        Object original=field.get(null);
        int[] commits={0};
        var type=OceanCanvasActiveTerrainOperationBridge.Controller.class;
        var wrapper=(OceanCanvasActiveTerrainOperationBridge.Controller)java.lang.reflect.Proxy.newProxyInstance(
            type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                if(method.getName().equals("authoritativeCommit")) { commits[0]++; return null; }
                try { return method.invoke(original,args); }
                catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
        try {
            OceanCanvasActiveTerrainOperationBridge.install(wrapper);
            OceanCanvasActiveTerrainOperationBridge.authoritativeCommit(world,new ChunkPos(1000000,1000000));
            require(commits[0]==0,"unavailable decoration target received an authoritative commit");
        } finally {
            OceanCanvasActiveTerrainOperationBridge.install((OceanCanvasActiveTerrainOperationBridge.Controller)original);
        }
    }
    @Override public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(phase==3 || !Files.exists(flag)) return;
            try {
                var world=server.overworld();
                // Controlled fixture only; the accepted generation evidence is separate.
                world.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,server);
                if(cells==null) cells=fixture(world);
                if(phase==0 && Files.exists(snapshot)) {
                    var expected=new TreeMap<String,String>();
                    for(var line:Files.readAllLines(snapshot)) {
                        int tab=line.indexOf('\t');
                        expected.put(line.substring(0,tab),line.substring(tab+1));
                    }
                    require(expected.equals(plants(world,cells)),"saved/reload aquatic state changed");
                    System.out.println("FOCUSED_VEGETATION_PERSISTENCE_PASS"); phase=3; return;
                }
                if(phase==0) {
                    var deep=underground(world);
                    for(var at:cells) {
                        var state=world.getBlockState(at);
                        if(aquatic(state)) require(state.canSurvive(world,at),"invalid saved plant support at "+at+": "+state);
                    }
                    decorate(world);
                    var first=plants(world,cells);
                    decorate(world);
                    require(first.equals(plants(world,cells)),"retry changed aquatic states/footprint");
                    clear(world,cells); decorate(world);
                    var wiped=plants(world,cells);
                    clear(world,cells); decorate(world);
                    require(wiped.equals(plants(world,cells)),"identical rewipe changed aquatic states/footprint");
                    require(deep.equals(underground(world)),"decoration changed underground biome cells");
                    failureWithholding(world);
                    System.out.println("FOCUSED_VEGETATION_RETRY_REWIPE_SUPPORT_UNDERGROUND_WITHHOLD_PASS");
                    phase=1;
                }
                if(phase==1 && OceanCanvasSurfaceFlattener.pendingLightSyncCount()==0) {
                    var rows=new ArrayList<String>();
                    plants(world,cells).forEach((key,value)->rows.add(key+"\t"+value));
                    Files.write(snapshot,rows);
                    System.out.println("FOCUSED_VEGETATION_PROBE_SAVE_READY"); phase=3;
                }
            } catch(Throwable failure) {
                phase=3;
                failure.printStackTrace();
                System.out.println("FOCUSED_VEGETATION_PROBE_FAILED "+failure);
            }
        });
    }
}

