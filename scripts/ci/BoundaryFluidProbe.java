package net.oceancanvas.test;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
public final class BoundaryFluidProbe implements ModInitializer {
 private static int ticks,writes;
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(server->{
  ticks++;
  if(ticks%200==0){ServerLevel w=server.getLevel(Level.OVERWORLD);if(w!=null)System.out.println("BOUNDARY_FLUID_SNAPSHOT tick="+ticks+" "+cells(w));}
  if(ticks==3600)System.out.println("BOUNDARY_FLUID_DONE verdict=HOLD");
 });}
 private static String cells(ServerLevel w){StringBuilder s=new StringBuilder();for(int y=63;y<=65;y++)for(int z=-251;z<=-250;z++){
  BlockPos p=new BlockPos(-23,y,z);var c=w.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
  s.append(p).append('=').append(c==null?"UNLOADED":c.getBlockState(p)).append(';');}return s.toString();}
 public static void changed(ServerLevel w,BlockPos p,BlockState state,boolean changed){
  if(!changed||w.dimension()!=Level.OVERWORLD||p.getX()<-32||p.getX()>-17||p.getZ()<-251||p.getZ()>-249||p.getY()<63||p.getY()>80)return;
  int n=++writes;if(n>32&&(n&255)!=0)return;
  String caller=StackWalker.getInstance().walk(fs->fs.filter(f->!f.getClassName().startsWith("net.oceancanvas.test.")&&!f.getClassName().equals("net.minecraft.world.level.Level")).limit(6).map(Object::toString).reduce((a,b)->a+" <- "+b).orElse("unknown"));
  System.out.println("BOUNDARY_FLUID_WRITE ordinal="+n+" pos="+p+" state="+state+" caller="+caller+" cells="+cells(w));
 }
}
