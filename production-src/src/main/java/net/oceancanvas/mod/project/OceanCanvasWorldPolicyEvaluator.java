package net.oceancanvas.mod.project;

import java.util.*;

/**
 * Pure, non-destructive World Policy inheritance/evaluation.
 * Specificity: WORLD < REGION < PROJECT < WORK_AREA. Disabled records are preserved in the trace
 * but do not override an enabled inherited value. Ties at the same scope/key use updatedAt then id.
 */
public final class OceanCanvasWorldPolicyEvaluator {
    private OceanCanvasWorldPolicyEvaluator(){}

    public interface PolicyLike {
        String id(); String scopeType(); String scopeId(); String key(); String value();
        boolean enabled(); String rationale(); long updatedAt();
    }

    public record Context(String regionId,String projectId,String workAreaId) {
        public Context {
            regionId=normId(regionId);projectId=normId(projectId);workAreaId=normId(workAreaId);
        }
        public static Context of(String regionId,String projectId,String workAreaId){return new Context(regionId,projectId,workAreaId);}
    }

    public record Step(String id,String scopeType,String scopeId,String key,String value,boolean enabled,
                       String rationale,long updatedAt,boolean applicable) { }

    public record Decision(String key,String value,String sourceId,String sourceScope,String sourceScopeId,
                           String rationale,List<Step> trace) {
        public boolean inherited(){return !"WORK_AREA".equals(sourceScope);}
        public String sourceLabel(){return sourceScope+(sourceScopeId.isBlank()?"":" · "+sourceScopeId);}
    }

    public static List<Decision> evaluate(Collection<? extends PolicyLike> policies,Context context){
        if(policies==null||policies.isEmpty())return List.of();
        Context c=context==null?Context.of("","",""):context;
        Map<String,List<Step>> byKey=new TreeMap<>();
        for(PolicyLike p:policies){
            if(p==null)continue;
            String scope=normScope(p.scopeType()),sid=normId(p.scopeId()),key=normKey(p.key());
            if(key.isBlank())continue;
            boolean applicable=switch(scope){
                case "WORLD" -> true;
                case "REGION" -> !c.regionId().isBlank()&&sid.equals(c.regionId());
                case "PROJECT" -> !c.projectId().isBlank()&&sid.equals(c.projectId());
                case "WORK_AREA" -> !c.workAreaId().isBlank()&&sid.equals(c.workAreaId());
                default -> false;
            };
            byKey.computeIfAbsent(key,k->new ArrayList<>()).add(new Step(p.id(),scope,sid,key,p.value()==null?"":p.value(),p.enabled(),p.rationale()==null?"":p.rationale(),p.updatedAt(),applicable));
        }
        List<Decision> out=new ArrayList<>();
        for(var e:byKey.entrySet()){
            List<Step> trace=new ArrayList<>(e.getValue());
            trace.sort(Comparator.comparingInt((Step s)->rank(s.scopeType())).thenComparingLong(Step::updatedAt).thenComparing(Step::id));
            Step winner=null;
            for(Step s:trace)if(s.applicable()&&s.enabled()&&(winner==null||rank(s.scopeType())>rank(winner.scopeType())||(rank(s.scopeType())==rank(winner.scopeType())&&compareTie(s,winner)>0)))winner=s;
            if(winner!=null)out.add(new Decision(e.getKey(),winner.value(),winner.id(),winner.scopeType(),winner.scopeId(),winner.rationale(),List.copyOf(trace)));
        }
        return List.copyOf(out);
    }

    public static Decision evaluateKey(Collection<? extends PolicyLike> policies,Context context,String key){
        String k=normKey(key);for(Decision d:evaluate(policies,context))if(d.key().equals(k))return d;return null;
    }

    private static int compareTie(Step a,Step b){int c=Long.compare(a.updatedAt(),b.updatedAt());return c!=0?c:a.id().compareTo(b.id());}
    private static int rank(String scope){return switch(scope){case "WORLD"->0;case "REGION"->1;case "PROJECT"->2;case "WORK_AREA"->3;default->-1;};}
    private static String normScope(String s){String v=s==null?"WORLD":s.trim().toUpperCase(Locale.ROOT);return Set.of("WORLD","REGION","PROJECT","WORK_AREA").contains(v)?v:"WORLD";}
    private static String normKey(String s){return s==null?"":s.trim().toUpperCase(Locale.ROOT).replace(' ','_');}
    private static String normId(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT).replace(' ','_');}
}
