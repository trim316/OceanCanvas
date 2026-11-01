package net.oceancanvas.mod.worldgen;
import java.util.HashSet;import java.util.Set;
/** Main-thread read-only scan slots. No terrain authority, ticket mutation or completion credit. */
final class OceanCanvasBlockProofAdmission {
 private final Set<Long> active=new HashSet<>();
 boolean acquire(long chunk,int existingResidencyCapacity){if(active.contains(chunk))return true;if(existingResidencyCapacity<=0||active.size()>=existingResidencyCapacity)return false;active.add(chunk);return true;}
 void release(long chunk){active.remove(chunk);}void clear(){active.clear();}int size(){return active.size();}
}
