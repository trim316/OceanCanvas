package net.oceancanvas.mod.worldgen;
/** Main-thread attribution only. Counts are attempted work, never certification or acceptance. */
final class OceanCanvasBlockProofWorkTelemetry {
 long admissionDeferrals,calls,missingContext,busyPublication,identityResets,epochResets,mixedSlices,discardedSamples,slices,attemptedSamples,cleanScanObservations,badScanObservations;
 long historicalPublications,historicalLoads,historicalArms,historicalDormantRetains,historicalParks,historicalWakeHolds;
 String snapshot(){return "admissionDeferrals="+admissionDeferrals+" calls="+calls+" missingContext="+missingContext+" busyPublication="+busyPublication+" identityResets="+identityResets+" epochResets="+epochResets+" mixedSlices="+mixedSlices+" discardedSamples="+discardedSamples+" slices="+slices+" attemptedSamples="+attemptedSamples+" cleanScanObservations="+cleanScanObservations+" badScanObservations="+badScanObservations+" historicalPublications="+historicalPublications+" historicalLoads="+historicalLoads+" historicalArms="+historicalArms+" historicalDormantRetains="+historicalDormantRetains+" historicalParks="+historicalParks+" historicalWakeHolds="+historicalWakeHolds;}
}
