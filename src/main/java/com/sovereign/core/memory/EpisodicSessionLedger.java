package com.sovereign.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shreeai.os.platform.sdk.MemorySDK;
import com.shreeai.os.platform.sdk.SDKResponse;
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
 * and outcomes backed by Shree AI OS {@link MemorySDK}.</p>
 */
public class EpisodicSessionLedger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record EpisodicEntry(
            String goalId,
            String description,
            GoalStatus status,
            boolean success,
            int iterations,
            int selfCorrections,
            String summary,
            long durationMs,
            Instant timestamp
    ) {}

    private final MemorySDK memorySdk;
    private final List<EpisodicEntry> history = new CopyOnWriteArrayList<>();

    public EpisodicSessionLedger() {
        this(null);
    }

    public EpisodicSessionLedger(MemorySDK memorySdk) {
        this.memorySdk = memorySdk;
    }

    /**
     * Records a completed goal execution into episodic memory.
     */
    public EpisodicEntry recordGoal(GoalTask goal, boolean success, int iterations, int selfCorrections, String summary, long durationMs) {
        Objects.requireNonNull(goal, "GoalTask must not be null");

        EpisodicEntry entry = new EpisodicEntry(
                goal.id(),
                goal.description(),
                goal.status(),
                success,
                iterations,
                selfCorrections,
                summary != null ? summary : "",
                durationMs,
                Instant.now()
        );

        history.add(entry);

        // Store into Shree AI OS MemorySDK
        if (memorySdk != null) {
            try {
                String json = MAPPER.writeValueAsString(entry);
                memorySdk.store("episodic:" + goal.id(), json);
                memorySdk.store("goal:" + goal.id(), goal.description());
                memorySdk.store("last_executed_goal", goal.description());
            } catch (Exception ignored) {
            }
        }

        return entry;
    }

    /**
     * Overload recording directly from an {@link AutonomousOperator.OperatorResult}.
     */
    public EpisodicEntry recordGoal(AutonomousOperator.OperatorResult result, long durationMs) {
        Objects.requireNonNull(result, "OperatorResult must not be null");
        return recordGoal(
                result.goalTask(),
                result.success(),
                result.iterationsExecuted(),
                result.selfCorrectionsExecuted(),
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
