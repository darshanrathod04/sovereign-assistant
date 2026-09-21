package com.sovereign;

import com.sovereign.core.react.engine.AutonomousOperator;
import com.sovereign.core.react.model.*;
import com.sovereign.core.react.planner.GoalDecomposer;
import com.sovereign.core.react.recovery.CausalErrorRecoveryEngine;
import com.sovereign.core.security.WorkspaceBoundary;
import com.sovereign.core.tools.FileSystemTool;
import com.sovereign.core.tools.ProcessControlTool;
import com.sovereign.core.tools.ShellExecutionTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignPhase2ReActTest</b>
 *
 * <p>Phase 2 Verification Test Suite validating Autonomous ReAct Cognitive Loop,
 * DAG Goal Decomposition, Causal Self-Correction, and Max Iteration Guardrails.</p>
 */
public class SovereignPhase2ReActTest {

    @Test
    @DisplayName("Test 1: GoalDecomposer converts complex goal into multi-step DAG plan")
    void testGoalDecomposerConvertsComplexGoalIntoDagPlan() {
        GoalDecomposer decomposer = new GoalDecomposer();
        String complexGoal = "Create target directory, write configuration file, and verify checksum";
        ExecutionPlan plan = decomposer.decompose(complexGoal);

        assertThat(plan).isNotNull();
        assertThat(plan.steps())
                .as("Complex goal must yield multiple sub-steps")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(plan.isDagValid())
                .as("Generated plan must form a valid, cycle-free DAG")
                .isTrue();

        PlanStep step1 = plan.steps().get(0);
        PlanStep step2 = plan.steps().get(1);
        PlanStep step3 = plan.steps().get(2);

        assertThat(step1.dependsOn())
                .as("Initial step has no dependencies")
                .isEmpty();
        assertThat(step2.dependsOn())
                .as("Second step must depend on first step")
                .contains(step1.stepId());
        assertThat(step3.dependsOn())
                .as("Third step must depend on second step")
                .contains(step2.stepId());

        assertThat(step1.toolName()).isEqualTo("shell_exec");
        assertThat(step2.toolName()).isEqualTo("file_write");
        assertThat(step3.toolName()).isEqualTo("file_read");
    }

    @Test
    @DisplayName("Test 2: AutonomousOperator completes a multi-step workflow autonomously")
    void testAutonomousOperatorCompletesMultiStepWorkflow(@TempDir Path tempWorkspace) throws IOException {
        WorkspaceBoundary boundary = new WorkspaceBoundary(tempWorkspace);
        FileSystemTool fsTool = new FileSystemTool(boundary);
        ShellExecutionTool shellTool = new ShellExecutionTool();
        ProcessControlTool procTool = new ProcessControlTool();

        GoalDecomposer decomposer = new GoalDecomposer();
        CausalErrorRecoveryEngine recoveryEngine = new CausalErrorRecoveryEngine();

        AutonomousOperator operator = new AutonomousOperator(decomposer, recoveryEngine, shellTool, fsTool, procTool);

        List<OperatorEvent> capturedEvents = new ArrayList<>();
        operator.addListener(capturedEvents::add);

        String goal = "Write 'data.json' with content '{\"status\":\"ok\"}', and read 'data.json'";
        AutonomousOperator.OperatorResult result = operator.execute(goal);

        assertThat(result.success())
                .as("Operator workflow must succeed")
                .isTrue();
        assertThat(result.iterationsExecuted())
                .as("Should execute at least 2 iterations")
                .isGreaterThanOrEqualTo(2);
        assertThat(fsTool.exists("data.json"))
                .as("File 'data.json' must exist in workspace")
                .isTrue();
        assertThat(fsTool.readFile("data.json"))
                .as("File content must match written payload")
                .isEqualTo("{\"status\":\"ok\"}");

        // Verify streaming ReAct trace events
        assertThat(capturedEvents).anyMatch(e -> e instanceof OperatorEvent.StepStarted);
        assertThat(capturedEvents).anyMatch(e -> e instanceof OperatorEvent.StepCompleted);
        assertThat(capturedEvents).anyMatch(e -> e instanceof OperatorEvent.GoalFinished && ((OperatorEvent.GoalFinished) e).success());
    }

