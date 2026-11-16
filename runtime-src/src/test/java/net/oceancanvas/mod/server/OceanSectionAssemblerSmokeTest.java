package net.oceancanvas.mod.server;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Small deterministic assertions runnable by a Minecraft-aware test harness. */
public final class OceanSectionAssemblerSmokeTest {
    public static void main(String[] args) {
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
        System.out.println("Ocean section assembler smoke test passed");
    }
}
