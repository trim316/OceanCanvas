package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.project.OceanCanvasDiagnosticBundle;

/** Explicit, bounded support export. Never includes region/chunk terrain files. */
public final class DiagnosticCommand {
    private DiagnosticCommand(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("oceancanvas").then(Commands.literal("diagnostics")
                .requires(Permissions.require("oceancanvas.protect",2)).executes(c->run(c.getSource()))
                .then(Commands.literal("lighting").executes(c->lighting(c.getSource()))
                    .then(Commands.literal("probe")
                        .then(Commands.argument("chunkX",com.mojang.brigadier.arguments.IntegerArgumentType.integer(-1875000,1874999))
                            .then(Commands.argument("chunkZ",com.mojang.brigadier.arguments.IntegerArgumentType.integer(-1875000,1874999))
                                .executes(c->probe(c.getSource(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"chunkX"),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"chunkZ")))))))));
    }
    private static int lighting(CommandSourceStack source){
        net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener.logFinalLightDiagnostics();
        source.sendSuccess(()->Component.literal("Lighting counters written to the server log."),false);
        return 1;
    }
    private static int probe(CommandSourceStack source,int x,int z){
        if(!net.oceancanvas.mod.worldgen.OceanCanvasSurfaceFlattener.logLoadedLightProfile(source.getLevel(),x,z)){
            source.sendFailure(Component.literal("Chunk is not loaded; diagnostic did not load it."));return 0;
        }
        source.sendSuccess(()->Component.literal("Bounded lighting profile requested; inspect server log for evidence or an inconclusive probe."),false);
        return 1;
    }
    private static int run(CommandSourceStack source){
        try{var r=OceanCanvasDiagnosticBundle.create(source.getLevel());source.sendSuccess(()->Component.literal("Diagnostic bundle "+r.supportCode()+" written to "+r.file()+" ("+r.bytes()+" bytes)."),false);return 1;}
        catch(Exception e){source.sendFailure(Component.literal("OC-D001: Diagnostic export failed: "+e.getMessage()));return 0;}
    }
}
