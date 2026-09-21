package com.sovereign.core.react.engine;

import com.sovereign.core.react.model.*;
import com.sovereign.core.react.planner.GoalDecomposer;
import com.sovereign.core.react.recovery.CausalErrorRecoveryEngine;
import com.sovereign.core.tools.FileSystemTool;
import com.sovereign.core.tools.ProcessControlTool;
import com.sovereign.core.tools.ShellExecutionTool;
import com.sovereign.core.workspace.WorkspaceContext;
import com.sovereign.core.workspace.WorkspaceContextIndexer;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>AutonomousOperator</b>
 *
 * <p>Central ReAct (Reasoning + Acting) autonomous execution engine for Sovereign Assistant.
 * Coordinates cognitive planning, step execution, observation evaluation, self-correction,
 * and max-iteration guardrail enforcement.</p>
 */
public class AutonomousOperator {

    public record OperatorResult(
            GoalTask goalTask,
            ExecutionPlan plan,
            boolean success,
            int iterationsExecuted,
            int selfCorrectionsExecuted,
            List<StepResult> stepResults,
            String summary
    ) {}

    private final GoalDecomposer decomposer;
    private final CausalErrorRecoveryEngine recoveryEngine;
    private final ShellExecutionTool shellTool;
    private final FileSystemTool fileSystemTool;
    private final ProcessControlTool processControlTool;

    private int maxIterations = 10;
    private final List<OperatorEventListener> listeners = new CopyOnWriteArrayList<>();

    public AutonomousOperator(GoalDecomposer decomposer,
                              CausalErrorRecoveryEngine recoveryEngine,
                              ShellExecutionTool shellTool,
                              FileSystemTool fileSystemTool,
                              ProcessControlTool processControlTool) {
        this.decomposer = Objects.requireNonNull(decomposer, "GoalDecomposer must not be null");
        this.recoveryEngine = Objects.requireNonNull(recoveryEngine, "CausalErrorRecoveryEngine must not be null");
        this.shellTool = Objects.requireNonNull(shellTool, "ShellExecutionTool must not be null");
        this.fileSystemTool = Objects.requireNonNull(fileSystemTool, "FileSystemTool must not be null");
        this.processControlTool = Objects.requireNonNull(processControlTool, "ProcessControlTool must not be null");
    }

