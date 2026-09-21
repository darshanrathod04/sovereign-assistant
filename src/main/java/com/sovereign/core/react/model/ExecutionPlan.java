package com.sovereign.core.react.model;

import java.time.Instant;
import java.util.*;

/**
 * <b>ExecutionPlan</b>
 *
 * <p>Represents a structured DAG plan composed of ordered {@link PlanStep} items
 * designed to fulfill a user {@link GoalTask}.</p>
 */
public record ExecutionPlan(
        String planId,
        String goalId,
        List<PlanStep> steps,
        Instant createdAt
) {
    public ExecutionPlan {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(goalId, "goalId must not be null");
        steps = steps != null ? List.copyOf(steps) : Collections.emptyList();
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static ExecutionPlan of(String goalId, List<PlanStep> steps) {
        return new ExecutionPlan("plan-" + UUID.randomUUID().toString().substring(0, 8), goalId, steps, Instant.now());
    }

    /**
     * Finds a step by its unique ID.
     */
    public Optional<PlanStep> getStep(String stepId) {
        if (stepId == null) {
            return Optional.empty();
        }
        return steps.stream().filter(s -> s.stepId().equals(stepId)).findFirst();
    }

    /**
     * Determines whether the dependencies in this plan form a valid, cycle-free DAG.
     */
    public boolean isDagValid() {
        Set<String> stepIds = new HashSet<>();
        for (PlanStep step : steps) {
            stepIds.add(step.stepId());
        }

        // Verify all dependsOn references exist
        for (PlanStep step : steps) {
            for (String dep : step.dependsOn()) {
                if (!stepIds.contains(dep)) {
                    return false;
                }
            }
        }

        // Cycle check using topological Kahn's algorithm or DFS
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();

        for (String id : stepIds) {
            inDegree.put(id, 0);
            adj.put(id, new ArrayList<>());
        }

        for (PlanStep step : steps) {
            for (String dep : step.dependsOn()) {
                adj.get(dep).add(step.stepId());
                inDegree.put(step.stepId(), inDegree.get(step.stepId()) + 1);
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        int visitedCount = 0;
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            visitedCount++;
            for (String neighbor : adj.get(curr)) {
                int updated = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, updated);
                if (updated == 0) {
                    queue.add(neighbor);
                }
            }
        }

        return visitedCount == stepIds.size();
    }

    /**
     * Returns the next executable steps whose prerequisites are fulfilled and have not yet completed.
     */
    public List<PlanStep> getNextExecutableSteps(Set<String> completedStepIds) {
        List<PlanStep> readySteps = new ArrayList<>();
        for (PlanStep step : steps) {
            if (completedStepIds.contains(step.stepId())) {
                continue;
            }
            boolean dependenciesSatisfied = true;
            for (String dep : step.dependsOn()) {
                if (!completedStepIds.contains(dep)) {
                    dependenciesSatisfied = false;
                    break;
                }
            }
            if (dependenciesSatisfied) {
                readySteps.add(step);
            }
        }
        return readySteps;
    }
}
