package net.oceancanvas.test.mixin;
import net.oceancanvas.test.LayerLookupProbe;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
/** Observes enabled mutable-map lookups; native getLayer result always passes through. */
@Mixin(value=DataLayerStorageMap.class,remap=false)
public abstract class LayerLookupMixin {
 @Shadow private boolean cacheEnabled;
 @Shadow @Final private long[] lastSectionKeys;
 @Unique private LayerLookupProbe.Sample layerLookup$sample;
 @Inject(method="getLayer",at=@At("HEAD"),remap=false)
 private void layerLookup$head(long key,CallbackInfoReturnable<DataLayer> ci){
  if(!cacheEnabled||!LayerLookupProbe.recording)return;
  if(layerLookup$sample==null)layerLookup$sample=new LayerLookupProbe.Sample();
  if(layerLookup$sample.done)return;
  layerLookup$sample.headHit=lastSectionKeys[0]==key||lastSectionKeys[1]==key;
 }
 @Inject(method="getLayer",at=@At("RETURN"),remap=false)
 private void layerLookup$return(long key,CallbackInfoReturnable<DataLayer> ci){if(cacheEnabled&&LayerLookupProbe.recording&&layerLookup$sample!=null)layerLookup$sample.record(key,ci.getReturnValue()!=null);}
 @Inject(method={"clearCache","disableCache"},at=@At("RETURN"),remap=false)
 private void layerLookup$clear(CallbackInfo ci){if(layerLookup$sample!=null)layerLookup$sample.invalidate();}
 @Inject(method="setLayer",at=@At("RETURN"),remap=false)
 private void layerLookup$set(long key,DataLayer value,CallbackInfo ci){if(layerLookup$sample!=null)layerLookup$sample.invalidate();}
 @Inject(method={"removeLayer","copyDataLayer"},at=@At("RETURN"),remap=false)
 private void layerLookup$replace(long key,CallbackInfoReturnable<DataLayer> ci){if(layerLookup$sample!=null)layerLookup$sample.invalidate();}
}
