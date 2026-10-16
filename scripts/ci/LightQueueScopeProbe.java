import net.oceancanvas.test.*;
import net.oceancanvas.test.mixin.LightQueueMixin;
public class LightQueueScopeProbe {
 public static void main(String[] args)throws Exception{
  var queue=new LightQueueMixin(){};
  var floor=LightQueueMixin.class.getDeclaredMethod("lightQueue$floor",int.class);floor.setAccessible(true);
  if((Integer)floor.invoke(queue,4)!=4)throw new AssertionError("unmarked");
  queue.lightQueue$mark();queue.lightQueue$mark();
  if(LightQueueProbe.marked.get()!=1)throw new AssertionError("duplicate mark");
  if((Integer)floor.invoke(queue,4)!=(LightQueueProbe.RETAIN?65536:4))throw new AssertionError("flag");
  System.out.println("PASS actual compiled Mixin handler unmarked/control/retain selection and idempotent marking; retain="+LightQueueProbe.RETAIN+" (not runtime injection proof)");
 }
}
