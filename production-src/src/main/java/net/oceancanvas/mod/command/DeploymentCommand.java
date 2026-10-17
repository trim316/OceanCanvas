package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.project.OceanCanvasDeploymentService;

public final class DeploymentCommand {
    private DeploymentCommand(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("deploy").requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.protect",2));
        root.then(Commands.literal("preflight").then(Commands.argument("asset",StringArgumentType.word()).executes(c->preflight(c.getSource(),StringArgumentType.getString(c,"asset")))));
        root.then(Commands.literal("commit").then(Commands.argument("asset",StringArgumentType.word()).executes(c->message(c.getSource(),OceanCanvasDeploymentService.commit(c.getSource().getLevel(),StringArgumentType.getString(c,"asset"),actor(c.getSource()))))));
        root.then(Commands.literal("acknowledge").then(Commands.argument("asset",StringArgumentType.word()).then(Commands.argument("fingerprint",StringArgumentType.word()).executes(c->message(c.getSource(),OceanCanvasDeploymentService.acknowledgeDeployed(c.getSource().getLevel(),StringArgumentType.getString(c,"asset"),StringArgumentType.getString(c,"fingerprint"),actor(c.getSource())))))));
        root.then(Commands.literal("verify").then(Commands.argument("asset",StringArgumentType.word()).then(Commands.argument("fingerprint",StringArgumentType.word()).then(Commands.argument("evidence",StringArgumentType.greedyString()).executes(c->message(c.getSource(),OceanCanvasDeploymentService.verify(c.getSource().getLevel(),StringArgumentType.getString(c,"asset"),StringArgumentType.getString(c,"fingerprint"),actor(c.getSource()),StringArgumentType.getString(c,"evidence"))))))));
        dispatcher.register(Commands.literal("oceancanvas").then(root));
    }
    private static int preflight(CommandSourceStack s,String id){var p=OceanCanvasDeploymentService.preflight(s.getLevel(),id);s.sendSuccess(()->Component.literal("Deployment preflight ["+p.assetName()+"]: "+p.summary()+"; fingerprint="+p.fingerprint()),false);for(var g:p.gates())s.sendSuccess(()->Component.literal(" - "+(g.pass()?"PASS ":"BLOCK ")+g.code()+": "+g.detail()),false);return p.ready()?1:0;}
    private static int message(CommandSourceStack s,String m){s.sendSuccess(()->Component.literal(m),false);return m.contains("blocked")||m.contains("rejected")?0:1;}
    private static String actor(CommandSourceStack s){return s.getEntity()==null?"server":s.getEntity().getName().getString();}
}
