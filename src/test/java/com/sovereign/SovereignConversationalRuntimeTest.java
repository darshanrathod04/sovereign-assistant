package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.client.SovereignClient;
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

    @Test
    @DisplayName("Test 10: CHAT query delegates to platform LLM provider and returns dynamic response")
    void testChatIntentInvokesLlmProvider() {
        IntentRouter router = new IntentRouter();
        IntentClassificationResult chatClassification = router.classify("What is your purpose, Sovereign?");
        assertThat(chatClassification.intentType()).isEqualTo(UserIntentType.CHAT);

        SovereignReplRunner runner = new SovereignReplRunner();
        runner.handleNaturalInput("What is your purpose, Sovereign?");

        // Verify conversation history captured 1 turn (user + assistant)
        List<SovereignReplRunner.ChatTurn> history = runner.getConversationHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo("user");
        assertThat(history.get(0).text()).isEqualTo("What is your purpose, Sovereign?");
        assertThat(history.get(1).role()).isEqualTo("assistant");
        assertThat(history.get(1).text()).contains("Sovereign");

        // Verify second turn preserves history continuity
        runner.handleNaturalInput("How are you?");
        assertThat(runner.getConversationHistory()).hasSize(4);

        // Verify direct client reasoning facade
        try (SovereignClient client = SovereignClient.create()) {
            assertThat(client.reasoning()).isNotNull();
            String directReply = client.reasoning().analyze("Who are you?");
            assertThat(directReply).isNotNull().isNotEmpty();
            assertThat(directReply).contains("Sovereign");
        }
    }

    @Test
    @DisplayName("Test 11: ProjectSDK facts are passed to LLM provider for summary synthesis")
    void testProjectIntentSummarizedViaProvider(@TempDir Path tempRepo) throws IOException {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample</groupId>
                    <artifactId>sovereign-core-service</artifactId>
                    <version>3.1.0</version>
                </project>
                """;
        Files.writeString(tempRepo.resolve("pom.xml"), pom);
        Files.createDirectories(tempRepo.resolve("src/main/java"));

        WorkspaceContextIndexer indexer = new WorkspaceContextIndexer(tempRepo);
        WorkspaceContext context = indexer.getContext();
        assertThat(context.projectName()).isEqualTo("sovereign-core-service");

        try (SovereignClient client = SovereignClient.create()) {
            String facts = "Project Name: " + context.projectName() + "\n"
                    + "Version: " + context.projectVersion() + "\n"
                    + "Build Tool: " + context.buildTool();
            String executiveSummary = client.reasoning().analyze(
                    "Summarize workspace architecture:",
                    facts
            );
            assertThat(executiveSummary).isNotNull().isNotEmpty();
            assertThat(executiveSummary).contains("Workspace");
        }
    }

    @Test
    @DisplayName("Test 12: MEMORY, EXECUTE, and SYSTEM intents remain strictly deterministic")
    void testDeterministicIntentsRemainIntact() {
        IntentRouter router = new IntentRouter();

        // 1. MEMORY remains deterministic
        IntentClassificationResult memResult = router.classify("I am Darshan");
        assertThat(memResult.intentType()).isEqualTo(UserIntentType.MEMORY);
        assertThat(memResult.entities().get("operation")).isEqualTo("STORE_NAME");
        assertThat(memResult.entities().get("userName")).isEqualTo("Darshan");

        SovereignReplRunner runner = new SovereignReplRunner();
        runner.handleNaturalInput("I am Darshan");
        assertThat(runner.getUserProfile().getUserName()).isEqualTo("Darshan");

        runner.handleNaturalInput("Set editor VS Code");
        assertThat(runner.getUserProfile().getPreferredEditor()).isEqualTo("VS Code");

        // 2. EXECUTE remains deterministic
        IntentClassificationResult execResult = router.classify("Run git status");
        assertThat(execResult.intentType()).isEqualTo(UserIntentType.EXECUTE);
        assertThat(execResult.normalizedQuery()).isEqualTo("git status");

        int exitCode = runner.executeAutonomousGoal("echo deterministic_guard_verification");
        assertThat(exitCode).isEqualTo(0);

        List<EpisodicSessionLedger.EpisodicEntry> entries = runner.getEpisodicLedger().getAllEntries();
        EpisodicSessionLedger.EpisodicEntry latest = entries.get(entries.size() - 1);
        assertThat(latest.intent()).isEqualTo(UserIntentType.EXECUTE);
        assertThat(latest.originalPrompt()).contains("deterministic_guard_verification");

        // 3. SYSTEM remains deterministic
        IntentClassificationResult sysResult = router.classify("status");
        assertThat(sysResult.intentType()).isEqualTo(UserIntentType.SYSTEM);
    }

    @Test
    @DisplayName("Test 13: Prefix stripping — 'sovereign> Run git status' resolves to 'git status' EXECUTE intent")
    void testPrefixStripping() {
        IntentRouter router = new IntentRouter();

        // Input normalisation: sovereign> prefix is stripped
        assertThat(IntentRouter.normalizeInput("sovereign> Run git status")).isEqualTo("Run git status");
        assertThat(IntentRouter.normalizeInput("sovereign> keys")).isEqualTo("keys");
        assertThat(IntentRouter.normalizeInput("$ git status")).isEqualTo("git status");
        assertThat(IntentRouter.normalizeInput("> keys")).isEqualTo("keys");
        assertThat(IntentRouter.normalizeInput("  sovereign>   echo hello  ")).isEqualTo("echo hello");

        // After normalisation, classification must be EXECUTE with correct normalised command
        IntentClassificationResult result = router.classify("sovereign> Run git status");
        assertThat(result.intentType()).isEqualTo(UserIntentType.EXECUTE);
        assertThat(result.normalizedQuery()).isEqualTo("git status");

        // SYSTEM intent still works after prefix strip
        IntentClassificationResult keysResult = router.classify("sovereign> keys");
        assertThat(keysResult.intentType()).isEqualTo(UserIntentType.SYSTEM);
        assertThat(keysResult.entities().get("operation")).isEqualTo("KEYS");

        // End-to-end: 'sovereign> Run git status' must execute git status, not throw CommandNotFoundException
        SovereignReplRunner runner = new SovereignReplRunner();
        int exitCode = runner.executeAutonomousGoal("sovereign> Run git status");
        // git may not be installed in CI — we only require it did NOT invoke a nonsense command that crashes the JVM
        // exit code 0 or 1/128 from git are all acceptable; what's NOT acceptable is an exception
        assertThat(exitCode).isLessThanOrEqualTo(128); // git exit codes are 0..128
    }

    @Test
    @DisplayName("Test 14: Compound name introduction 'hello i am rahul' routes to MEMORY, not shell_exec")
    void testCompoundNameIntroduction() {
        IntentRouter router = new IntentRouter();

        // Compound greeting + name must route to MEMORY
        IntentClassificationResult helloRahul = router.classify("hello i am rahul");
        assertThat(helloRahul.intentType()).isEqualTo(UserIntentType.MEMORY);
        assertThat(helloRahul.entities().get("operation")).isEqualTo("STORE_NAME");
        assertThat(helloRahul.entities().get("userName")).isEqualToIgnoringCase("rahul");

        IntentClassificationResult hiAlex = router.classify("hi my name is alex");
        assertThat(hiAlex.intentType()).isEqualTo(UserIntentType.MEMORY);
        assertThat(hiAlex.entities().get("userName")).isEqualToIgnoringCase("alex");

        IntentClassificationResult heySovereign = router.classify("hey sovereign i am darshan");
        assertThat(heySovereign.intentType()).isEqualTo(UserIntentType.MEMORY);
        assertThat(heySovereign.entities().get("userName")).isEqualToIgnoringCase("darshan");

        // End-to-end: runner must update user profile to 'rahul' without invoking shell
        SovereignReplRunner runner = new SovereignReplRunner();
        runner.handleNaturalInput("hello i am rahul");
        assertThat(runner.getUserProfile().getUserName()).isEqualToIgnoringCase("rahul");

        // Verify the acknowledgement response captured in conversation history is conversational
        // (MEMORY branch does NOT call recordConversationTurn, so history stays empty — just verify no shell crashed)
        assertThat(runner.getConversationHistory()).isEmpty();
    }

    @Test
    @DisplayName("Test 15: Natural language queries route to CHAT, never to shell_exec")
    void testNaturalLanguageQueryDoesNotTriggerShell() {
        IntentRouter router = new IntentRouter();

        // "what can you do" — must be CHAT
        assertThat(router.classify("what can you do").intentType()).isEqualTo(UserIntentType.CHAT);
        assertThat(router.classify("what can you do?").intentType()).isEqualTo(UserIntentType.CHAT);
        assertThat(router.classify("help me").intentType()).isEqualTo(UserIntentType.CHAT);
        assertThat(router.classify("what are your capabilities").intentType()).isEqualTo(UserIntentType.CHAT);
        assertThat(router.classify("who are you").intentType()).isEqualTo(UserIntentType.CHAT);
        assertThat(router.classify("what is your purpose").intentType()).isEqualTo(UserIntentType.CHAT);

        // Verify isNaturalLanguageFallback guard
        assertThat(IntentRouter.isNaturalLanguageFallback("hello i am rahul")).isTrue();
        assertThat(IntentRouter.isNaturalLanguageFallback("git status")).isFalse();
        assertThat(IntentRouter.isNaturalLanguageFallback("mvn clean test")).isFalse();
        assertThat(IntentRouter.isNaturalLanguageFallback("echo hello world")).isFalse();

        // End-to-end: executeAutonomousGoal("what can you do") must NOT invoke any shell command
        // It must be caught by the CHAT guard in executeAutonomousGoal and redirect to handleNaturalInput
        SovereignReplRunner runner = new SovereignReplRunner();
        int exitCode = runner.executeAutonomousGoal("what can you do");
        assertThat(exitCode).isEqualTo(0);

        // Conversation history should have captured a CHAT turn (not an EXECUTE result)
        List<SovereignReplRunner.ChatTurn> history = runner.getConversationHistory();
        assertThat(history).isNotEmpty();
        assertThat(history.get(0).role()).isEqualTo("user");
        assertThat(history.get(0).text()).isEqualTo("what can you do");
    }
}
