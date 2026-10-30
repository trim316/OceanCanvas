package net.oceancanvas.probe;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
public final class FreshCheckpointFixture implements ModInitializer {
 public static volatile MinecraftServer server;
 @Override public void onInitialize(){ServerLifecycleEvents.SERVER_STARTING.register(s->server=s);ServerLifecycleEvents.SERVER_STOPPED.register(s->server=null);}
}
