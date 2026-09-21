package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.client.SovereignClient;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.intent.IntentClassificationResult;
import com.sovereign.core.intent.IntentRouter;
import com.sovereign.core.intent.UserIntentType;
import com.sovereign.core.security.ReasoningOutputSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignProviderHardeningTest</b>
 *
 * <p>Validates production hardening for LLM provider chains, 3-tier key resolution,
 * masked credential security, output sanitization, and clean offline fallback.</p>
 */
public class SovereignProviderHardeningTest {

    @Test
    @DisplayName("Test 1: ProviderConfig without keys defaults directly to in-memory chain")
    void testProviderConfigWithoutKeysDefaultsToInMemory() {
        ProviderConfig config = ProviderConfig.of(null, null);

        assertThat(config.hasGeminiKey()).isFalse();
        assertThat(config.hasOpenAiKey()).isFalse();
        assertThat(config.hasAnyKey()).isFalse();
        assertThat(config.isOnline()).isFalse();
        assertThat(config.resolveActiveChain()).isEqualTo("in-memory");
        assertThat(config.getActiveProviderDisplayName()).contains("In-Memory");
        assertThat(config.getBanner()).contains("OFFLINE (Deterministic In-Memory Engine Active)");
        assertThat(config.resolvePrimaryApiKey()).isEqualTo("deterministic-fallback-key");
    }

    @Test
    @DisplayName("Test 2: ProviderConfig with Gemini key prioritizes Gemini Live in chain")
    void testProviderConfigWithGeminiKeyPrioritizesGemini() {
        ProviderConfig config = ProviderConfig.of("test-gemini-key-1234567890", null);

        assertThat(config.hasGeminiKey()).isTrue();
        assertThat(config.hasOpenAiKey()).isFalse();
        assertThat(config.isOnline()).isTrue();
        assertThat(config.resolveActiveChain()).isEqualTo("gemini,openai,in-memory");
        assertThat(config.getActiveProviderDisplayName()).isEqualTo("Gemini Live");
        assertThat(config.getBanner()).contains("ONLINE (Provider: Gemini Live)");
        assertThat(config.resolvePrimaryApiKey()).isEqualTo("test-gemini-key-1234567890");
    }

    @Test
    @DisplayName("Test 3: ProviderConfig with OpenAI key only configures openai chain")
    void testProviderConfigWithOpenAiKeyOnlyConfiguresOpenAi() {
        ProviderConfig config = ProviderConfig.of(null, "sk-proj-TestOpenAiKey1234567890");

        assertThat(config.hasGeminiKey()).isFalse();
        assertThat(config.hasOpenAiKey()).isTrue();
        assertThat(config.isOnline()).isTrue();
        assertThat(config.resolveActiveChain()).isEqualTo("openai,in-memory");
        assertThat(config.getActiveProviderDisplayName()).isEqualTo("OpenAI");
        assertThat(config.getBanner()).contains("ONLINE (Provider: OpenAI)");
        assertThat(config.resolvePrimaryApiKey()).isEqualTo("sk-proj-TestOpenAiKey1234567890");
    }

    @Test
    @DisplayName("Test 4: ProviderConfig with both keys prioritizes Gemini Live first")
    void testProviderConfigWithBothKeysPrioritizesGemini() {
        ProviderConfig config = ProviderConfig.of("test-gemini-key-12345", "sk-proj-OpenAi12345");

        assertThat(config.hasGeminiKey()).isTrue();
        assertThat(config.hasOpenAiKey()).isTrue();
        assertThat(config.resolveActiveChain()).isEqualTo("gemini,openai,in-memory");
        assertThat(config.getActiveProviderDisplayName()).isEqualTo("Gemini Live");
        assertThat(config.getBanner()).contains("Provider: Gemini Live");
    }

    @Test
    @DisplayName("Test 5: Mask key protects sensitive cloud credentials")
    void testMaskKeyProtectsSensitiveCredentials() {
        assertThat(ProviderConfig.maskKey("test-gemini-AbCdEf1234567890")).isEqualTo("test-g...****");
        assertThat(ProviderConfig.maskKey("sk-proj-1234567890ABCDEF")).isEqualTo("sk-pro...****");
        assertThat(ProviderConfig.maskKey("short123")).isEqualTo("****");
        assertThat(ProviderConfig.maskKey(null)).isEqualTo("[NOT DETECTED]");
        assertThat(ProviderConfig.maskKey("   ")).isEqualTo("[NOT DETECTED]");
    }

    @Test
    @DisplayName("Test 6: .env file parsing extracts credentials and handles comments/quotes")
    void testDotEnvFileParsing(@TempDir Path tempDir) throws IOException {
        String envContent = """
                # Sovereign Environment Configuration
                GEMINI_API_KEY="test-gemini-FromDotEnvFile12345"
                OPENAI_API_KEY='sk-proj-FromDotEnvFile67890'
                // Trailing comment
                IGNORED_KEY=dummy
                """;
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, envContent);

