package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.oceancanvas.mod.restore.OceanCanvasRestoreManager;
import net.oceancanvas.mod.worldgen.OceanCanvasPlayerZones;

/** Power-user equivalent of the map's two-click Restore to Vanilla action. */
public final class RestoreCommand {
    private RestoreCommand(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("oceancanvas").then(Commands.literal("restore")
                .requires(Permissions.require("oceancanvas.restore",2))
                .then(Commands.literal("cancel").executes(ctx->{String ownership=net.oceancanvas.mod.project.OceanCanvasOperationOwnership.controlBlockReason(ctx.getSource());if(!ownership.isBlank()){ctx.getSource().sendFailure(Component.literal("[Ocean Canvas] "+ownership));return 0;}String message=OceanCanvasRestoreManager.cancel(ctx.getSource().getLevel());ctx.getSource().sendSuccess(()->Component.literal("[Ocean Canvas] "+message),true);return 1;}))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("region",StringArgumentType.greedyString()).executes(ctx->{
                            CommandSourceStack source=ctx.getSource(); String name=StringArgumentType.getString(ctx,"region");
                            OceanCanvasPlayerZones.Zone zone=OceanCanvasPlayerZones.get(source.getLevel()).zoneByName(name);
                            if(zone==null){source.sendFailure(Component.literal("No Ocean Canvas region named '"+name+"'."));return 0;}
                            var meta=net.oceancanvas.mod.project.OceanCanvasProjectData.get(source.getLevel()).regionMeta(zone.name());
                            if(meta!=null && meta.parsedStage()==net.oceancanvas.mod.project.OceanCanvasProjectData.RegionStage.ARCHIVED){source.sendFailure(Component.literal("Region '"+zone.name()+"' is Archived. Move it out of Archived before Restore to Vanilla."));return 0;}
                            ServerPlayer player=source.getPlayer(); String message=OceanCanvasRestoreManager.restoreRegion(source.getLevel(),zone,player);
                            source.sendSuccess(()->Component.literal("[Ocean Canvas] "+message),true);return 1;
                        })))));
    }
}
