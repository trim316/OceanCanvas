package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import java.util.*;
final class OceanCanvasLightProofSelectionTest {
 static int size(long[] a){return Arrays.stream(a).mapToInt(Long::bitCount).sum();}
 @Test void runtimeRetainedScopeIsLookedUpOnceRatherThanForEveryColumn(){int[] scopeReads={0},selectedReads={0};var bits=OceanCanvasLightProofSelection.snapshot(()->{scopeReads[0]++;return true;},c->true,c->{selectedReads[0]++;return true;});assertEquals(1,scopeReads[0]);assertEquals(256,selectedReads[0]);assertEquals(256,size(bits));}
 @Test void exactSouthPartialKeepsOnlySelected128Columns(){var bits=OceanCanvasLightProofSelection.snapshot(()->true,c->true,c->(c&15)>=8);assertEquals(128,size(bits));for(int c=0;c<256;c++)assertEquals((c&15)>=8,(bits[c>>>6]&(1L<<(c&63)))!=0);}
 @Test void outsideCanvasAndAbsentRetainedScopeFollowOriginalShortCircuitPolicy(){int[] calls={0};var bits=OceanCanvasLightProofSelection.snapshot(()->false,c->c<10,c->{calls[0]++;throw new AssertionError();});assertEquals(10,size(bits));assertEquals(0,calls[0]);var outside=OceanCanvasLightProofSelection.snapshot(()->true,c->false,c->{throw new AssertionError();});assertEquals(0,size(outside));}
 @Test void nextInvocationNeverBorrowsOldScopeOrMutableMask(){boolean[] scope={true};var first=OceanCanvasLightProofSelection.snapshot(()->scope[0],c->true,c->false);scope[0]=false;var second=OceanCanvasLightProofSelection.snapshot(()->scope[0],c->true,c->false);assertEquals(0,size(first));assertEquals(256,size(second));second[0]=0;assertEquals(0,size(first));}
}
