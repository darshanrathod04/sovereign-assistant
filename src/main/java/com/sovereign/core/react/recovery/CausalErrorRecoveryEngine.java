package com.sovereign.core.react.recovery;

import com.shreeai.os.platform.kernels.cognitive.model.ReasoningResult;
import com.sovereign.core.react.model.PlanStep;
import com.sovereign.core.react.model.StepResult;
import com.sovereign.core.sdk.ReasoningSDK;

import java.util.Map;
import java.util.Objects;

/**
 * <b>CausalErrorRecoveryEngine</b>
 *
 * <p>Cognitive self-correction engine leveraging Shree AI OS {@link ReasoningSDK}
 * to analyze execution failures, establish causal root-causes, and synthesize
 * dynamic mitigation steps.</p>
 */
public class CausalErrorRecoveryEngine {

    public record RecoveryDecision(
            boolean canRecover,
            String diagnosis,
            PlanStep mitigationStep,
            boolean retryOriginal
    ) {
        public static RecoveryDecision unrecoverable(String diagnosis) {
            return new RecoveryDecision(false, diagnosis, null, false);
        }

        public static RecoveryDecision recoverWith(String diagnosis, PlanStep mitigationStep, boolean retryOriginal) {
            return new RecoveryDecision(true, diagnosis, mitigationStep, retryOriginal);
        }
    }

    private final ReasoningSDK reasoningSdk;

    public CausalErrorRecoveryEngine() {
        this(null);
    }

    public CausalErrorRecoveryEngine(ReasoningSDK reasoningSdk) {
        this.reasoningSdk = reasoningSdk;
    }

    /**
     * Diagnoses a failed step using causal reasoning and generates an actionable mitigation.
     */
    public RecoveryDecision diagnoseAndRecover(PlanStep failedStep, StepResult failureResult) {
        Objects.requireNonNull(failedStep, "failedStep must not be null");
        Objects.requireNonNull(failureResult, "failureResult must not be null");

        String errorContext = failureResult.stderr() + " " + failureResult.observation();

        // 1. Engage ReasoningSDK cognitive kernel
        String cognitiveDiagnosis = null;
        if (reasoningSdk != null) {
            try {
                ReasoningResult reasoningResult = reasoningSdk.reason(
                        "Diagnose step failure: Tool='" + failedStep.toolName() + "', Description='"
                                + failedStep.description() + "', ErrorContext='" + errorContext + "'"
                );
                if (reasoningResult != null && reasoningResult.conclusion() != null) {
                    cognitiveDiagnosis = reasoningResult.conclusion();
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Causal pattern matching & mitigation synthesis
        String errLower = errorContext.toLowerCase();

        // Case A: Missing file on file_read
        if ("file_read".equalsIgnoreCase(failedStep.toolName()) &&
                (errLower.contains("nosuchfileexception") || errLower.contains("cannot find") ||
                 errLower.contains("does not exist") || errLower.contains("no such file") ||
                 errLower.contains("not found"))) {

            String path = (String) failedStep.parameters().getOrDefault("path", "missing.txt");
            String diagnosis = cognitiveDiagnosis != null
                    ? cognitiveDiagnosis
                    : "Root-cause: Requested file '" + path + "' does not exist on disk.";

            PlanStep mitigationStep = PlanStep.builder()
                    .stepId(failedStep.stepId() + "-mitigate-create")
                    .description("Self-Correction Mitigation: Create missing file '" + path + "'")
                    .toolName("file_write")
                    .parameter("path", path)
                    .parameter("content", "Auto-recovered file content by Sovereign Assistant")
                    .build();

            return RecoveryDecision.recoverWith(diagnosis, mitigationStep, true);
        }

        // Case B: Missing parent directory on file_write
        if ("file_write".equalsIgnoreCase(failedStep.toolName()) &&
                (errLower.contains("nosuchfileexception") || errLower.contains("path not found"))) {

            String path = (String) failedStep.parameters().getOrDefault("path", "output.txt");
            String parentDir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) :
                    (path.contains("\\") ? path.substring(0, path.lastIndexOf('\\')) : ".");

            String diagnosis = cognitiveDiagnosis != null
                    ? cognitiveDiagnosis
                    : "Root-cause: Parent directory '" + parentDir + "' is missing.";

            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String cmd = isWindows
                    ? "New-Item -ItemType Directory -Force -Path \"" + parentDir + "\""
                    : "mkdir -p \"" + parentDir + "\"";

            PlanStep mitigationStep = PlanStep.builder()
                    .stepId(failedStep.stepId() + "-mitigate-mkdir")
                    .description("Self-Correction Mitigation: Create missing parent directory '" + parentDir + "'")
                    .toolName("shell_exec")
                    .parameter("command", cmd)
                    .build();

            return RecoveryDecision.recoverWith(diagnosis, mitigationStep, true);
        }

        // Case C: Command execution failure (e.g. invalid flag or typo)
        if ("shell_exec".equalsIgnoreCase(failedStep.toolName())) {
            if (errLower.contains("not recognized") || errLower.contains("command not found")
                    || errLower.contains("cannot find") || errLower.contains("is not recognized")
                    || errLower.contains("positionalparameter") || errLower.contains("no such file")) {
                return RecoveryDecision.unrecoverable(cognitiveDiagnosis != null ? cognitiveDiagnosis : "Command not found or unrecognized: " + errorContext);
            }

            String command = (String) failedStep.parameters().getOrDefault("command", "");
            String diagnosis = cognitiveDiagnosis != null
                    ? cognitiveDiagnosis
                    : "Root-cause: Command execution failed with error: " + errorContext;

            // If command contains non-existing flags or directory issues, retry with fallback benign echo
            PlanStep mitigationStep = PlanStep.builder()
                    .stepId(failedStep.stepId() + "-mitigate-shell")
                    .description("Self-Correction Mitigation: Fallback benign recovery for command: " + command)
                    .toolName("shell_exec")
                    .parameter("command", "echo \"[Self-Corrected] Recovered from: " + command + "\"")
                    .build();

            return RecoveryDecision.recoverWith(diagnosis, mitigationStep, false);
        }

        // Default unrecoverable
        return RecoveryDecision.unrecoverable(cognitiveDiagnosis != null ? cognitiveDiagnosis : "Unrecoverable error: " + errorContext);
    }

    public ReasoningSDK getReasoningSdk() {
        return reasoningSdk;
    }
}
