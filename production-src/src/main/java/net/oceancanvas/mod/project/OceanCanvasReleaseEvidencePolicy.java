package net.oceancanvas.mod.project;

import java.util.Locale;

/**
 * Pure policy for build-bound release-certification evidence.
 *
 * <p>Runtime certification is intentionally invalidated when the Ocean Canvas build changes.
 * A world may retain its append-only historical evidence, but a later build must be exercised
 * again before any manually certified release gate can become PASS.</p>
 */
public final class OceanCanvasReleaseEvidencePolicy {
    private OceanCanvasReleaseEvidencePolicy(){}

    private static final String PREFIX="release-gate:v2:";

    public static String normalizeGate(String raw){
        String v=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);
        if(v.matches("[1-9]"))v="OC-R00"+v;
        else if(v.equals("10"))v="OC-R010";
        else if(v.matches("R00[1-9]"))v="OC-"+v;
        else if(v.equals("R010"))v="OC-R010";
        if(!v.matches("OC-R00[1-9]|OC-R010"))throw new IllegalArgumentException("gate must be 1..10 or OC-R001..OC-R010");
        return v;
    }

    public static String sourceRef(String gateId,String build){
        String gate=normalizeGate(gateId);
        String b=build==null?"":build.trim();
        if(!b.matches("v\\d+(?:\\.\\d+){2,3}"))throw new IllegalArgumentException("invalid Ocean Canvas build identity: "+b);
        return PREFIX+b+":"+gate;
    }

    public static boolean matchesCurrentBuild(String sourceRef,String gateId,String build){
        return sourceRef!=null&&sourceRef.equals(sourceRef(gateId,build));
    }

    public static boolean manuallyCertifiable(String gateId){
        String gate=normalizeGate(gateId);
        return !gate.equals("OC-R004")&&!gate.equals("OC-R005")&&!gate.equals("OC-R010");
    }
}
