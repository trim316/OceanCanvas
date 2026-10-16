package net.oceancanvas.test;
/** Counterfactual lookup counts only; never supplies a DataLayer to Minecraft. */
public final class LayerCacheModel {
 private final long[] keys; private final boolean[] valid;
 public long hits,misses;
 public LayerCacheModel(int size){if(Integer.bitCount(size)!=1)throw new IllegalArgumentException();keys=new long[size];valid=new boolean[size];}
 public void access(long key){int i=(int)mix(key)&(keys.length-1);if(valid[i]&&keys[i]==key)hits++;else{misses++;keys[i]=key;valid[i]=true;}}
 public void invalidate(){java.util.Arrays.fill(valid,false);}
 private static long mix(long x){x^=x>>>33;x*=0xff51afd7ed558ccdL;x^=x>>>33;x*=0xc4ceb9fe1a85ec53L;return x^(x>>>33);}
}
