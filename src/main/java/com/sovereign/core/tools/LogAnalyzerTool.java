package com.sovereign.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>LogAnalyzerTool</b>
 *
 * <p>Automated diagnostic engine for build logs, compilation errors, stack traces,
 * and Maven/Gradle surefire reports. Pinpoints the root cause, exact file, line number,
 * and suggests targeted remediation steps.</p>
 */
public class LogAnalyzerTool {

    private static final Pattern JAVA_ERROR_PATTERN =
            Pattern.compile("\\[ERROR\\]\\s+([^:]+\\.java):\\[(\\d+),(\\d+)\\]\\s+(.+)");

    private static final Pattern STACK_TRACE_PATTERN =
            Pattern.compile("([a-zA-Z0-9_.]+(?:Exception|Error)):\\s*(.+)");

    private static final Pattern TEST_FAILURE_PATTERN =
            Pattern.compile("\\[ERROR\\]\\s+Failures:\\s*\\n\\[ERROR\\]\\s+([a-zA-Z0-9_.$]+):(\\d+)");

    public record LogAnalysis(
            boolean isFailure,
            String errorType,
            String sourceFile,
            int lineNumber,
            String summary,
            String suggestion
    ) {}

    public LogAnalysis analyzeLogFile(Path logPath) throws IOException {
        if (!Files.exists(logPath)) {
            return new LogAnalysis(false, "NONE", null, -1, "File not found: " + logPath, "Verify path.");
        }
        String text = Files.readString(logPath);
        return analyzeLogText(text);
    }

    public LogAnalysis analyzeLogText(String logText) {
        if (logText == null || logText.isBlank()) {
            return new LogAnalysis(false, "NONE", null, -1, "Empty log content.", "No errors detected.");
        }

        // 1. Check for Java compilation errors: [ERROR] Foo.java:[42,15] error description
        Matcher javaMatcher = JAVA_ERROR_PATTERN.matcher(logText);
        if (javaMatcher.find()) {
            String file = javaMatcher.group(1);
            int line = Integer.parseInt(javaMatcher.group(2));
            String desc = javaMatcher.group(4);
            String suggestion = generateCompileSuggestion(desc);
            return new LogAnalysis(true, "CompilationError", file, line, desc, suggestion);
        }

        // 2. Check for Test Failures
        Matcher testMatcher = TEST_FAILURE_PATTERN.matcher(logText);
        if (testMatcher.find()) {
            String testClass = testMatcher.group(1);
            int line = Integer.parseInt(testMatcher.group(2));
            return new LogAnalysis(true, "TestFailure", testClass, line,
                    "Assertion failure in " + testClass + " at line " + line,
                    "Review test expectations or mock behavior at line " + line + ".");
        }

        // 3. Check for Exceptions / Stack Traces
        Matcher stackMatcher = STACK_TRACE_PATTERN.matcher(logText);
        if (stackMatcher.find()) {
            String exc = stackMatcher.group(1);
            String msg = stackMatcher.group(2);
            return new LogAnalysis(true, exc, null, -1, exc + ": " + msg,
                    "Inspect the root cause of " + exc + " in recent stack trace frames.");
        }

        if (logText.contains("BUILD FAILURE")) {
            return new LogAnalysis(true, "BuildFailure", null, -1,
                    "Build execution failed without an explicit parsed stack trace.",
                    "Run with 'mvn test -X' or inspect recent dependencies.");
        }

        return new LogAnalysis(false, "NONE", null, -1, "No known errors or failures found in log.", "System operating normally.");
    }

    private static String generateCompileSuggestion(String errorDesc) {
        if (errorDesc == null) return "Check compiler syntax.";
        String lower = errorDesc.toLowerCase();
        if (lower.contains("cannot find symbol")) {
            return "Verify that the variable, class, or method is declared and imported correctly.";
        }
        if (lower.contains("incompatible types")) {
            return "Check return type or variable assignment types for casting discrepancies.";
        }
        if (lower.contains("package") && lower.contains("does not exist")) {
            return "Ensure the required dependency is added in pom.xml and imported in the class.";
        }
        return "Review the syntax near the reported line.";
    }
}
