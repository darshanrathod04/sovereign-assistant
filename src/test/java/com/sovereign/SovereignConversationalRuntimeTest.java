package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.intent.IntentClassificationResult;
import com.sovereign.core.intent.IntentRouter;
import com.sovereign.core.intent.UserIntentType;
import com.sovereign.core.memory.EpisodicSessionLedger;
import com.sovereign.core.react.engine.AutonomousOperator;
import com.sovereign.core.react.model.ExecutionPlan;
import com.sovereign.core.react.model.GoalStatus;
import com.sovereign.core.react.model.PlanStep;
import com.sovereign.core.react.planner.GoalDecomposer;
import com.sovereign.core.react.recovery.CausalErrorRecoveryEngine;
import com.sovereign.core.tools.FileSystemTool;
import com.sovereign.core.tools.ProcessControlTool;
import com.sovereign.core.tools.ShellExecutionTool;
import com.sovereign.core.workspace.WorkspaceContext;
import com.sovereign.core.workspace.WorkspaceContextIndexer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignConversationalRuntimeTest</b>
 *
 * <p>Comprehensive verification test suite asserting Intent Classification,
 * Conversational Runtime, Tool Parameter Normalization, Deterministic State Machine,
 * Episodic Memory write path, POM parsing, and Observation Summarization.</p>
 */
public class SovereignConversationalRuntimeTest {

    @Test
    @DisplayName("Test 1: Chat intent is classified correctly and does not trigger shell execution")
    void testChatIntentDoesNotTriggerShellExec() {
        IntentRouter router = new IntentRouter();

        IntentClassificationResult resultHello = router.classify("hello");
        assertThat(resultHello.intentType()).isEqualTo(UserIntentType.CHAT);

        IntentClassificationResult resultHi = router.classify("Hi! How are you?");
        assertThat(resultHi.intentType()).isEqualTo(UserIntentType.CHAT);

        IntentClassificationResult resultThanks = router.classify("Thank you");
        assertThat(resultThanks.intentType()).isEqualTo(UserIntentType.CHAT);

        SovereignReplRunner runner = new SovereignReplRunner();
        // natural input "hello" should execute without exception and should not execute any shell command
        runner.handleNaturalInput("hello");

        // Execute via executeAutonomousGoal("hello") should also catch CHAT and return 0 cleanly without shell errors
        int code = runner.executeAutonomousGoal("hello");
        assertThat(code).isEqualTo(0);
    }

    @Test
    @DisplayName("Test 2: Memory intent correctly stores and recalls user name and preferences")
    void testMemoryIntentStoresAndRecallsUserName() {
        IntentRouter router = new IntentRouter();
        IntentClassificationResult storeResult = router.classify("I am Darshan");
        assertThat(storeResult.intentType()).isEqualTo(UserIntentType.MEMORY);
        assertThat(storeResult.entities().get("operation")).isEqualTo("STORE_NAME");
        assertThat(storeResult.entities().get("userName")).isEqualTo("Darshan");

        IntentClassificationResult recallResult = router.classify("What is my name?");
        assertThat(recallResult.intentType()).isEqualTo(UserIntentType.MEMORY);
        assertThat(recallResult.entities().get("operation")).isEqualTo("RECALL_NAME");

        SovereignReplRunner runner = new SovereignReplRunner();
        runner.handleNaturalInput("I am Darshan");
        assertThat(runner.getUserProfile().getUserName()).isEqualTo("Darshan");

        // Verify editor preference
        runner.handleNaturalInput("Set editor VS Code");
        assertThat(runner.getUserProfile().getPreferredEditor()).isEqualTo("VS Code");
    }

