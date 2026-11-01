package net.oceancanvas.mod.worldgen;
/** Main-thread attribution only. Counts are attempted work, never certification or acceptance. */
final class OceanCanvasBlockProofWorkTelemetry {
 long calls,missingContext,busyPublication,identityResets,epochResets,mixedSlices,discardedSamples,slices,attemptedSamples,cleanScanObservations,badScanObservations;
 String snapshot(){return "calls="+calls+" missingContext="+missingContext+" busyPublication="+busyPublication+" identityResets="+identityResets+" epochResets="+epochResets+" mixedSlices="+mixedSlices+" discardedSamples="+discardedSamples+" slices="+slices+" attemptedSamples="+attemptedSamples+" cleanScanObservations="+cleanScanObservations+" badScanObservations="+badScanObservations;}
}
