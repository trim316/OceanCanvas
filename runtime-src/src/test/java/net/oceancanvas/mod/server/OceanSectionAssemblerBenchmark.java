package net.oceancanvas.mod.server;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;

/** Disposable in-memory assembler microbenchmark, NOT a Minecraft throughput gate. */
public final class OceanSectionAssemblerBenchmark {
    private static volatile long sink;
    private static BlockState[] reference(int cx,int cz,int sy,int surface,OceanSectionAssembler.FloorSampler floor) {
        BlockState[] out=new BlockState[4096];
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            int f=floor.floorY(cx*16+x,cz*16+z);
            for(int y=0;y<16;y++) out[(y<<8)|(z<<4)|x]=OceanTerrainTarget.blockAt(sy*16+y,f,surface);
        }
        return out;
    }
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        OceanSectionAssembler.FloorSampler floor=(x,z)->20+Math.floorMod(x*13+z*7,23);
        int[] heights={-4,0,1,2,3,4,6};
        // Warm up both paths before timing to avoid measuring first class initialization.
        for(int i=0;i<1500;i++) {
            int h=heights[i%heights.length];
            sink+=OceanSectionAssembler.assemble(i%13-6,i%11-5,h,63,floor)[0].hashCode();
            sink+=reference(i%13-6,i%11-5,h,63,floor)[0].hashCode();
        }
        int iterations=12000;
        long[] ns=new long[2];
        for(int pass=0;pass<4;pass++) {
            for(int variant=0;variant<2;variant++) {
                int which=(variant+pass)%2;
                long t=System.nanoTime();
                for(int i=0;i<iterations;i++) {
                    int h=heights[i%heights.length];
                    BlockState[] a=which==0
                        ? OceanSectionAssembler.assemble(i%13-6,i%11-5,h,63,floor)
                        : reference(i%13-6,i%11-5,h,63,floor);
                    sink+=a[(i*17)&4095].hashCode();
                }
                ns[which]+=System.nanoTime()-t;
            }
        }
        double optimized=ns[0]/1e6,baseline=ns[1]/1e6;
        System.out.printf("microbenchmark optimized_ms=%.3f baseline_ms=%.3f speedup=%.3fx sections=%d sink=%d%n",
            optimized,baseline,baseline/optimized,iterations*4,sink);
    }
}
