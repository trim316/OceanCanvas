package net.oceancanvas.mod.server;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Small deterministic assertions runnable by a Minecraft-aware test harness. */
public final class OceanSectionAssemblerSmokeTest {
    public static void main(String[] args) {
        net.minecraft.server.Bootstrap.bootStrap();
        BlockState[] states = OceanSectionAssembler.assemble(-2, 3, 0, 8, (x, z) -> 3);
        if (states.length != 4096) throw new AssertionError("section volume");
        for (int z=0; z<16; z++) for (int x=0; x<16; x++) for (int y=0; y<16; y++) {
            BlockState expected = y < 3 ? Blocks.STONE.defaultBlockState()
                    : y <= 8 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (!states[OceanSectionAssembler.index(x,y,z)].equals(expected))
                throw new AssertionError("incorrect canonical state");
        }
        BlockState[] below = OceanSectionAssembler.assemble(0, 0, -1, 8, (x,z) -> 3);
        for (BlockState state : below) if (!state.is(Blocks.STONE))
            throw new AssertionError("negative section");
        try {
            OceanSectionAssembler.assemble(0,0,0,8,(x,z)->9);
            throw new AssertionError("invalid floor accepted");
        } catch (IllegalArgumentException expected) {}
        // Exhaustively compare optimized output to independent per-cell oracle
        // across varying floors, negative chunk coordinates and section heights.
        for (int chunkX : new int[]{-4,0,7})
            for (int chunkZ : new int[]{-3,0,5})
                for (int sectionY : new int[]{-4,-1,0,2,4,6}) {
                    int water = 63;
                    OceanSectionAssembler.FloorSampler varying =
                        (wx,wz) -> 16 + Math.floorMod(wx*13 + wz*7, 23);
                    BlockState[] actual = OceanSectionAssembler.assemble(
                        chunkX,chunkZ,sectionY,water,varying);
                    for (int z=0;z<16;z++) for (int x=0;x<16;x++) for (int y=0;y<16;y++) {
                        int floor = varying.floorY(chunkX*16+x,chunkZ*16+z);
                        BlockState expected = OceanTerrainTarget.blockAt(sectionY*16+y,floor,water);
                        if (!actual[OceanSectionAssembler.index(x,y,z)].equals(expected))
                            throw new AssertionError("oracle mismatch");
                    }
                }
        // Uniform-section fast paths must still reject invalid columns.
        try {
            OceanSectionAssembler.assemble(0,0,6,63,(wx,wz)->wx==15 && wz==15 ? 64 : 12);
            throw new AssertionError("uniform shortcut skipped validation");
        } catch (IllegalArgumentException expected) {}
        System.out.println("Ocean section assembler smoke test passed");
    }
}
