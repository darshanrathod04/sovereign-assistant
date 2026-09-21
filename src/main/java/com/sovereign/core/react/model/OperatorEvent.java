package com.sovereign.core.react.model;

/**
 * <b>OperatorEvent</b>
 *
 * <p>Streaming execution events emitted during the ReAct autonomous loop.</p>
 */
public sealed interface OperatorEvent permits
        OperatorEvent.StepStarted,
        OperatorEvent.StepCompleted,
        OperatorEvent.SelfCorrecting,
        OperatorEvent.GoalFinished {

    record StepStarted(PlanStep step, String thought) implements OperatorEvent {}

    record StepCompleted(PlanStep step, StepResult result) implements OperatorEvent {}

    record SelfCorrecting(PlanStep failedStep, String diagnosis, PlanStep mitigationStep) implements OperatorEvent {}

    record GoalFinished(GoalTask goal, boolean success, String summary) implements OperatorEvent {}
}