    @Test
    @DisplayName("Test 3: Self-correction engine recovers from an intentional failing step")
    void testSelfCorrectionEngineRecoversFromFailingStep(@TempDir Path tempWorkspace) {
        WorkspaceBoundary boundary = new WorkspaceBoundary(tempWorkspace);
        FileSystemTool fsTool = new FileSystemTool(boundary);
        ShellExecutionTool shellTool = new ShellExecutionTool();
        ProcessControlTool procTool = new ProcessControlTool();

        // Custom decomposer that generates an intentional failing step (reading non-existent file)
        GoalDecomposer decomposer = new GoalDecomposer() {
            @Override
            public ExecutionPlan decompose(GoalTask goalTask) {
                PlanStep failStep = PlanStep.builder()
                        .stepId("step-1")
                        .description("Read missing configuration file 'missing.txt'")
                        .toolName("file_read")
                        .parameter("path", "missing.txt")
                        .build();
                return ExecutionPlan.of(goalTask.id(), List.of(failStep));
            }
        };

        CausalErrorRecoveryEngine recoveryEngine = new CausalErrorRecoveryEngine();
        AutonomousOperator operator = new AutonomousOperator(decomposer, recoveryEngine, shellTool, fsTool, procTool);

        List<OperatorEvent> events = new ArrayList<>();
        operator.addListener(events::add);

        AutonomousOperator.OperatorResult result = operator.execute("Read configuration from missing.txt");

        assertThat(result.success())
                .as("AutonomousOperator should succeed after causal self-correction")
                .isTrue();
        assertThat(result.selfCorrectionsExecuted())
                .as("Should record at least one self-correction mitigation")
                .isGreaterThan(0);
        assertThat(fsTool.exists("missing.txt"))
                .as("Missing file must have been created by self-correction engine")
                .isTrue();

        assertThat(events)
                .as("Must emit SelfCorrecting event")
                .anyMatch(e -> e instanceof OperatorEvent.SelfCorrecting);
    }

    @Test
    @DisplayName("Test 4: Max iteration guardrail stops execution if a task cannot converge")
    void testMaxIterationGuardrailStopsUnconvergedTask(@TempDir Path tempWorkspace) {
        WorkspaceBoundary boundary = new WorkspaceBoundary(tempWorkspace);
        FileSystemTool fsTool = new FileSystemTool(boundary);
        ShellExecutionTool shellTool = new ShellExecutionTool();
        ProcessControlTool procTool = new ProcessControlTool();

        // Intentional perpetual non-converging step
        GoalDecomposer decomposer = new GoalDecomposer() {
            @Override
            public ExecutionPlan decompose(GoalTask goalTask) {
                PlanStep infiniteStep = PlanStep.builder()
                        .stepId("step-perpetual")
                        .description("Attempt impossible tool action")
                        .toolName("non_existent_tool")
                        .build();
                return ExecutionPlan.of(goalTask.id(), List.of(infiniteStep));
            }
        };

        // Recovery engine that perpetually retries with a mitigation that also triggers retry
        CausalErrorRecoveryEngine recoveryEngine = new CausalErrorRecoveryEngine() {
            @Override
            public RecoveryDecision diagnoseAndRecover(PlanStep failedStep, StepResult failureResult) {
                PlanStep dummyRetry = PlanStep.builder()
                        .stepId(failedStep.stepId() + "-retry")
                        .description("Perpetual retry mitigation")
                        .toolName("shell_exec")
                        .parameter("command", "echo retry")
                        .build();
                return RecoveryDecision.recoverWith("Simulated perpetual root-cause", dummyRetry, true);
            }
        };

        AutonomousOperator operator = new AutonomousOperator(decomposer, recoveryEngine, shellTool, fsTool, procTool);
        operator.setMaxIterations(3); // Guardrail threshold

        AutonomousOperator.OperatorResult result = operator.execute("Perpetually unconverged task");

        assertThat(result.success())
                .as("Execution must fail when max iterations reached")
                .isFalse();
        assertThat(result.iterationsExecuted())
                .as("Iterations must hit configured max iteration guardrail limit")
                .isEqualTo(3);
        assertThat(result.summary())
                .as("Summary must state max iteration limit exceeded")
                .containsIgnoringCase("Maximum iteration limit");
        assertThat(result.goalTask().status())
                .as("Goal status must be marked FAILED")
                .isEqualTo(GoalStatus.FAILED);
    }
}
