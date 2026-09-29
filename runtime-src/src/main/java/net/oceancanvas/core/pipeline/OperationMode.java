package net.oceancanvas.core.pipeline;

/** Runtime authority is explicit rather than inferred from which queue a chunk entered. */
public enum OperationMode {
    SAFE_HOLD,
    CORE_AUTHORING,
    RECOVERY_ONLY
}
