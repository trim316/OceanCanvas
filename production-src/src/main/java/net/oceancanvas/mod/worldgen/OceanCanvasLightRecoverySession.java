package net.oceancanvas.mod.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/** Server-session-owned identity and attempt state for bounded lighting recovery. */
final class OceanCanvasLightRecoverySession {
    // v253.125.43: transient scalar repair state is primitive; recovery policy is unchanged.
    final OceanCanvasPrimitiveLongIntMap verifyEscalations = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongIntMap hardSkyResetRadius1 = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongSet skyQuarantine = new OceanCanvasPrimitiveLongSet();
    final OceanCanvasPrimitiveLongIntMap hardSkyResetCounts = new OceanCanvasPrimitiveLongIntMap();
    /** True strict-light failures only. Retry streak/quarantine policy is tied to this lane. */
    // v253.125.42: these are the largest long-lived lighting-debt maps. Keep
    // due ticks primitive so a large dormant cohort does not box key+value pairs.
    final OceanCanvasPrimitiveLongLongMap skyBackoffUntilTick = new OceanCanvasPrimitiveLongLongMap();
    /**
     * Scheduler-only dormancy used by Forever World pressure control. These entries have
     * not failed the strict skylight oracle merely because they were parked, so they must
     * never share the persistent-fault backoff/quarantine identity above.
     */
    final OceanCanvasPrimitiveLongLongMap pressureParkUntilTick = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongIntMap deepZeroScrubCounts = new OceanCanvasPrimitiveLongIntMap();
    /** v253.125.14 visible-only dense public light recheck attempts. */
    final OceanCanvasPrimitiveLongIntMap visibleDeepDenseRepairCounts = new OceanCanvasPrimitiveLongIntMap();
    /** v253.125.16 bounded cross-chunk escalation after two local dense waves fail. */
    final OceanCanvasPrimitiveLongIntMap visibleDeepClusterRepairCounts = new OceanCanvasPrimitiveLongIntMap();
    /**
     * v253.125.25 resumable deep-repair state. Large checkBlock waves are sliced
     * across server ticks instead of executing as one 4k-200k+ atomic burst.
     * All state is transient and is discarded on physical mutation/cancel/restart.
     */
    final OceanCanvasPrimitiveLongIntMap visibleDeepDenseSectionY = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongIntMap visibleDeepDenseCursor = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongLongMap visibleDeepDenseChecksAccumulated = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongIntMap visibleDeepClusterAttemptInFlight = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongIntMap visibleDeepClusterSectionY = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongLongMap visibleDeepClusterCursor = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongLongMap visibleDeepClusterChecksAccumulated = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongIntMap deepRepairHeapDeferralCounts = new OceanCanvasPrimitiveLongIntMap();
    /** Highest pathology milestone already reported for a strict-failure epoch. */
    final OceanCanvasPrimitiveLongIntMap pathologyHotspotLevel = new OceanCanvasPrimitiveLongIntMap();
    /** World-wide admission gate so a visible multi-chunk repair cannot storm the light executor. */
    final AtomicLong visibleDeepClusterNextAllowedTick = new AtomicLong(Long.MIN_VALUE);
    final OceanCanvasPrimitiveLongIntMap deepZeroPublicRecoveryCounts = new OceanCanvasPrimitiveLongIntMap();
    final OceanCanvasPrimitiveLongLongMap deepZeroPublicRecoveryRetryAfterTick = new OceanCanvasPrimitiveLongLongMap();
    final OceanCanvasPrimitiveLongIntMap postAuditRegressions = new OceanCanvasPrimitiveLongIntMap();
}
