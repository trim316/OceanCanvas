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
                .requires(Permissions.require("oceancanvas.protect",2)).executes(c->run(c.getSource()))));
    }
    private static int run(CommandSourceStack source){
        try{var r=OceanCanvasDiagnosticBundle.create(source.getLevel());source.sendSuccess(()->Component.literal("Diagnostic bundle "+r.supportCode()+" written to "+r.file()+" ("+r.bytes()+" bytes)."),false);return 1;}
        catch(Exception e){source.sendFailure(Component.literal("OC-D001: Diagnostic export failed: "+e.getMessage()));return 0;}
    }
}