        ProviderConfig config = ProviderConfig.fromDotEnv(envFile);
        assertThat(config.hasGeminiKey()).isTrue();
        assertThat(config.hasOpenAiKey()).isTrue();
        assertThat(config.getGeminiApiKey()).isEqualTo("test-gemini-FromDotEnvFile12345");
        assertThat(config.getOpenAiApiKey()).isEqualTo("sk-proj-FromDotEnvFile67890");
        assertThat(config.getGeminiSource()).isEqualTo(ProviderConfig.CredentialSource.DOT_ENV);
        assertThat(config.getOpenAiSource()).isEqualTo(ProviderConfig.CredentialSource.DOT_ENV);
    }

    @Test
    @DisplayName("Test 7: ReasoningOutputSanitizer strips debug noise and synthesizes clean responses")
    void testReasoningOutputSanitizerFiltersNoiseAndEchoes() {
        // Line noise detection
        assertThat(ReasoningOutputSanitizer.isInternalNoise(">>> [LLM ROUTER] Invoking provider: openai")).isTrue();
        assertThat(ReasoningOutputSanitizer.isInternalNoise(">>> [SHREE RUNTIME] Active LLM Chain: in-memory")).isTrue();
        assertThat(ReasoningOutputSanitizer.isInternalNoise("[INIT] DefaultRuntimeService")).isTrue();
        assertThat(ReasoningOutputSanitizer.isInternalNoise("[START] DefaultRuntimeService")).isTrue();
        assertThat(ReasoningOutputSanitizer.isInternalNoise("[STOP] DefaultRuntimeService")).isTrue();
        assertThat(ReasoningOutputSanitizer.isInternalNoise("OpenAI request failed with HTTP 404")).isTrue();
        assertThat(ReasoningOutputSanitizer.isInternalNoise(">>> GEMINI HTTP ERROR CODE: 404")).isTrue();
        assertThat(ReasoningOutputSanitizer.isInternalNoise("Hello Darshan! Sovereign online.")).isFalse();

        // Prefix stripping
        String stripped = ReasoningOutputSanitizer.sanitize("default: Hello world", "hi", null);
        assertThat(stripped).isEqualTo("Hello world");

        String strippedInMemory = ReasoningOutputSanitizer.sanitize("in-memory: Greetings user", "hi", null);
        assertThat(strippedInMemory).isEqualTo("Greetings user");

        // Echo synthesis
        String rawEcho = """
                [SYSTEM]
                You are Sovereign...
                [CONTEXT]
                User: Darshan
                [USER]
                Who are you, Sovereign?
                """;
        String synthesized = ReasoningOutputSanitizer.sanitize(rawEcho, "Who are you, Sovereign?", null);
        assertThat(synthesized).contains("Sovereign");
        assertThat(synthesized).doesNotContain("[SYSTEM]");
        assertThat(synthesized).doesNotContain("[USER]");
    }

    @Test
    @DisplayName("Test 8: SovereignClient boots cleanly in offline in-memory mode without HTTP dumps")
    void testSovereignClientWithInMemoryProviderProducesNoHttpDumps() {
        ProviderConfig config = ProviderConfig.of(null, null);

        try (SovereignClient client = SovereignClient.create(config)) {
            assertThat(client.isInitialized()).isTrue();
            assertThat(client.isRunning()).isTrue();
            assertThat(client.getProviderConfig().isOnline()).isFalse();

            String response = client.reasoning().analyze("What is your purpose, Sovereign?");
            assertThat(response).isNotNull().isNotEmpty();
            assertThat(response).contains("Sovereign");
        }
    }

    @Test
    @DisplayName("Test 9: REPL and CLI keys command execution produces status telemetry")
    void testReplKeysCommandExecution() {
        IntentRouter router = new IntentRouter();

        IntentClassificationResult result1 = router.classify("keys");
        assertThat(result1.intentType()).isEqualTo(UserIntentType.SYSTEM);
        assertThat(result1.entities().get("operation")).isEqualTo("KEYS");

        IntentClassificationResult result2 = router.classify("sovereign keys");
        assertThat(result2.intentType()).isEqualTo(UserIntentType.SYSTEM);
        assertThat(result2.entities().get("operation")).isEqualTo("KEYS");

        IntentClassificationResult result3 = router.classify("show keys");
        assertThat(result3.intentType()).isEqualTo(UserIntentType.SYSTEM);
        assertThat(result3.entities().get("operation")).isEqualTo("KEYS");

        ProviderConfig config = ProviderConfig.of("test-gemini-SampleKey12345", "sk-proj-SampleKey12345");
        SovereignReplRunner runner = new SovereignReplRunner(config);

        int exitCode = runner.run(new String[]{"keys"});
        assertThat(exitCode).isEqualTo(0);

        // Natural input handling for keys
        runner.handleNaturalInput("keys");
        assertThat(runner.getProviderConfig().hasGeminiKey()).isTrue();
    }
}