    public void addListener(OperatorEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(OperatorEventListener listener) {
        listeners.remove(listener);
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = Math.max(1, maxIterations);
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Executes a high-level goal through the autonomous ReAct cognitive loop.
     */
    public OperatorResult execute(String goalDescription) {
        return execute(new GoalTask(goalDescription));
    }

    /**
     * Executes a {@link GoalTask} through the autonomous ReAct cognitive loop.
     */
    public OperatorResult execute(GoalTask goalTask) {
        Objects.requireNonNull(goalTask, "GoalTask must not be null");
        goalTask.markRunning();

        // 1. Decompose goal into multi-step DAG ExecutionPlan
        ExecutionPlan plan = decomposer.decompose(goalTask);
        if (plan.steps().isEmpty()) {
            goalTask.markFailed();
            String summary = "Failed to generate execution plan from goal.";
            emit(new OperatorEvent.GoalFinished(goalTask, false, summary));
            return new OperatorResult(goalTask, plan, false, 0, 0, Collections.emptyList(), summary);
        }

        Set<String> allStepIds = new HashSet<>();
        for (PlanStep step : plan.steps()) {
            allStepIds.add(step.stepId());
        }

        Set<String> completedStepIds = new LinkedHashSet<>();
        List<StepResult> stepResults = new ArrayList<>();
        Queue<PlanStep> stepQueue = new ArrayDeque<>(plan.getNextExecutableSteps(completedStepIds));

        int iterations = 0;
        int selfCorrections = 0;

        // 2. ReAct Cognitive Loop with Max Iteration Guardrail
        while (completedStepIds.size() < allStepIds.size()) {
            // Guardrail check: prevent infinite loops
            if (iterations >= maxIterations) {
                goalTask.markFailed();
                String summary = "Execution aborted by Guardrail: Maximum iteration limit (" + maxIterations + ") reached without convergence.";
                emit(new OperatorEvent.GoalFinished(goalTask, false, summary));
                return new OperatorResult(goalTask, plan, false, iterations, selfCorrections, stepResults, summary);
            }

            PlanStep currentStep = stepQueue.poll();
            if (currentStep == null) {
                List<PlanStep> ready = plan.getNextExecutableSteps(completedStepIds);
                if (ready.isEmpty()) {
                    goalTask.markFailed();
                    String summary = "Execution blocked: Dependency requirements could not be satisfied.";
                    emit(new OperatorEvent.GoalFinished(goalTask, false, summary));
                    return new OperatorResult(goalTask, plan, false, iterations, selfCorrections, stepResults, summary);
                }
                currentStep = ready.get(0);
            }

            iterations++;

            // [THOUGHT] formulation
            String thought = "Planning to execute step [" + currentStep.stepId() + "]: "
                    + currentStep.description() + " using tool '" + currentStep.toolName() + "'";
            emit(new OperatorEvent.StepStarted(currentStep, thought));

            // [ACTION] execution
            StepResult result = executeTool(currentStep);
            stepResults.add(result);

            // [OBSERVATION] evaluation
            if (result.success()) {
                completedStepIds.add(currentStep.stepId());
                emit(new OperatorEvent.StepCompleted(currentStep, result));

                // Enqueue any newly unblocked DAG steps
                for (PlanStep readyStep : plan.getNextExecutableSteps(completedStepIds)) {
                    if (!stepQueue.contains(readyStep)) {
                        stepQueue.add(readyStep);
                    }
                }
            } else {
                emit(new OperatorEvent.StepCompleted(currentStep, result));

                // 3. Causal Self-Correction & Error Recovery
                CausalErrorRecoveryEngine.RecoveryDecision decision = recoveryEngine.diagnoseAndRecover(currentStep, result);

                if (decision.canRecover() && decision.mitigationStep() != null) {
                    selfCorrections++;
                    emit(new OperatorEvent.SelfCorrecting(currentStep, decision.diagnosis(), decision.mitigationStep()));

                    // Execute synthesized mitigation step
                    StepResult mitigationResult = executeTool(decision.mitigationStep());
                    stepResults.add(mitigationResult);

                    if (mitigationResult.success()) {
                        if (decision.retryOriginal()) {
                            // Retry the original failed step
                            stepQueue.add(currentStep);
                        } else {
                            completedStepIds.add(currentStep.stepId());
                        }
                    } else {
                        goalTask.markFailed();
                        String summary = "Self-correction mitigation step failed: " + mitigationResult.stderr();
                        emit(new OperatorEvent.GoalFinished(goalTask, false, summary));
                        return new OperatorResult(goalTask, plan, false, iterations, selfCorrections, stepResults, summary);
                    }
                } else {
                    goalTask.markFailed();
                    String summary = "Step execution failed and could not be recovered: " + result.stderr();
                    emit(new OperatorEvent.GoalFinished(goalTask, false, summary));
                    return new OperatorResult(goalTask, plan, false, iterations, selfCorrections, stepResults, summary);
                }
            }
        }

        // 4. Final Result formulation
        if (completedStepIds.size() < allStepIds.size()) {
            goalTask.markFailed();
            String summary = "Goal execution failed: Not all plan steps completed.";
            emit(new OperatorEvent.GoalFinished(goalTask, false, summary));
            return new OperatorResult(goalTask, plan, false, iterations, selfCorrections, stepResults, summary);
        }

        if (selfCorrections > 0) {
            goalTask.markRecovered();
        } else {
            goalTask.markSuccess();
        }
        String summary = "Successfully completed goal with " + completedStepIds.size() + " steps in " + iterations + " iterations.";
        emit(new OperatorEvent.GoalFinished(goalTask, true, summary));
        return new OperatorResult(goalTask, plan, true, iterations, selfCorrections, stepResults, summary);
    }

    private StepResult executeTool(PlanStep step) {
        long start = System.currentTimeMillis();
        try {
            return switch (step.toolName().toLowerCase()) {
                case "shell_exec" -> {
                    String cmd = (String) step.parameters().getOrDefault("command", "");
                    ShellExecutionTool.ShellResult res = shellTool.execute(cmd);
                    long duration = System.currentTimeMillis() - start;
                    if (res.isSuccess()) {
                        yield StepResult.success(step.stepId(), res.stdout(), "Shell command exited successfully (code 0)", duration);
                    } else {
                        yield StepResult.failure(step.stepId(), res.stderr(), "Shell command failed with exit code " + res.exitCode(), duration);
                    }
                }
                case "file_write" -> {
                    String path = (String) step.parameters().get("path");
                    String content = (String) step.parameters().getOrDefault("content", "");
                    fileSystemTool.atomicWriteFile(path, content);
                    long duration = System.currentTimeMillis() - start;
                    yield StepResult.success(step.stepId(), "Wrote " + content.length() + " chars to " + path, "File written atomically to " + path, duration);
                }
                case "file_read" -> {
                    String path = (String) step.parameters().get("path");
                    String content = fileSystemTool.readFile(path);
                    long duration = System.currentTimeMillis() - start;
                    yield StepResult.success(step.stepId(), content, "Read " + content.length() + " chars from " + path, duration);
                }
                case "file_walk" -> {
                    String path = (String) step.parameters().getOrDefault("path", ".");
                    int depth = ((Number) step.parameters().getOrDefault("maxDepth", 4)).intValue();
                    var list = fileSystemTool.walkDirectory(path, depth);
                    long duration = System.currentTimeMillis() - start;

                    String observationOutput;
                    String description = step.description().toLowerCase();
                    if (description.contains("source") || description.contains("java")) {
                        Set<String> roots = new LinkedHashSet<>();
                        for (FileSystemTool.FileInfo info : list) {
                            String rel = info.relativePath().replace('\\', '/');
                            if (rel.contains("src/main/java")) {
                                int idx = rel.indexOf("src/main/java");
                                roots.add(rel.substring(0, idx) + "src/main/java");
                            }
                            if (rel.contains("src/test/java")) {
                                int idx = rel.indexOf("src/test/java");
                                roots.add(rel.substring(0, idx) + "src/test/java");
                            }
                        }
                        List<String> sourceRoots = new ArrayList<>(roots);
                        if (sourceRoots.isEmpty()) {
                            sourceRoots = list.stream()
                                    .filter(FileSystemTool.FileInfo::isDirectory)
                                    .map(p -> p.relativePath().replace('\\', '/'))
                                    .filter(p -> p.endsWith("java") || p.endsWith("src"))
                                    .distinct()
                                    .toList();
                        }
                        observationOutput = "Identified Java source directories: " + sourceRoots;
                    } else {
                        List<String> paths = list.stream()
                                .map(p -> p.relativePath().replace('\\', '/'))
                                .toList();
                        observationOutput = paths.size() > 20
                                ? "Found " + paths.size() + " paths: " + paths.subList(0, 20) + "..."
                                : "Found paths: " + paths;
                    }
                    yield StepResult.success(step.stepId(), observationOutput, observationOutput, duration);
                }
                case "project_analyze" -> {
                    WorkspaceContext ctx = decomposer != null && decomposer.getContextIndexer() != null
                            ? decomposer.getContextIndexer().getContext()
                            : new WorkspaceContextIndexer().getContext();
                    if (decomposer != null && decomposer.getContextIndexer() != null && decomposer.getContextIndexer().getProjectSdk() != null) {
                        try {
                            decomposer.getContextIndexer().getProjectSdk().analyze(ctx.rootPath());
                        } catch (Exception ignored) {
                        }
                    }
                    String summary = "Workspace Analysis: Project=" + ctx.projectName()
                            + ", Version=" + ctx.projectVersion()
                            + ", BuildTool=" + ctx.buildTool()
                            + ", Framework=" + ctx.detectedFramework()
                            + ", SourceRoots=" + ctx.sourceDirectories();
                    long duration = System.currentTimeMillis() - start;
                    yield StepResult.success(step.stepId(), summary, summary, duration);
                }
                case "process_list" -> {
                    String filter = (String) step.parameters().getOrDefault("filter", null);
                    var procs = processControlTool.queryProcesses(filter);
                    long duration = System.currentTimeMillis() - start;
                    yield StepResult.success(step.stepId(), procs.toString(), "Active processes queried: " + procs.size() + " found", duration);
                }
                default -> {
                    long duration = System.currentTimeMillis() - start;
                    yield StepResult.failure(step.stepId(), "Unknown tool: " + step.toolName(), "Tool dispatch failed", duration);
                }
            };
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return StepResult.failure(step.stepId(), e.getMessage() != null ? e.getMessage() : e.toString(),
                    "Tool invocation threw exception: " + e.getClass().getSimpleName(), duration);
        }
    }

    private void emit(OperatorEvent event) {
        for (OperatorEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ignored) {
            }
        }
    }

    public GoalDecomposer getDecomposer() {
        return decomposer;
    }

    public CausalErrorRecoveryEngine getRecoveryEngine() {
        return recoveryEngine;
    }
}
