package com.sovereign.core.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shreeai.os.platform.sdk.MemorySDK;
import com.shreeai.os.platform.sdk.SDKResponse;
import com.sovereign.core.intent.UserIntentType;
import com.sovereign.core.react.engine.AutonomousOperator;
import com.sovereign.core.react.model.GoalStatus;
import com.sovereign.core.react.model.GoalTask;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * <b>EpisodicSessionLedger</b>
 *
 * <p>Chronological episodic memory ledger capturing past goal executions, durations,
 * intents, and outcomes backed by Shree AI OS {@link MemorySDK}.</p>
 */
public class EpisodicSessionLedger {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EpisodicEntry(
            String goalId,
            Instant timestamp,
            UserIntentType intent,
            String originalPrompt,
            String planSummary,
            GoalStatus executionStatus,
            double confidence,
            String observationSummary,
            long durationMs
    ) {
        // Backward-compatible accessors
        public String description() {
            return originalPrompt;
        }

        public GoalStatus status() {
            return executionStatus;
        }

        public boolean success() {
            return executionStatus == GoalStatus.SUCCESS
                    || executionStatus == GoalStatus.RECOVERED
                    || executionStatus == GoalStatus.COMPLETED;
        }

        public String summary() {
            return observationSummary;
        }
    }

    private final MemorySDK memorySdk;
    private final List<EpisodicEntry> history = new CopyOnWriteArrayList<>();

    public EpisodicSessionLedger() {
        this(null);
    }

    public EpisodicSessionLedger(MemorySDK memorySdk) {
        this.memorySdk = memorySdk;
    }

    /**
     * Records a completed goal execution into episodic memory using the full schema.
     */
    public EpisodicEntry recordGoal(String goalId, UserIntentType intent, String originalPrompt,
                                   String planSummary, GoalStatus status, double confidence,
                                   String observationSummary, long durationMs) {
        EpisodicEntry entry = new EpisodicEntry(
                goalId != null ? goalId : UUID.randomUUID().toString(),
                Instant.now(),
                intent != null ? intent : UserIntentType.EXECUTE,
                originalPrompt != null ? originalPrompt : "",
                planSummary != null ? planSummary : "",
                status != null ? status : GoalStatus.SUCCESS,
                confidence,
                observationSummary != null ? observationSummary : "",
                durationMs
        );

        history.add(entry);

        // Store into Shree AI OS MemorySDK
        if (memorySdk != null) {
            try {
                String json = MAPPER.writeValueAsString(entry);
                memorySdk.store("episodic:" + entry.goalId(), json);
                memorySdk.store("goal:" + entry.goalId(), entry.originalPrompt());
                memorySdk.store("last_executed_goal", entry.originalPrompt());
            } catch (Exception ignored) {
            }
        }

        return entry;
    }

    /**
     * Overload recording from legacy goal parameters for backward compatibility.
     */
    public EpisodicEntry recordGoal(GoalTask goal, boolean success, int iterations, int selfCorrections, String summary, long durationMs) {
        Objects.requireNonNull(goal, "GoalTask must not be null");
        GoalStatus status = goal.status();
        if (status == GoalStatus.PENDING || status == GoalStatus.RUNNING || status == GoalStatus.IN_PROGRESS) {
            status = success ? (selfCorrections > 0 ? GoalStatus.RECOVERED : GoalStatus.SUCCESS) : GoalStatus.FAILED;
        }
        return recordGoal(
                goal.id(),
                UserIntentType.EXECUTE,
                goal.description(),
                "Execution plan with " + iterations + " iterations",
                status,
                1.0,
                summary,
                durationMs
        );
    }

    /**
     * Overload recording directly from an {@link AutonomousOperator.OperatorResult}.
     */
    public EpisodicEntry recordGoal(AutonomousOperator.OperatorResult result, long durationMs) {
        return recordGoal(result, UserIntentType.EXECUTE, 1.0, durationMs);
    }

    public EpisodicEntry recordGoal(AutonomousOperator.OperatorResult result, UserIntentType intent, double confidence, long durationMs) {
        Objects.requireNonNull(result, "OperatorResult must not be null");
        GoalTask task = result.goalTask();
        String planSummary = result.plan() != null && result.plan().steps() != null
                ? result.plan().steps().stream().map(s -> s.description()).collect(Collectors.joining("; "))
                : "Plan";
        return recordGoal(
                task.id(),
                intent,
                task.description(),
                planSummary,
                task.status(),
                confidence,
                result.summary(),
                durationMs
        );
    }

    /**
     * Retrieves recent goal entries up to the specified limit.
     */
    public List<EpisodicEntry> getRecentGoals(int limit) {
        int size = history.size();
        if (size == 0) {
            return Collections.emptyList();
        }
        int from = Math.max(0, size - Math.max(1, limit));
        List<EpisodicEntry> subList = new ArrayList<>(history.subList(from, size));
        Collections.reverse(subList);
        return Collections.unmodifiableList(subList);
    }

    /**
     * Recalls a specific goal execution by its ID from memory.
     */
    public Optional<EpisodicEntry> recallGoal(String goalId) {
        if (goalId == null) {
            return Optional.empty();
        }

        // Check in-memory history first
        Optional<EpisodicEntry> match = history.stream()
                .filter(e -> e.goalId().equals(goalId))
                .findFirst();

        if (match.isPresent()) {
            return match;
        }

        // Fall back to MemorySDK recall
        if (memorySdk != null) {
            try {
                SDKResponse response = memorySdk.recall("episodic:" + goalId);
                if (response != null && response.getAnswer() != null && !response.getAnswer().isBlank()) {
                    EpisodicEntry entry = MAPPER.readValue(response.getAnswer(), EpisodicEntry.class);
                    history.add(entry);
                    return Optional.of(entry);
                }
            } catch (Exception ignored) {
            }
        }

        return Optional.empty();
    }

    public List<EpisodicEntry> getAllEntries() {
        return Collections.unmodifiableList(history);
    }

    public MemorySDK getMemorySdk() {
        return memorySdk;
    }
}
