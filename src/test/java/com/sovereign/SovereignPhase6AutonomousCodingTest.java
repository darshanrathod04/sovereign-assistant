package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.config.RateLimitGuard;
import com.sovereign.core.tools.CodeGenerationTool;
import com.sovereign.core.tools.TestGenerationTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <b>SovereignPhase6AutonomousCodingTest</b>
 *
 * <p>Unit and integration tests for Phase 6: Autonomous Coding Agent (Gemini free tier
 * with local Ollama fallback, RateLimitGuard 15 RPM protection, CodeGenerationTool,
 * and TestGenerationTool).</p>
 */
public class SovereignPhase6AutonomousCodingTest {

    @Test
    @DisplayName("Test 1: RateLimitGuard enforces 15 RPM rolling window and recommends Ollama fallback")
    void testRateLimitGuardSlidingWindow() {
        RateLimitGuard guard = RateLimitGuard.getInstance();
        guard.reset();

        assertThat(guard.canCallGemini()).isTrue();
        assertThat(guard.shouldFallbackToOllama()).isFalse();
        assertThat(guard.getCallsThisMinute()).isEqualTo(0);
        assertThat(guard.getRemainingCallsThisMinute()).isEqualTo(15);

        // Record 14 calls (hits safety threshold)
        for (int i = 0; i < 14; i++) {
            guard.recordGeminiCall();
        }
        assertThat(guard.getCallsThisMinute()).isEqualTo(14);
        assertThat(guard.getRemainingCallsThisMinute()).isEqualTo(1);
        assertThat(guard.shouldFallbackToOllama()).isTrue();
        assertThat(guard.canCallGemini()).isTrue();

        // 15th call exhausts RPM
        guard.recordGeminiCall();
        assertThat(guard.getCallsThisMinute()).isEqualTo(15);
        assertThat(guard.getRemainingCallsThisMinute()).isEqualTo(0);
        assertThat(guard.canCallGemini()).isFalse();

        guard.reset();
        assertThat(guard.getCallsThisMinute()).isEqualTo(0);
    }

    @Test
    @DisplayName("Test 2: CodeGenerationTool cleans code fences and deduces target paths")
    void testCodeDeductionAndCleaning() {
        String fencedCode = """
                ```java
                package com.sovereign.calc;

                public class BasicCalculator {
                    public int add(int a, int b) { return a + b; }
                }
                ```
                """;

        String cleaned = CodeGenerationTool.cleanCodeBlock(fencedCode);
        assertThat(cleaned).startsWith("package com.sovereign.calc;");
        assertThat(cleaned).endsWith("}");
        assertThat(cleaned).doesNotContain("```");

        Path path = CodeGenerationTool.deduceFilePath(cleaned, "src/main/java");
        assertThat(path.toString().replace('\\', '/')).isEqualTo("src/main/java/com/sovereign/calc/BasicCalculator.java");
    }

    @Test
    @DisplayName("Test 3: CodeGenerationTool writes valid Java class to target file")
    void testCodeGenerationExecution(@TempDir Path tempDir) {
        CodeGenerationTool tool = new CodeGenerationTool();
        Path targetFile = tempDir.resolve("Greeter.java");

        var result = tool.generate("Create a Greeter class with a greet method", targetFile);

        assertThat(result).isNotNull();
        assertThat(result.targetFile()).isEqualTo(targetFile);
        assertThat(Files.exists(targetFile)).isTrue();
        assertThat(result.code()).contains("class ");
        assertThat(result.formatSummary()).contains("SOVEREIGN AUTONOMOUS CODE GENERATION");
    }

    @Test
    @DisplayName("Test 4: TestGenerationTool creates JUnit 5 tests for existing Java class")
    void testTestGenerationExecution(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("MathUtils.java");
        Files.writeString(sourceFile, """
                package com.sovereign.util;

                public class MathUtils {
                    public static int square(int x) { return x * x; }
                }
                """);

        TestGenerationTool tool = new TestGenerationTool();
        Path targetTestFile = tempDir.resolve("MathUtilsTest.java");

        var result = tool.generateTests(sourceFile, targetTestFile);

        assertThat(result).isNotNull();
        assertThat(result.testFile()).isEqualTo(targetTestFile);
        assertThat(Files.exists(targetTestFile)).isTrue();
        assertThat(result.testCode()).contains("@Test");
        assertThat(result.formatSummary()).contains("SOVEREIGN AUTONOMOUS TEST GENERATION");
    }

    @Test
    @DisplayName("Test 5: SovereignReplRunner rate-limit and quota command renders free tier status")
    void testReplQuotaOutput() {
        ProviderConfig offlineConfig = ProviderConfig.of(null, null);
        SovereignReplRunner runner = new SovereignReplRunner(offlineConfig);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        try {
            System.setOut(new PrintStream(baos));
            assertThatCode(runner::printRateLimits).doesNotThrowAnyException();
            String output = baos.toString();
            assertThat(output).contains("SOVEREIGN ZERO-COST RATE LIMIT GUARD");
            assertThat(output).contains("Minute Quota (15 RPM)");
            assertThat(output).contains("Guaranteed 100% Free");
        } finally {
            System.setOut(origOut);
        }
    }
}
