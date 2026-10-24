package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.oceancanvas.mod.project.OceanCanvasP1W4Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Headless/read-only validation and stewardship report surface (OC-F065). */
public final class ReportCommand {
    private ReportCommand(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("report").requires(Permissions.require("oceancanvas.diagnostics.view",1))
                .executes(c->show(c.getSource()))
                .then(Commands.literal("write").executes(c->write(c.getSource())))
                .then(Commands.literal("upgrade-baseline").requires(Permissions.require("oceancanvas.admin",2)).executes(c->baseline(c.getSource())));
        dispatcher.register(Commands.literal("oceancanvas").then(root));
    }
    private static int show(CommandSourceStack source){String report=OceanCanvasP1W4Service.headlessReport(source.getLevel());for(String line:report.split("\\n"))source.sendSuccess(()->Component.literal(line),false);return report.contains("ATTENTION")||report.contains("FAIL")?0:1;}
    private static int write(CommandSourceStack source){try{String report=OceanCanvasP1W4Service.headlessReport(source.getLevel());var dir=source.getServer().getWorldPath(LevelResource.ROOT).resolve("oceancanvas").resolve("reports");Files.createDirectories(dir);var file=dir.resolve("headless-report-"+System.currentTimeMillis()+".txt");Files.writeString(file,report,StandardCharsets.UTF_8);source.sendSuccess(()->Component.literal("Ocean Canvas report written: "+file.getFileName()+" at "+Instant.now()),false);return 1;}catch(Exception e){source.sendFailure(Component.literal("Ocean Canvas report failed: "+e.getMessage()));return 0;}}
    private static int baseline(CommandSourceStack source){var d=OceanCanvasP1W4Service.acceptUpgradeBaseline(source.getLevel());source.sendSuccess(()->Component.literal("Accepted upgrade baseline for "+d.current().minecraftVersion()+"; current diff state "+d.state()+"."),false);return 1;}
}
