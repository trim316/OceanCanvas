package net.oceancanvas.mod;

import net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel;
import net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel.Entry;
import net.oceancanvas.mod.pregen.OceanCanvasPregenQueueModel.View;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure state and wire tests; these do NOT simulate SavedData, packets or the Minecraft engine. */
public final class PregenQueueOfflineTest {
    private static int checks;
    private static Entry entry(int n){return new Entry(new UUID(0,n).toString(),"Region "+n,new UUID(1,1).toString(),"a".repeat(64),"0,0,20000",16,"WAITING","");}
    private static OceanCanvasPregenQueueModel empty(){return new OceanCanvasPregenQueueModel(1,0,List.of());}
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();}catch(IllegalArgumentException|IllegalStateException e){checks++;return;}throw new AssertionError(message);}
    public static void main(String[] args){runAll();System.out.println("Pregen queue: "+checks+" assertions passed.");}
    public static void runAll(){
        checks=0;var q=empty();check(!q.readOnly()&&q.head()==null,"empty writable queue");
        q.add(entry(1));q.add(entry(2));q.add(entry(3));check(q.revision()==3,"add revisions");
        q.move(entry(3).id(),-1);check(q.entries().get(1).id().equals(entry(3).id()),"move up");
        q.move(entry(3).id(),1);check(q.entries().get(2).id().equals(entry(3).id()),"move down");
        long rev=q.revision();q.move(entry(1).id(),-1);check(q.revision()==rev,"edge move no-op");
        rejects(()->q.add(entry(1)),"duplicate region");
        var duplicate=new Entry(entry(1).id(),"Different",entry(1).owner(),entry(1).shape(),entry(1).canvas(),16,"WAITING","");
        rejects(()->q.add(duplicate),"duplicate id");
        rejects(()->q.add(entry(4).withState("RUNNING","")),"add cannot start job");
        rejects(()->q.state(entry(2).id(),"RUNNING",""),"non-head cannot run");
        check(q.revision()==rev,"invalid changes atomic");
        q.state(entry(1).id(),"RUNNING","started");
        rejects(()->q.remove(entry(1).id()),"cannot remove running");
        rejects(()->q.move(entry(2).id(),-1),"cannot move across running");
        rejects(()->q.move(entry(1).id(),1),"cannot move running");
        rejects(()->q.complete(entry(2).id()),"wrong completion id");
        rejects(()->q.state(entry(2).id(),"RUNNING",""),"cannot run second");
        check(q.entries().size()==3,"failed completion retains entries");
        q.complete(entry(1).id());check(q.head().id().equals(entry(2).id()),"completion advances exactly one");
        rejects(()->q.complete(entry(1).id()),"duplicate completion denied");
        rejects(()->q.complete(entry(2).id()),"waiting head cannot complete");
        q.state(entry(2).id(),"RUNNING","");q.state(entry(2).id(),"INTERRUPTED","stop");
        check(q.entries().size()==2&&q.head().state().equals("INTERRUPTED"),"interrupted retained");
        q.state(entry(2).id(),"WAITING","retry");q.state(entry(2).id(),"RUNNING","");q.complete(entry(2).id());
        check(q.head().id().equals(entry(3).id()),"retry completes");
        q.state(entry(3).id(),"BLOCKED","changed bounds");q.state(entry(3).id(),"WAITING","approved");
        rev=q.revision();q.touch();check(q.revision()==rev+1,"authorization revision changes");
        q.remove(entry(3).id());check(q.head()==null,"remove waiting");
        rejects(()->q.remove("missing"),"stale remove");
        rejects(()->q.state("missing","WAITING",""),"stale state");
        var cap=empty();for(int i=1;i<=16;i++){cap.add(entry(i));check(cap.entries().size()==i,"capacity "+i);}
        rejects(()->cap.add(entry(17)),"capacity enforced");
        var snapshot=new OceanCanvasPregenQueueModel(cap.schema(),cap.revision(),cap.entries());
        check(snapshot.entries().equals(cap.entries())&&snapshot.revision()==cap.revision(),"pure reconstruction retains order/state/revision");
        var mutable=new ArrayList<>(cap.entries());var copied=new OceanCanvasPregenQueueModel(1,0,mutable);mutable.clear();
        check(copied.entries().size()==16,"defensive input copy");
        for(int schema:new int[]{0,2,Integer.MAX_VALUE}){
            var future=new OceanCanvasPregenQueueModel(schema,0,List.of(entry(1)));check(future.readOnly(),"unknown schema read-only");
            rejects(()->future.remove(entry(1).id()),"read-only blocks mutation");
            check(future.entries().size()==1,"unknown data preserved");
        }
        for(long r:new long[]{-1,Long.MAX_VALUE})check(new OceanCanvasPregenQueueModel(1,r,List.of()).readOnly(),"invalid revision");
        var tooMany=new ArrayList<>(cap.entries());tooMany.add(entry(17));
        check(new OceanCanvasPregenQueueModel(1,0,tooMany).readOnly(),"oversize saved queue read-only");
        check(new OceanCanvasPregenQueueModel(1,0,List.of(entry(1),entry(2).withState("RUNNING",""))).readOnly(),"invalid saved running position");
        check(new OceanCanvasPregenQueueModel(1,0,List.of(entry(1),entry(1))).readOnly(),"invalid saved duplicate");
        check(new OceanCanvasPregenQueueModel(1,0,List.of(entry(1).withState("BOGUS",""))).readOnly(),"unknown state");
        var base=entry(1);
        for(long count:new long[]{0,-1,65_537,Long.MAX_VALUE}){
            var bad=new Entry(base.id(),base.region(),base.owner(),base.shape(),base.canvas(),count,"WAITING","");
            rejects(()->empty().add(bad),"chunk count bounded");
        }
        var wide=empty();
        for(int i=1;i<=16;i++){
            var e=entry(i);wide.add(new Entry(e.id(),"海".repeat(250)+i,e.owner(),e.shape(),e.canvas(),65536,"WAITING","界".repeat(240)));
        }
        String packed=wide.view(false,"語".repeat(200),entry(1).id());
        check(packed.length()<32767,"worst-case supported Unicode packet bounded");
        View view=View.decode(packed);check(view.items().size()==16,"wire max entries");
        check(view.items().get(0).detail().equals("界".repeat(240)),"wire retains complete detail");
        check(view.recoveryId().equals(entry(1).id())&&!view.armed(),"recovery identity and paused status");
        var text=empty();var e=entry(1);text.add(new Entry(e.id(),"A\tB\n雪",e.owner(),e.shape(),e.canvas(),16,"WAITING","Line\n2\t✓"));
        view=View.decode(text.view(true,"Ready\nNow", ""));
        check(view.items().get(0).region().equals("A\tB\n雪"),"wire escaping region");
        check(view.items().get(0).detail().equals("Line\n2\t✓"),"wire escaping detail");
        check(view.armed()&&view.message().equals("Ready\nNow"),"wire status");
        for(String invalid:new String[]{"", "Q2", "Q1\tx\tfalse\tfalse\teA\t\tend",packed+"\nbad"})
            check(View.decode(invalid).items().isEmpty()&&!View.decode(invalid).armed(),"malformed wire fails closed");
        var readOnly=new OceanCanvasPregenQueueModel(2,5,List.of(entry(1)));
        check(View.decode(readOnly.view(false,"Recovery","")).readOnly(),"read-only visible");
    }
}
