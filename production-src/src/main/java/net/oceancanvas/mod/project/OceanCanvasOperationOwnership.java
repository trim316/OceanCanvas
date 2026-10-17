package net.oceancanvas.mod.project;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/** OC-F253 one authority model for the currently active terrain operation. */
public final class OceanCanvasOperationOwnership {
    private OceanCanvasOperationOwnership() {}

    public record Snapshot(boolean active,String kind,UUID requesterId,String requesterDisplay,String authority){
        public Snapshot{kind=safe(kind);requesterDisplay=safe(requesterDisplay);authority=safe(authority);}
    }

    public static Snapshot snapshot(ServerLevel world){
        var owner=net.oceancanvas.mod.operation.OceanCanvasTerrainOperationView.ownerSnapshot();
        if(owner!=null)return new Snapshot(true,owner.kind(),owner.requesterId(),display(world,owner.requesterId(),owner.requesterDisplay()),"Initiator or oceancanvas.operation.override (level 3)");
        return new Snapshot(false,"",null,"","No active operation");
    }

    public static String controlBlockReason(CommandSourceStack source){
        if(source==null)return "Operation control requires a command source.";
        Snapshot s=snapshot(source.getLevel());
        if(!s.active())return "";
        if(source.getEntity()==null)return ""; // console/command automation is authoritative
        var player=source.getPlayer();
        if(player!=null && s.requesterId()!=null && s.requesterId().equals(player.getUUID()))return "";
        if(Permissions.require("oceancanvas.operation.override",3).test(source))return "";
        return "Operation is owned by "+(s.requesterDisplay().isBlank()?"another operator":s.requesterDisplay())+". Only the initiator or an operation-override admin may pause/cancel/approve recovery.";
    }

    private static String display(ServerLevel world,UUID id,String fallback){
        if(world!=null&&id!=null){var p=world.getServer().getPlayerList().getPlayer(id);if(p!=null)return p.getGameProfile().name();}
        if(fallback!=null&&!fallback.isBlank())return fallback;
        return id==null?"console/system":id.toString();
    }
    private static String safe(String s){return s==null?"":s;}
}
