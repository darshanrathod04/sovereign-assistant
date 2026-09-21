package com.sovereign.core.react.model;

import java.util.*;

/**
 * <b>PlanStep</b>
 *
 * <p>An individual executable step within an {@link ExecutionPlan}, binding tool invocation
 * parameters, prerequisite dependencies (forming a DAG), and an optional rollback action.</p>
 */
public record PlanStep(
        String stepId,
        String description,
        String toolName,
        Map<String, Object> parameters,
        List<String> dependsOn,
        String rollbackAction
) {
    public PlanStep {
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        parameters = parameters != null ? Map.copyOf(parameters) : Collections.emptyMap();
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String stepId;
        private String description;
        private String toolName;
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private final List<String> dependsOn = new ArrayList<>();
        private String rollbackAction;

        public Builder stepId(String stepId) {
            this.stepId = stepId;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder parameter(String key, Object value) {
            this.parameters.put(key, value);
            return this;
        }

        public Builder parameters(Map<String, Object> params) {
            if (params != null) {
                this.parameters.putAll(params);
            }
            return this;
        }

        public Builder dependsOn(String prerequisiteStepId) {
            if (prerequisiteStepId != null && !prerequisiteStepId.isBlank()) {
                this.dependsOn.add(prerequisiteStepId);
            }
            return this;
        }

        public Builder dependsOn(List<String> prerequisiteStepIds) {
            if (prerequisiteStepIds != null) {
                this.dependsOn.addAll(prerequisiteStepIds);
            }
            return this;
        }

        public Builder rollbackAction(String rollbackAction) {
            this.rollbackAction = rollbackAction;
            return this;
        }

        public PlanStep build() {
            return new PlanStep(stepId, description, toolName, parameters, dependsOn, rollbackAction);
        }
    }
}
