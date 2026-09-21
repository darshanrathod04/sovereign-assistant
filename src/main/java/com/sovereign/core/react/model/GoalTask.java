package com.sovereign.core.react.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>GoalTask</b>
 *
 * <p>Represents a high-level user objective or intent submitted to Sovereign Assistant.</p>
 */
public class GoalTask {

    private final String id;
    private final String description;
    private GoalStatus status;
    private final Instant createdAt;
    private Instant completedAt;

    public GoalTask(String description) {
        this("goal-" + UUID.randomUUID().toString().substring(0, 8), description);
    }

    public GoalTask(String id, String description) {
        this(id, description, GoalStatus.PENDING, Instant.now(), null);
    }

    public GoalTask(String id, String description, GoalStatus status, Instant createdAt, Instant completedAt) {
        this.id = Objects.requireNonNull(id, "Goal id must not be null");
        this.description = Objects.requireNonNull(description, "Goal description must not be null");
        this.status = Objects.requireNonNull(status, "Goal status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.completedAt = completedAt;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public GoalStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public synchronized GoalTask markRunning() {
        this.status = GoalStatus.RUNNING;
        return this;
    }

    public synchronized GoalTask markInProgress() {
        return markRunning();
    }

    public synchronized GoalTask markSuccess() {
        this.status = GoalStatus.SUCCESS;
        this.completedAt = Instant.now();
        return this;
    }

    public synchronized GoalTask markCompleted() {
        return markSuccess();
    }

    public synchronized GoalTask markRecovered() {
        this.status = GoalStatus.RECOVERED;
        this.completedAt = Instant.now();
        return this;
    }

    public synchronized GoalTask markFailed() {
        this.status = GoalStatus.FAILED;
        this.completedAt = Instant.now();
        return this;
    }

    public synchronized GoalTask markCancelled() {
        this.status = GoalStatus.CANCELLED;
        this.completedAt = Instant.now();
        return this;
    }

    public synchronized GoalTask withStatus(GoalStatus newStatus) {
        this.status = newStatus;
        if (newStatus == GoalStatus.SUCCESS || newStatus == GoalStatus.COMPLETED
                || newStatus == GoalStatus.FAILED || newStatus == GoalStatus.RECOVERED
                || newStatus == GoalStatus.CANCELLED) {
            this.completedAt = Instant.now();
        }
        return this;
    }

    @Override
    public String toString() {
        return "GoalTask{" +
                "id='" + id + '\'' +
                ", description='" + description + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
