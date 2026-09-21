package com.sovereign.core.react.model;

import java.util.Objects;

/**
 * <b>StepResult</b>
 *
 * <p>Structured outcome of a tool invocation within a ReAct cognitive iteration.</p>
 */
public record StepResult(
        String stepId,
        boolean success,
        String stdout,
        String stderr,
        String observation,
        long durationMs
) {
    public StepResult {
        Objects.requireNonNull(stepId, "stepId must not be null");
        stdout = stdout != null ? stdout : "";
        stderr = stderr != null ? stderr : "";
        observation = observation != null ? observation : "";
    }

    public static StepResult success(String stepId, String stdout, String observation, long durationMs) {
        return new StepResult(stepId, true, stdout, "", observation, durationMs);
    }

    public static StepResult failure(String stepId, String stderr, String observation, long durationMs) {
        return new StepResult(stepId, false, "", stderr, observation, durationMs);
    }
}
