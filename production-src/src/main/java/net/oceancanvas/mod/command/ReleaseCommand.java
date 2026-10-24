package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.project.OceanCanvasReleaseReadiness;

public final class ReleaseCommand {
    private ReleaseCommand(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var release=Commands.literal("release")
                .then(Commands.literal("audit").executes(c->audit(c.getSource())))
                .then(Commands.literal("certify")
                        .then(Commands.argument("gate",StringArgumentType.word())
                                .then(Commands.argument("evidence",StringArgumentType.greedyString())
                                        .executes(c->message(c.getSource(),OceanCanvasReleaseReadiness.certify(c.getSource().getLevel(),StringArgumentType.getString(c,"gate"),actor(c.getSource()),StringArgumentType.getString(c,"evidence")))))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("gate",StringArgumentType.word())
                                .then(Commands.argument("reason",StringArgumentType.greedyString())
                                        .executes(c->message(c.getSource(),OceanCanvasReleaseReadiness.revoke(c.getSource().getLevel(),StringArgumentType.getString(c,"gate"),actor(c.getSource()),StringArgumentType.getString(c,"reason")))))));
        dispatcher.register(Commands.literal("oceancanvas").then(release));
    }
    private static int audit(CommandSourceStack s){var r=OceanCanvasReleaseReadiness.audit(s.getLevel());s.sendSuccess(()->Component.literal("Ocean Canvas 1.0 readiness: blocked="+r.blocked()+", runtime-required="+r.runtimeRequired()+", release-ready="+r.releaseReady()+"."),false);for(var g:r.gates())s.sendSuccess(()->Component.literal(" - "+g.id()+" "+g.name()+": "+g.state()+" — "+g.detail()),false);return r.releaseReady()?1:0;}
    private static int message(CommandSourceStack s,String text){s.sendSuccess(()->Component.literal(text),false);return text.toLowerCase(java.util.Locale.ROOT).contains("rejected")?0:1;}
    private static String actor(CommandSourceStack s){return s.getTextName();}
}
