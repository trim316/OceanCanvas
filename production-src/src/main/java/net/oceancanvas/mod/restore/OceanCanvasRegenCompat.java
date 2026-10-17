package net.oceancanvas.mod.restore;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Compatibility bridge for Restore-to-Vanilla regeneration. Adapted from the
 * MIT-licensed Orbital/ReGenerate-Chunks project (2026) and kept isolated so
 * Mojang worldgen API drift is contained to one file.
 */
public final class OceanCanvasRegenCompat {
    private enum Strategy { MODERN_6, MODERN_7, LEGACY_7 }
    private static final Method CARVERS_METHOD;
    private static final Strategy STRATEGY;
    private static final Class<?> ENUM_CLASS;

    static {
        Method picked = null; Strategy strategy = null; Class<?> enumClass = null;
        for (Method m : candidates()) if (modern6(m)) { picked=m; strategy=Strategy.MODERN_6; break; }
        if (picked == null) for (Method m : candidates()) if (modern7(m)) { picked=m; strategy=Strategy.MODERN_7; enumClass=m.getParameterTypes()[6]; break; }
        if (picked == null) for (Method m : candidates()) if (legacy7(m)) { picked=m; strategy=Strategy.LEGACY_7; enumClass=m.getParameterTypes()[6]; break; }
        if (picked == null) throw new ExceptionInInitializerError("Could not resolve ChunkGenerator carvers step");
        CARVERS_METHOD=picked; STRATEGY=strategy; ENUM_CLASS=enumClass;
    }
    private OceanCanvasRegenCompat() {}

    private static List<Method> candidates() {
        Set<String> seen = new HashSet<>(); List<Method> out = new ArrayList<>();
        for (Class<?> start : new Class<?>[]{NoiseBasedChunkGenerator.class, ChunkGenerator.class}) {
            for (Class<?> c=start; c!=null && ChunkGenerator.class.isAssignableFrom(c); c=c.getSuperclass()) {
                for (Method m:c.getDeclaredMethods()) add(seen,out,m);
            }
        }
        for (Method m:ChunkGenerator.class.getMethods()) add(seen,out,m);
        return out;
    }
    private static void add(Set<String> seen,List<Method> out,Method m) {
        if (Modifier.isStatic(m.getModifiers()) || m.getReturnType()!=void.class) return;
        int mod=m.getModifiers(); if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) return;
        String key=m.getDeclaringClass().getName()+"#"+m.getName()+Arrays.toString(m.getParameterTypes());
        if (!seen.add(key)) return; try {m.setAccessible(true);} catch(RuntimeException ignored){} out.add(m);
    }
    private static boolean modern6(Method m){Class<?>[]p=m.getParameterTypes();return p.length==6&&p[0].isAssignableFrom(WorldGenRegion.class)&&p[1]==long.class&&p[2]==RandomState.class&&p[3].isAssignableFrom(BiomeManager.class)&&p[4].isAssignableFrom(StructureManager.class)&&p[5].isAssignableFrom(ProtoChunk.class);}
    private static boolean modern7(Method m){Class<?>[]p=m.getParameterTypes();return p.length==7&&p[0].isAssignableFrom(WorldGenRegion.class)&&p[1]==long.class&&p[2]==RandomState.class&&p[3].isAssignableFrom(BiomeManager.class)&&p[4].isAssignableFrom(StructureManager.class)&&p[5].isAssignableFrom(ProtoChunk.class)&&p[6].isEnum();}
    private static boolean legacy7(Method m){Class<?>[]p=m.getParameterTypes();return p.length==7&&p[0].isAssignableFrom(WorldGenRegion.class)&&p[1]==long.class&&"NoiseConfig".equals(p[2].getSimpleName())&&p[3].isAssignableFrom(BiomeManager.class)&&p[4].isAssignableFrom(StructureManager.class)&&p[5].isAssignableFrom(ProtoChunk.class)&&p[6].isEnum();}

    public static void applyCarvers(ChunkGenerator generator, WorldGenRegion region, ServerLevel level,
                                    RandomState randomState, StructureManager structures, ProtoChunk chunk) {
        try {
            long seed=level.getSeed(); BiomeManager biomes=region.getBiomeManager();
            switch(STRATEGY){
                case MODERN_6 -> CARVERS_METHOD.invoke(generator,region,seed,randomState,biomes,structures,chunk);
                case MODERN_7 -> { for(Object e:ENUM_CLASS.getEnumConstants()) CARVERS_METHOD.invoke(generator,region,seed,randomState,biomes,structures,chunk,e); }
                case LEGACY_7 -> { Object noise=resolveNoiseConfig(level); for(Object e:ENUM_CLASS.getEnumConstants()) CARVERS_METHOD.invoke(generator,region,seed,noise,biomes,structures,chunk,e); }
            }
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    private static Object resolveNoiseConfig(ServerLevel level) throws ReflectiveOperationException {
        Object cache=level.getChunkSource();
        for(Method m:cache.getClass().getMethods()) if(m.getParameterCount()==0&&"NoiseConfig".equals(m.getReturnType().getSimpleName())) return m.invoke(cache);
        for(Method m:cache.getClass().getDeclaredMethods()) if(m.getParameterCount()==0&&"NoiseConfig".equals(m.getReturnType().getSimpleName())) {m.setAccessible(true); return m.invoke(cache);} 
        throw new NoSuchMethodException("No NoiseConfig getter on "+cache.getClass().getName());
    }
    public static void markChunkNeedsSave(LevelChunk chunk) {
        try { if (invokeBooleanSetter(chunk,chunk.getClass())||invokeBooleanSetter(chunk,ChunkAccess.class)) return; }
        catch(ReflectiveOperationException e){throw new RuntimeException(e);} throw new IllegalStateException("Cannot mark regenerated chunk dirty");
    }
    private static boolean invokeBooleanSetter(Object receiver,Class<?> type)throws ReflectiveOperationException{
        for(Method m:type.getMethods()) if(m.getReturnType()==void.class&&m.getParameterCount()==1&&m.getParameterTypes()[0]==boolean.class){m.invoke(receiver,true);return true;}
        for(Method m:type.getDeclaredMethods()) if(m.getReturnType()==void.class&&m.getParameterCount()==1&&m.getParameterTypes()[0]==boolean.class){m.setAccessible(true);m.invoke(receiver,true);return true;}
        return false;
    }
}
