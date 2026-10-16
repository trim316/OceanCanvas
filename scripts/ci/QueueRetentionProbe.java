import java.util.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import it.unimi.dsi.fastutil.longs.*;
public class QueueRetentionProbe {
 static Class<?> retained;
 static Object create()throws Exception{return retained.getConstructor().newInstance();}
 static Object invoke(Object q,String method,Class<?>[] types,Object... args)throws Exception{
  try{return q.getClass().getMethod(method,types).invoke(q,args);}catch(java.lang.reflect.InvocationTargetException e){throw (Exception)e.getCause();}
 }
 static Object op(Object q,String method)throws Exception{return invoke(q,method,new Class<?>[0]);}
 static void push(Object q,String method,long v)throws Exception{invoke(q,method,new Class<?>[]{long.class},v);}
 static void verify()throws Exception{
  var a=new LongArrayFIFOQueue();Object b=create();var random=new Random(4182026);
  for(int i=0;i<100000;i++){
   int action=random.nextInt(9);long value=random.nextLong();
   if(action<2){String m=action==0?"enqueue":"enqueueFirst";push(a,m,value);push(b,m,value);}
   else if(action<6){String m=new String[]{"dequeueLong","dequeueLastLong","firstLong","lastLong"}[action-2];
    if(a.size()==0){for(Object q:new Object[]{a,b})try{op(q,m);throw new AssertionError("empty");}catch(NoSuchElementException expected){}}
    else if(!op(a,m).equals(op(b,m)))throw new AssertionError(m);
   }else if(action==6){a.trim();op(b,"trim");}
   else if(action==7&&i%31==0){a.clear();op(b,"clear");}
   if(a.size()!=(Integer)op(b,"size"))throw new AssertionError("size");
   if(i%1000==0&&!Arrays.equals(a.toArray(),(long[])op(b,"toArray")))throw new AssertionError("contents");
  }
  // Grow well above the floor, then prove shrinking above it and exact full drain.
  a.clear();op(b,"clear");for(int i=0;i<200000;i++){a.enqueue(i);push(b,"enqueue",i);}
  int peak=(Integer)op(b,"capacity");
  for(int i=0;i<200000;i++)if(a.dequeueLong()!=(Long)op(b,"dequeueLong"))throw new AssertionError("burst order");
  int end=(Integer)op(b,"capacity");if(end>65536||end>=peak)throw new AssertionError("retention bound");
  for(int i=0;i<100;i++)push(b,"enqueue",(long)i);
  var bytes=new ByteArrayOutputStream();try(var o=new ObjectOutputStream(bytes)){o.writeObject(b);}
  Object roundtrip;try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())){
   protected Class<?> resolveClass(ObjectStreamClass d)throws IOException,ClassNotFoundException{return d.getName().equals(retained.getName())?retained:super.resolveClass(d);}
  }){roundtrip=in.readObject();}
  if(!Arrays.equals((long[])op(b,"toArray"),(long[])op(roundtrip,"toArray")))throw new AssertionError("serialization");
  System.out.println("PASS actual queue transformed capacity threshold only; 100k mixed deque/wrap/empty/trim/clear, 200k burst/order/shrink, serialization; peak="+peak+" retained="+end);
 }
 static volatile long sink;
 static void bench(boolean retain)throws Exception{
  LongPriorityQueue q=retain?(LongPriorityQueue)create():new LongArrayFIFOQueue();
  var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long tid=Thread.currentThread().threadId();
  long before=bean.getThreadAllocatedBytes(tid),start=System.nanoTime(),sum=0;
  for(int wave=0;wave<500;wave++){for(int i=0;i<32768;i++)q.enqueue(i);while(!q.isEmpty())sum+=q.dequeueLong();}
  long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(tid)-before;sink=sum;
  if(sum!=500L*32768*32767/2)throw new AssertionError("checksum");
  System.out.printf("retain=%s waves=500 burst=32768 ms=%.3f allocatedBytes=%d finalCapacity=%s%n",retain,elapsed/1e6,allocated,op(q,"capacity"));
 }
 public static void main(String[] args)throws Exception{
  String name="it/unimi/dsi/fastutil/longs/LongArrayFIFOQueue";byte[] original;
  try(var in=LongArrayFIFOQueue.class.getResourceAsStream("LongArrayFIFOQueue.class")){original=in.readAllBytes();}
  var writer=new ClassWriter(0);int[] changes={0};
  var remapper=new ClassRemapper(writer,new SimpleRemapper(name,"RetainedLongQueue"));
  new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9,remapper){
   public MethodVisitor visitMethod(int access,String n,String d,String s,String[] ex){
    var mv=super.visitMethod(access,n,d,s,ex);if(!n.equals("reduce"))return mv;
    return new MethodVisitor(Opcodes.ASM9,mv){public void visitInsn(int opcode){
     if(opcode==Opcodes.ICONST_4&&changes[0]==0){super.visitLdcInsn(65536);changes[0]++;}else super.visitInsn(opcode);
    }};
   }
  },0);
  if(changes[0]!=1)throw new AssertionError("transform count");
  retained=new ClassLoader(QueueRetentionProbe.class.getClassLoader()){Class<?> build(){byte[] b=writer.toByteArray();return defineClass("RetainedLongQueue",b,0,b.length);}}.build();
  verify();for(int t=0;t<4;t++){bench(false);bench(true);}
 }
}
