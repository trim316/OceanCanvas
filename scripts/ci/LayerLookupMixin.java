package net.oceancanvas.test.mixin;
import net.oceancanvas.test.*;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.concurrent.atomic.AtomicBoolean;
@Mixin(value=DataLayerStorageMap.class,remap=false)
public abstract class LayerLookupMixin {
 @Shadow private boolean cacheEnabled;
 @Shadow @Final private long[] lastSectionKeys;
 @Unique private volatile LayerLookupProbe.Sample layerLookup$sample;
 @Unique private final LayerLookupAccess layerLookup$access=new LayerLookupAccess();
 @Unique private final AtomicBoolean layerLookup$reported=new AtomicBoolean();
 @Unique private void layerLookup$failure(){if(layerLookup$sample!=null&&layerLookup$access.failed&&layerLookup$reported.compareAndSet(false,true)){layerLookup$sample.done=true;System.out.println("LAYER_LOOKUP_FAILED overlappingAccess id="+layerLookup$sample.id);}}
 @Unique private void layerLookup$enter(){layerLookup$access.enter();layerLookup$failure();}
 @Unique private void layerLookup$leave(){layerLookup$failure();layerLookup$access.leave();}
 @Unique private void layerLookup$invalidate(){if(!layerLookup$access.failed&&layerLookup$access.held()&&layerLookup$sample!=null)layerLookup$sample.invalidate();}
 @Inject(method="getLayer",at=@At("HEAD"),remap=false)
 private void layerLookup$head(long key,CallbackInfoReturnable<DataLayer> ci){
  layerLookup$enter();
  if(layerLookup$access.failed||!cacheEnabled||!LayerLookupProbe.recording)return;
  if(layerLookup$sample==null)layerLookup$sample=new LayerLookupProbe.Sample();
  if(!layerLookup$sample.done)layerLookup$sample.headHit=lastSectionKeys[0]==key||lastSectionKeys[1]==key;
 }
 @Inject(method="getLayer",at=@At("RETURN"),remap=false)
 private void layerLookup$return(long key,CallbackInfoReturnable<DataLayer> ci){
  if(!layerLookup$access.failed&&cacheEnabled&&LayerLookupProbe.recording&&layerLookup$sample!=null)layerLookup$sample.record(key,ci.getReturnValue()!=null);
  layerLookup$leave();
 }
 @Inject(method={"clearCache","disableCache"},at=@At("HEAD"),remap=false)
 private void layerLookup$clearHead(CallbackInfo ci){layerLookup$enter();}
 @Inject(method={"clearCache","disableCache"},at=@At("RETURN"),remap=false)
 private void layerLookup$clear(CallbackInfo ci){layerLookup$invalidate();layerLookup$leave();}
 @Inject(method="setLayer",at=@At("HEAD"),remap=false)
 private void layerLookup$setHead(long key,DataLayer value,CallbackInfo ci){layerLookup$enter();}
 @Inject(method="setLayer",at=@At("RETURN"),remap=false)
 private void layerLookup$set(long key,DataLayer value,CallbackInfo ci){layerLookup$invalidate();layerLookup$leave();}
 @Inject(method={"removeLayer","copyDataLayer"},at=@At("HEAD"),remap=false)
 private void layerLookup$replaceHead(long key,CallbackInfoReturnable<DataLayer> ci){layerLookup$enter();}
 @Inject(method={"removeLayer","copyDataLayer"},at=@At("RETURN"),remap=false)
 private void layerLookup$replace(long key,CallbackInfoReturnable<DataLayer> ci){layerLookup$invalidate();layerLookup$leave();}
}
