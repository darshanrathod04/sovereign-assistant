package com.sovereign.core.react.model;

/**
 * <b>GoalStatus</b>
 *
 * <p>Deterministic lifecycle states of a high-level user goal in the ReAct cognitive loop.</p>
 */
public enum GoalStatus {
    /** Goal created and pending execution. */
    PENDING,

    /** Goal is currently executing in the cognitive loop. */
    RUNNING,

    /** All required DAG plan steps completed with exit code 0 and valid outputs. */
    SUCCESS,

    /** An execution step failed and could not be recovered. */
    FAILED,

    /** An initial step failed, but error recovery synthesized a mitigation that succeeded. */
    RECOVERED,

    /** Execution was cancelled before completion. */
    CANCELLED,

    /** Backward-compatibility alias for {@link #RUNNING}. */
    IN_PROGRESS,

    /** Backward-compatibility alias for {@link #SUCCESS}. */
    COMPLETED
}