    @Test
    @DisplayName("Test 3: Tool argument resolver strips conversational imperative phrases")
    void testToolArgumentResolverStripsImperativePhrases() {
        assertThat(IntentRouter.stripImperativePrefix("Run git status")).isEqualTo("git status");
        assertThat(IntentRouter.stripImperativePrefix("Execute mvn test")).isEqualTo("mvn test");
        assertThat(IntentRouter.stripImperativePrefix("Please run git status")).isEqualTo("git status");
        assertThat(IntentRouter.stripImperativePrefix("Can you run git diff")).isEqualTo("git diff");

        GoalDecomposer decomposer = new GoalDecomposer(null, null, null, null);
        ExecutionPlan gitPlan = decomposer.decompose("Run git status");
        assertThat(gitPlan.steps()).hasSize(1);
        PlanStep step = gitPlan.steps().get(0);
        assertThat(step.toolName()).isEqualTo("shell_exec");
        assertThat(step.parameters().get("command")).isEqualTo("git status");

        ExecutionPlan mvnPlan = decomposer.decompose("Execute mvn --version");
        assertThat(mvnPlan.steps().get(0).toolName()).isEqualTo("shell_exec");
        assertThat(mvnPlan.steps().get(0).parameters().get("command")).isEqualTo("mvn --version");
    }

    @Test
    @DisplayName("Test 4: Deterministic Goal State Machine distinguishes SUCCESS and FAILED without false successes")
    void testGoalStateMachineDistinguishesSuccessAndFailure() {
        GoalDecomposer decomposer = new GoalDecomposer(null, null, null, null);
        CausalErrorRecoveryEngine recovery = new CausalErrorRecoveryEngine();
        AutonomousOperator operator = new AutonomousOperator(
                decomposer,
                recovery,
                new ShellExecutionTool(),
                new FileSystemTool(),
                new ProcessControlTool()
        );

        // 1. Successful execution
        AutonomousOperator.OperatorResult successResult = operator.execute("echo sovereign_state_test");
        assertThat(successResult.success()).isTrue();
        assertThat(successResult.goalTask().status()).isEqualTo(GoalStatus.SUCCESS);

        // 2. Failing execution with non-zero exit code and no valid recovery
        AutonomousOperator.OperatorResult failResult = operator.execute("non_existent_command_xyz_123");
        assertThat(failResult.success()).isFalse();
        assertThat(failResult.goalTask().status()).isEqualTo(GoalStatus.FAILED);
    }

