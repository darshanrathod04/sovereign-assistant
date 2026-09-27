package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.daemon.ProactiveIntelligenceDaemon;
import com.sovereign.core.memory.CorrectionLedger;
import com.sovereign.core.sdk.ReasoningSDK;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <b>SovereignPhase7SelfLearningTest</b>
 *
 * <p>Unit and integration tests for Phase 7: Self-Learning (CorrectionLedger,
 * system prompt rule injection, and zero-cost ProactiveIntelligenceDaemon).</p>
 */
public class SovereignPhase7SelfLearningTest {

    @Test
    @DisplayName("Test 1: CorrectionLedger adds, formats, and persists rules across reloads")
    void testCorrectionLedgerPersistence(@TempDir Path tempDir) {
        Path rulesFile = tempDir.resolve("rules.json");
        CorrectionLedger ledger = new CorrectionLedger(rulesFile);

        assertThat(ledger.size()).isEqualTo(0);
        ledger.addRule("Always use JUnit 5", "manual");
        ledger.addRule("Prefer 4 spaces over tabs", "manual");
        ledger.addRule("Never use hardcoded secrets", "manual");

        assertThat(ledger.size()).isEqualTo(3);
        String promptSection = ledger.formatRulesForPrompt();
        assertThat(promptSection).contains("Always use JUnit 5");
        assertThat(promptSection).contains("Prefer 4 spaces over tabs");
        assertThat(promptSection).contains("Never use hardcoded secrets");

        // Reload from disk into a fresh instance
        CorrectionLedger reloaded = new CorrectionLedger(rulesFile);
        assertThat(reloaded.size()).isEqualTo(3);
        assertThat(reloaded.getRules().get(0).rule()).isEqualTo("Always use JUnit 5");
    }

    @Test
    @DisplayName("Test 2: CorrectionLedger detects and extracts rules from natural correction language")
    void testNaturalCorrectionExtraction(@TempDir Path tempDir) {
        Path rulesFile = tempDir.resolve("natural-rules.json");
        CorrectionLedger ledger = new CorrectionLedger(rulesFile);

        assertThat(ledger.learnFromCorrection("Don't use mutable collections in records")).isTrue();
        assertThat(ledger.learnFromCorrection("Always check for null before dereference")).isTrue();
        assertThat(ledger.learnFromCorrection("Prefer AssertJ over JUnit assertions")).isTrue();

        // Conversational chatter is NOT a rule
        assertThat(ledger.learnFromCorrection("How are you doing today?")).isFalse();
        assertThat(ledger.learnFromCorrection("What is the current time?")).isFalse();

        assertThat(ledger.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("Test 3: ReasoningSDK dynamically injects learned rules into JARVIS system prompt")
    void testReasoningSdkSystemPromptRuleInjection(@TempDir Path tempDir) {
        Path rulesFile = tempDir.resolve("system-rules.json");
        CorrectionLedger ledger = new CorrectionLedger(rulesFile);
        ledger.addRule("Always generate record instead of class when immutable", "manual");

        String systemPrompt = ReasoningSDK.buildSystemPrompt("Darshan", ledger);

        assertThat(systemPrompt).contains("The user's name is Darshan");
        assertThat(systemPrompt).contains("User-defined rules and behavioral preferences");
        assertThat(systemPrompt).contains("Always generate record instead of class when immutable");
    }

    @Test
    @DisplayName("Test 4: ProactiveIntelligenceDaemon inspects workspace and manages lifecycle safely")
    void testProactiveDaemonLifecycle(@TempDir Path tempDir) {
        ProactiveIntelligenceDaemon daemon = new ProactiveIntelligenceDaemon(null, tempDir, 500L);

        List<String> notices = daemon.inspectWorkspace();
        assertThat(notices).isNotNull();

        assertThat(daemon.isRunning()).isFalse();
        daemon.start();
        assertThat(daemon.isRunning()).isTrue();
        daemon.stop();
        assertThat(daemon.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Test 5: SovereignReplRunner renders Phase 7 rules and proactive health checks")
    void testReplRunnerPhase7Commands() {
        ProviderConfig offlineConfig = ProviderConfig.of(null, null);
        SovereignReplRunner runner = new SovereignReplRunner(offlineConfig);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        try {
            System.setOut(new PrintStream(baos));
            assertThatCode(runner::printRules).doesNotThrowAnyException();
            assertThatCode(runner::printProactiveNotices).doesNotThrowAnyException();

            String output = baos.toString();
            assertThat(output).contains("SOVEREIGN LEARNED RULES & PREFERENCES");
            assertThat(output).contains("SOVEREIGN PROACTIVE INTELLIGENCE AUDIT");
        } finally {
            System.setOut(origOut);
        }
    }
}
