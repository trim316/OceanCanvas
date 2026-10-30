package net.oceancanvas.mod.worldgen;
import java.util.Objects;
import java.util.function.Function;
/** Identity-only lifetime for discardable scans; never a completion certificate. */
final class OceanCanvasTransientChunkOwner<T> {
 private T chunk;
 OceanCanvasTransientChunkOwner(T chunk) { this.chunk=Objects.requireNonNull(chunk); }
 boolean matches(T expected) { return chunk!=null && chunk==expected; }
 boolean detach(T expected) { if(!matches(expected))return false;chunk=null;return true; }
 static <T,S> boolean discardMatching(OceanCanvasPrimitiveLongObjectMap<S> states,long key,T expected,Function<S,T> owner) {
  S state=states.get(key);
  return state!=null && owner.apply(state)==expected && states.remove(key,state);
 }
}