    @Test
    @DisplayName("Test 5: Episodic Memory write path stores executed goals with full schema")
    void testEpisodicMemoryRecordsExecutedGoals() {
        SovereignReplRunner runner = new SovereignReplRunner();
        int exitCode = runner.executeAutonomousGoal("echo episodic_ledger_verification");
        assertThat(exitCode).isEqualTo(0);

        List<EpisodicSessionLedger.EpisodicEntry> entries = runner.getEpisodicLedger().getAllEntries();
        assertThat(entries).isNotEmpty();

        EpisodicSessionLedger.EpisodicEntry latest = entries.get(entries.size() - 1);
        assertThat(latest.originalPrompt()).contains("episodic_ledger_verification");
        assertThat(latest.executionStatus()).isEqualTo(GoalStatus.SUCCESS);
        assertThat(latest.intent()).isEqualTo(UserIntentType.EXECUTE);
        assertThat(latest.confidence()).isGreaterThan(0.0);
        assertThat(latest.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("Test 6: WorkspaceContextIndexer extracts child artifactId and version from pom.xml, ignoring parent")
    void testWorkspaceContextIndexerExtractsChildArtifactId(@TempDir Path tempRepo) throws IOException {
        String pomWithParent = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>4.0.2</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>sample-child-app</artifactId>
                    <version>2.5.0-SNAPSHOT</version>
                </project>
                """;
        Files.writeString(tempRepo.resolve("pom.xml"), pomWithParent);

        WorkspaceContextIndexer indexer = new WorkspaceContextIndexer(tempRepo);
        WorkspaceContext context = indexer.getContext();

        assertThat(context.projectName()).isEqualTo("sample-child-app");
        assertThat(context.projectVersion()).isEqualTo("2.5.0-SNAPSHOT");
        assertThat(context.buildTool()).isEqualTo("Maven");
    }

    @Test
    @DisplayName("Test 7: Bulk directory walk observation is filtered for Java source directories")
    void testObservationConsumedByReasoning(@TempDir Path tempRepo) throws IOException {
        // Create mock source directories and other noise
        Files.createDirectories(tempRepo.resolve("src/main/java/com/app"));
        Files.createDirectories(tempRepo.resolve("src/test/java/com/app"));
        Files.createDirectories(tempRepo.resolve("docs/assets/images"));
        Files.createDirectories(tempRepo.resolve(".git/objects"));

        GoalDecomposer decomposer = new GoalDecomposer(null, new WorkspaceContextIndexer(tempRepo), null, null);
        CausalErrorRecoveryEngine recovery = new CausalErrorRecoveryEngine();
        AutonomousOperator operator = new AutonomousOperator(
                decomposer,
                recovery,
                new ShellExecutionTool(),
                new FileSystemTool(),
                new ProcessControlTool()
        );

        AutonomousOperator.OperatorResult result = operator.execute("Find all Java source directories");
        assertThat(result.success()).isTrue();
        assertThat(result.stepResults()).isNotEmpty();

        String stdout = result.stepResults().get(0).stdout();
        assertThat(stdout).contains("Identified Java source directories:");
        assertThat(stdout).contains("src/main/java");
        assertThat(stdout).contains("src/test/java");
        assertThat(stdout).doesNotContain("docs/assets");

        String observation = result.stepResults().get(0).observation();
        assertThat(observation).contains("Identified Java source directories:");
        assertThat(observation).contains("src/main/java");
    }

    @Test
    @DisplayName("Test 8: Queries beginning with Analyze, Inspect, Explain, Summarize, Find route to PROJECT intent")
    void testWorkspacePrefixQueriesRouteToProjectIntent() {
        IntentRouter router = new IntentRouter();

        assertThat(router.classify("Analyze this Maven workspace").intentType())
                .isEqualTo(UserIntentType.PROJECT);
        assertThat(router.classify("Inspect project structure").intentType())
                .isEqualTo(UserIntentType.PROJECT);
        assertThat(router.classify("Explain repository architecture").intentType())
                .isEqualTo(UserIntentType.PROJECT);
        assertThat(router.classify("Summarize codebase").intentType())
                .isEqualTo(UserIntentType.PROJECT);
        assertThat(router.classify("Find all Java source directories").intentType())
                .isEqualTo(UserIntentType.PROJECT);
        assertThat(router.classify("Please analyze project").intentType())
                .isEqualTo(UserIntentType.PROJECT);

        // Non-workspace queries must not match PROJECT
        assertThat(router.classify("Run git status").intentType())
                .isEqualTo(UserIntentType.EXECUTE);
        assertThat(router.classify("echo hello").intentType())
                .isEqualTo(UserIntentType.EXECUTE);
    }

    @Test
    @DisplayName("Test 9: Analyze this Maven workspace produces workspace summary without shell execution")
    void testAnalyzeMavenWorkspaceProducesSummaryWithoutShellExec(@TempDir Path tempRepo) throws IOException {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample</groupId>
                    <artifactId>demo-service</artifactId>
                    <version>1.0.0</version>
                </project>
                """;
        Files.writeString(tempRepo.resolve("pom.xml"), pom);
        Files.createDirectories(tempRepo.resolve("src/main/java"));

        WorkspaceContextIndexer indexer = new WorkspaceContextIndexer(tempRepo);
        GoalDecomposer decomposer = new GoalDecomposer(null, indexer, null, null);
        CausalErrorRecoveryEngine recovery = new CausalErrorRecoveryEngine();
        AutonomousOperator operator = new AutonomousOperator(
                decomposer,
                recovery,
                new ShellExecutionTool(),
                new FileSystemTool(),
                new ProcessControlTool()
        );

        AutonomousOperator.OperatorResult result = operator.execute("Analyze this Maven workspace");
        assertThat(result.success()).isTrue();
        assertThat(result.stepResults()).hasSize(1);
        PlanStep step = result.plan().steps().get(0);
        assertThat(step.toolName()).isEqualTo("project_analyze");

        String output = result.stepResults().get(0).stdout();
        assertThat(output).contains("Workspace Analysis: Project=demo-service");
        assertThat(output).contains("BuildTool=Maven");

        // SovereignReplRunner execution
        SovereignReplRunner runner = new SovereignReplRunner();
        int exitCode = runner.executeAutonomousGoal("Analyze this Maven workspace");
        assertThat(exitCode).isEqualTo(0);
    }
}
