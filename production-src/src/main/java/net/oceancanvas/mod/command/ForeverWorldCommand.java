package net.oceancanvas.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardship;
import net.oceancanvas.mod.project.OceanCanvasForeverWorldStewardshipData;

/** Administrative surface for v235 Forever World stewardship. Coordinates are chunk coordinates. */
public final class ForeverWorldCommand {
    private ForeverWorldCommand(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("forever")
                .requires(me.lucko.fabric.api.permissions.v0.Permissions.require("oceancanvas.protect",2))
                .executes(c->status(c.getSource()));
        root.then(Commands.literal("status").executes(c->status(c.getSource())));
        root.then(Commands.literal("role")
                .then(Commands.argument("role",StringArgumentType.word())
                        .executes(c->role(c.getSource(),StringArgumentType.getString(c,"role"),""))
                        .then(Commands.argument("parentWorldUuid",StringArgumentType.word())
                                .executes(c->role(c.getSource(),StringArgumentType.getString(c,"role"),StringArgumentType.getString(c,"parentWorldUuid"))))));

        var reserveAdd=Commands.literal("add");
        reserveAdd.then(Commands.argument("minChunkX",IntegerArgumentType.integer())
                .then(Commands.argument("minChunkZ",IntegerArgumentType.integer())
                .then(Commands.argument("maxChunkX",IntegerArgumentType.integer())
                .then(Commands.argument("maxChunkZ",IntegerArgumentType.integer())
                .then(Commands.argument("expectedVersion",StringArgumentType.word())
                .then(Commands.argument("name",StringArgumentType.greedyString())
                        .executes(c->addReserve(c.getSource(),IntegerArgumentType.getInteger(c,"minChunkX"),IntegerArgumentType.getInteger(c,"minChunkZ"),IntegerArgumentType.getInteger(c,"maxChunkX"),IntegerArgumentType.getInteger(c,"maxChunkZ"),StringArgumentType.getString(c,"expectedVersion"),StringArgumentType.getString(c,"name")))))))));
        var reserve=Commands.literal("reserve");
        reserve.then(Commands.literal("list").executes(c->reserves(c.getSource())));
        reserve.then(Commands.literal("remove").then(Commands.argument("id",StringArgumentType.word()).executes(c->removeReserve(c.getSource(),StringArgumentType.getString(c,"id")))));
        reserve.then(reserveAdd);root.then(reserve);

        var frontierAdd=Commands.literal("add");
        frontierAdd.then(Commands.argument("minChunkX",IntegerArgumentType.integer())
                .then(Commands.argument("minChunkZ",IntegerArgumentType.integer())
                .then(Commands.argument("maxChunkX",IntegerArgumentType.integer())
                .then(Commands.argument("maxChunkZ",IntegerArgumentType.integer())
                .then(Commands.argument("targetVersion",StringArgumentType.word())
                .then(Commands.argument("name",StringArgumentType.greedyString())
                        .executes(c->addFrontier(c.getSource(),IntegerArgumentType.getInteger(c,"minChunkX"),IntegerArgumentType.getInteger(c,"minChunkZ"),IntegerArgumentType.getInteger(c,"maxChunkX"),IntegerArgumentType.getInteger(c,"maxChunkZ"),StringArgumentType.getString(c,"targetVersion"),StringArgumentType.getString(c,"name")))))))));
        var frontier=Commands.literal("frontier");
        frontier.then(Commands.literal("list").executes(c->frontiers(c.getSource())));
        frontier.then(Commands.literal("status").then(Commands.argument("id",StringArgumentType.word()).then(Commands.argument("status",StringArgumentType.word()).executes(c->frontierStatus(c.getSource(),StringArgumentType.getString(c,"id"),StringArgumentType.getString(c,"status"))))));
        frontier.then(frontierAdd);root.then(frontier);

        root.then(Commands.literal("heritage").then(Commands.literal("list").executes(c->heritage(c.getSource()))));
        var rehearsal=Commands.literal("rehearsal");
        rehearsal.then(Commands.literal("list").executes(c->rehearsals(c.getSource())));
        rehearsal.then(Commands.literal("start").then(Commands.argument("fromVersion",StringArgumentType.word()).then(Commands.argument("toVersion",StringArgumentType.word()).then(Commands.argument("checkpoint",StringArgumentType.greedyString()).executes(c->rehearsalStart(c.getSource(),StringArgumentType.getString(c,"fromVersion"),StringArgumentType.getString(c,"toVersion"),StringArgumentType.getString(c,"checkpoint")))))));
        rehearsal.then(Commands.literal("migrate").then(Commands.argument("id",StringArgumentType.word()).then(Commands.argument("notes",StringArgumentType.greedyString()).executes(c->rehearsalMigrate(c.getSource(),StringArgumentType.getString(c,"id"),StringArgumentType.getString(c,"notes"))))));
        rehearsal.then(Commands.literal("validate").then(Commands.argument("id",StringArgumentType.word()).then(Commands.argument("result",StringArgumentType.word()).then(Commands.argument("notes",StringArgumentType.greedyString()).executes(c->rehearsalValidate(c.getSource(),StringArgumentType.getString(c,"id"),StringArgumentType.getString(c,"result"),StringArgumentType.getString(c,"notes")))))));
        rehearsal.then(Commands.literal("commit").then(Commands.argument("id",StringArgumentType.word()).executes(c->rehearsalCommit(c.getSource(),StringArgumentType.getString(c,"id")))));
        root.then(rehearsal);
        root.then(Commands.literal("asset").then(Commands.literal("register").then(Commands.argument("required",StringArgumentType.word()).then(Commands.argument("portable",StringArgumentType.word()).then(Commands.argument("path",StringArgumentType.greedyString()).executes(c->asset(c.getSource(),StringArgumentType.getString(c,"required"),StringArgumentType.getString(c,"portable"),StringArgumentType.getString(c,"path"))))))));
        var recovery=Commands.literal("recovery");recovery.then(Commands.literal("scan").executes(c->status(c.getSource())));recovery.then(Commands.literal("rebuild").executes(c->rebuild(c.getSource())));recovery.then(Commands.literal("drill").executes(c->drill(c.getSource())));root.then(recovery);
        dispatcher.register(Commands.literal("oceancanvas").then(root));
    }
    private static OceanCanvasForeverWorldStewardshipData d(CommandSourceStack s){return OceanCanvasForeverWorldStewardshipData.get(s.getLevel());}
    private static int status(CommandSourceStack s){var h=OceanCanvasForeverWorldStewardship.health(s.getLevel());ok(s,"Forever World: "+h.state()+" · role="+h.role()+" · UUID="+h.worldUuid()+" · heritage="+h.heritageEntries()+" · reserves="+h.reserves()+" · frontiers="+h.frontiers()+" · rehearsals="+h.rehearsals()+" · assets="+h.assets());for(var f:h.findings())ok(s," - "+f.severity()+" "+f.code()+": "+f.detail());return h.ready()?1:0;}
    private static int role(CommandSourceStack s,String role,String parent){try{var i=d(s).setRole(role,parent);ok(s,"Forever World role set to "+i.role()+"; UUID="+i.worldUuid()+(i.parentWorldUuid().isBlank()?"":"; parent="+i.parentWorldUuid()));return 1;}catch(Exception e){fail(s,e.getMessage());return 0;}}
    private static int addReserve(CommandSourceStack s,int minX,int minZ,int maxX,int maxZ,String version,String name){var r=d(s).addReserve(name,minX,maxX,minZ,maxZ,version,"Reserved for future Minecraft generation");ok(s,"Created reserve "+r.id()+" '"+r.name()+"'. Pregen/Rewipe/Restore/Expand touching it are now blocked unless RESERVE_MUTATION=ALLOW explicitly overrides it.");return 1;}
    private static int reserves(CommandSourceStack s){var a=d(s).reserves();ok(s,"Future Generation Reserves: "+a.size());for(var r:a)ok(s," - "+r.id()+" '"+r.name()+"' chunks ["+r.minChunkX()+","+r.minChunkZ()+"]..["+r.maxChunkX()+","+r.maxChunkZ()+"] expected="+r.expectedVersion()+" enabled="+r.enabled());return 1;}
    private static int removeReserve(CommandSourceStack s,String id){if(d(s).removeReserve(id)){ok(s,"Removed reserve "+id+".");return 1;}fail(s,"Unknown reserve "+id);return 0;}
    private static int addFrontier(CommandSourceStack s,int minX,int minZ,int maxX,int maxZ,String version,String name){var f=d(s).addFrontier(name,minX,maxX,minZ,maxZ,version,0,0,"Expansion Campaign frontier");ok(s,"Created frontier "+f.id()+" '"+f.name()+"' targeting "+f.targetVersion()+".");return 1;}
    private static int frontiers(CommandSourceStack s){var a=d(s).frontiers();ok(s,"Version Frontiers: "+a.size());for(var f:a)ok(s," - "+f.id()+" '"+f.name()+"' "+f.status()+" target="+f.targetVersion()+" chunks ["+f.minChunkX()+","+f.minChunkZ()+"]..["+f.maxChunkX()+","+f.maxChunkZ()+"]");return 1;}
    private static int frontierStatus(CommandSourceStack s,String id,String status){try{if(status.equalsIgnoreCase("OPEN")){var target=d(s).frontiers().stream().filter(v->v.id().equalsIgnoreCase(id)).findFirst().orElseThrow(()->new IllegalArgumentException("unknown frontier"));String gate=OceanCanvasForeverWorldStewardship.upgradeGate(s.getLevel(),target.targetVersion());if(!gate.isEmpty()){fail(s,gate);return 0;}}var f=d(s).setFrontierStatus(id,status);ok(s,"Frontier "+f.id()+" -> "+f.status());return 1;}catch(Exception e){fail(s,e.getMessage());return 0;}}
    private static int heritage(CommandSourceStack s){var a=d(s).heritage();ok(s,"Generation Heritage entries: "+a.size());int from=Math.max(0,a.size()-20);for(int i=from;i<a.size();i++){var h=a.get(i);ok(s," - "+h.heritageType()+" chunks ["+h.minChunkX()+","+h.minChunkZ()+"]..["+h.maxChunkX()+","+h.maxChunkZ()+"] version="+h.generationVersion()+" source="+h.source());}return 1;}
    private static int rehearsalStart(CommandSourceStack s,String from,String to,String checkpoint){String report=OceanCanvasForeverWorldStewardship.compatibilityReport(s.getLevel(),to);var r=d(s).startRehearsal(from,to,checkpoint,report);ok(s,"Started Upgrade Rehearsal "+r.id()+" "+from+" -> "+to+" status="+r.status()+". Validate it only after testing the staging copy and compatibility report.");return 1;}
    private static int rehearsals(CommandSourceStack s){var a=d(s).rehearsals();ok(s,"Upgrade Rehearsals: "+a.size());for(var r:a)ok(s," - "+r.id()+" "+r.fromVersion()+" -> "+r.toVersion()+" "+r.status()+" checkpoint="+r.checkpointRef());return 1;}
    private static int rehearsalMigrate(CommandSourceStack s,String id,String notes){try{var r=d(s).markMigrated(id,notes);d(s).addLineage("UPGRADE_MIGRATED","","Rehearsal "+id+" recorded migration to "+r.toVersion());ok(s,"Rehearsal "+r.id()+" -> MIGRATED. Run validation on the migrated staging world before commit.");return 1;}catch(Exception e){fail(s,e.getMessage());return 0;}}
    private static int rehearsalValidate(CommandSourceStack s,String id,String result,String notes){try{boolean pass=result.equalsIgnoreCase("pass")||result.equalsIgnoreCase("validated")||result.equalsIgnoreCase("true");var r=d(s).validateRehearsal(id,pass,notes);ok(s,"Rehearsal "+r.id()+" -> "+r.status());return pass?1:0;}catch(Exception e){fail(s,e.getMessage());return 0;}}
    private static int rehearsalCommit(CommandSourceStack s,String id){try{var r=d(s).commitRehearsal(id);d(s).addLineage("UPGRADE_COMMITTED","","Upgrade rehearsal "+id+" committed for "+r.toVersion());ok(s,"Committed rehearsal "+id+" for "+r.toVersion()+" to Forever World history.");return 1;}catch(Exception e){fail(s,e.getMessage());return 0;}}
    private static int asset(CommandSourceStack s,String required,String portable,String path){boolean req=Boolean.parseBoolean(required),port=Boolean.parseBoolean(portable);var a=d(s).putAsset("","External dependency",path,"",req,port,"Registered for recovery/portability checks");ok(s,"Registered external asset "+a.id()+"; required="+req+" portable="+port+" path="+a.path());return 1;}
    private static int rebuild(CommandSourceStack s){ok(s,OceanCanvasForeverWorldStewardship.rebuildRecoveryMetadata(s.getLevel()));return 1;}
    private static int drill(CommandSourceStack s){ok(s,OceanCanvasForeverWorldStewardship.rebuildRecoveryMetadata(s.getLevel()));return status(s);}
    private static void ok(CommandSourceStack s,String m){s.sendSuccess(()->Component.literal(m),false);}private static void fail(CommandSourceStack s,String m){s.sendFailure(Component.literal(m==null?"Forever World operation failed.":m));}
}
