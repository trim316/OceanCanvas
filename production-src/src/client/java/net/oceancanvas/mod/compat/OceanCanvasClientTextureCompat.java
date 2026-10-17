package net.oceancanvas.mod.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.oceancanvas.mod.diagnostic.OceanCanvasIncidentRecorder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/**
 * Single client-side compatibility boundary for Minecraft's dynamic texture internals.
 *
 * <p>Minecraft 26.x has changed NativeImage/DynamicTexture constructor and texture-manager
 * signatures more than once. Map rendering and planning references only need a registered
 * {@link Identifier}; they must not each rediscover those internals independently.</p>
 *
 * <p>Failures are deliberately fail-soft. A renderer-internal compatibility failure disables
 * future uploads for the session, records one bounded incident, and leaves vector/map UI alive.
 * Callers can query {@link #available()} / {@link #status()} for diagnostics.</p>
 */
public final class OceanCanvasClientTextureCompat {
    public record RegisteredTexture(Identifier id, Object texture) { }

    private static volatile boolean disabled;
    private static volatile String disabledReason = "";

    private OceanCanvasClientTextureCompat() { }

    public static boolean available() { return !disabled; }
    public static String status() { return disabled ? "disabled: " + disabledReason : "available"; }

    /** Decode PNG bytes, create a dynamic texture and register it under the requested id. */
    public static RegisteredTexture uploadPng(byte[] png, Identifier id, String label) {
        if (disabled || png == null || png.length == 0 || id == null) return null;
        Object nativeImage = null;
        Object dynamic = null;
        try {
            nativeImage = readNativeImage(new ByteArrayInputStream(png));
            if (nativeImage == null) return disable("NativeImage InputStream decoder was not found", null);
            dynamic = newDynamicTexture(nativeImage, label == null ? "Ocean Canvas texture" : label);
            if (dynamic == null) {
                closeQuietly(nativeImage);
                return disable("DynamicTexture constructor compatible with NativeImage was not found", null);
            }
            nativeImage = null; // ownership transfers to DynamicTexture on the supported constructor path
            if (!registerTexture(id, dynamic)) {
                closeQuietly(dynamic);
                return disable("TextureManager register method compatible with Identifier/DynamicTexture was not found", null);
            }
            return new RegisteredTexture(id, dynamic);
        } catch (Throwable failure) {
            if (dynamic != null) closeQuietly(dynamic); else if (nativeImage != null) closeQuietly(nativeImage);
            return disable("dynamic texture compatibility failure", failure);
        }
    }

    /** Release a registered texture through the current texture manager, then close as a fallback. */
    public static void release(Identifier id, Object texture) {
        if (texture == null) return;
        try {
            Object manager = Minecraft.getInstance().getTextureManager();
            for (Method m : manager.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                if (p.length == 1 && id != null && p[0].isAssignableFrom(id.getClass())
                        && (n.contains("release") || n.contains("remove"))) {
                    m.invoke(manager, id);
                    return;
                }
            }
        } catch (Throwable failure) {
            OceanCanvasIncidentRecorder.record("client.texture-release",
                    "texture manager release failed for " + id, failure);
        }
        closeQuietly(texture);
    }

    private static Object readNativeImage(InputStream in) throws Exception {
        Class<?> clazz = Class.forName("com.mojang.blaze3d.platform.NativeImage");
        for (Method m : clazz.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !clazz.isAssignableFrom(m.getReturnType())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && InputStream.class.isAssignableFrom(p[0])) return m.invoke(null, in);
        }
        return null;
    }

    private static Object newDynamicTexture(Object nativeImage, String label) throws Exception {
        Class<?> clazz = Class.forName("net.minecraft.client.renderer.texture.DynamicTexture");
        for (Constructor<?> c : clazz.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && p[0].isInstance(nativeImage)) return c.newInstance(nativeImage);
            if (p.length == 2) {
                Object[] args = new Object[2]; boolean image = false, labelOk = false;
                for (int i = 0; i < 2; i++) {
                    if (p[i].isInstance(nativeImage)) { args[i] = nativeImage; image = true; }
                    else if (p[i] == String.class) { args[i] = label; labelOk = true; }
                    else if (Supplier.class.isAssignableFrom(p[i])) { args[i] = (Supplier<String>) () -> label; labelOk = true; }
                }
                if (image && labelOk) return c.newInstance(args);
            }
        }
        return null;
    }

    private static boolean registerTexture(Identifier id, Object texture) throws Exception {
        Object manager = Minecraft.getInstance().getTextureManager();
        for (Method m : manager.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2 || !p[0].isAssignableFrom(id.getClass()) || !p[1].isInstance(texture)) continue;
            Object result = m.invoke(manager, id, texture);
            return !(result instanceof Boolean b) || b;
        }
        return false;
    }

    private static synchronized RegisteredTexture disable(String reason, Throwable failure) {
        if (!disabled) {
            disabled = true;
            disabledReason = reason == null ? "unknown compatibility failure" : reason;
            OceanCanvasIncidentRecorder.record("client.texture-compat", disabledReason, failure);
        }
        return null;
    }

    private static void closeQuietly(Object value) {
        if (value instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
    }
}
