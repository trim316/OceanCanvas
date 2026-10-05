package net.oceancanvas.mod.worldgen;

import java.util.Objects;

import net.oceancanvas.mod.config.StructureOverride;

/**
 * Neutral bridge for temporary structure-rule overrides owned by the currently
 * active terrain operation.
 *
 * <p>The region/worldgen layer must not depend on the Pregen implementation in
 * order to resolve structure policy.  The operation controller installs its
 * resolver during mod initialization; until then (and in isolated tests) this
 * provider fails safe to {@link StructureOverride#INHERIT}.</p>
 *
 * <p>The resolver is process-static by design: it is a method reference to the
 * operation controller, not server/world state.  Per-server/per-job state
 * remains owned by that controller.</p>
 */
public final class OceanCanvasActiveStructureRuleProvider {
    @FunctionalInterface
    public interface Resolver {
        StructureOverride resolve(OceanCanvasStructureKind kind, int chunkX, int chunkZ);
    }

    private static final Resolver INHERIT_ONLY = (kind, chunkX, chunkZ) -> StructureOverride.INHERIT;
    private static volatile Resolver resolver = INHERIT_ONLY;

    private OceanCanvasActiveStructureRuleProvider() {
    }

    /** Install the active-operation rule resolver. */
    public static void install(Resolver newResolver) {
        resolver = Objects.requireNonNull(newResolver, "newResolver");
    }

    /** Resolve one temporary job-level rule without exposing its owner type. */
    public static StructureOverride resolve(OceanCanvasStructureKind kind, int chunkX, int chunkZ) {
        Objects.requireNonNull(kind, "kind");
        StructureOverride value = resolver.resolve(kind, chunkX, chunkZ);
        return value == null ? StructureOverride.INHERIT : value;
    }
}
