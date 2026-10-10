package net.oceancanvas.mod.config;

/**
 * Three-state structure policy shared by global configuration, authored regions,
 * staged operations and networking. Persisted wire/save values remain the stable
 * enum names INHERIT, FORCE_ON and FORCE_OFF.
 */
public enum StructureOverride {
    /** Defer to the matching global structure rule. */
    INHERIT,
    /** Force the structure kind on/protected for the selected scope. */
    FORCE_ON,
    /** Force the structure kind off/cleared for the selected scope. */
    FORCE_OFF
}
