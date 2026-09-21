package com.sovereign;

import com.sovereign.core.client.SovereignClient;
import com.sovereign.core.security.CommandGuardrail;
import com.sovereign.core.security.WorkspaceBoundary;
import com.sovereign.core.tools.FileSystemTool;
import com.sovereign.core.tools.ShellExecutionTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>SovereignPhase1ToolingTest</b>
 *
 * <p>Phase 1 Verification Test Suite validating Host OS Tooling Engine, Security Guardrails,
 * and Shree AI OS Client Initialization.</p>
 */
public class SovereignPhase1ToolingTest {

    @Test
    @DisplayName("Test 1: Shell tool successfully runs a benign command (echo) and returns exitCode 0")
    void testBenignShellCommandExecution() {
        ShellExecutionTool shellTool = new ShellExecutionTool();
        ShellExecutionTool.ShellResult result = shellTool.execute("echo \"hello\"");

        assertThat(result.exitCode())
                .as("Exit code must be 0 for benign echo command")
                .isEqualTo(0);
        assertThat(result.isSuccess())
                .as("isSuccess() should be true")
                .isTrue();
        assertThat(result.timedOut())
                .as("Command must not time out")
                .isFalse();
        assertThat(result.stdout().trim())
                .as("Stdout must contain 'hello'")
                .contains("hello");
        assertThat(result.executionTimeMs())
                .as("Execution time must be recorded")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Test 2: Shell tool times out gracefully when command exceeds configured threshold")
    void testShellExecutionTimeout() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String sleepCommand = isWindows ? "Start-Sleep -Seconds 5" : "sleep 5";

        ShellExecutionTool shellTool = new ShellExecutionTool(Duration.ofMillis(500));
        ShellExecutionTool.ShellResult result = shellTool.execute(sleepCommand);

        assertThat(result.timedOut())
                .as("Tool should detect execution timeout")
                .isTrue();
        assertThat(result.isSuccess())
                .as("Success must be false on timeout")
                .isFalse();
        assertThat(result.exitCode())
                .as("Exit code should be -1 on timeout")
                .isEqualTo(-1);
        assertThat(result.stderr())
                .as("Stderr should report timeout error")
                .containsIgnoringCase("timed out");
    }

    @Test
    @DisplayName("Test 3: CommandGuardrail blocks catastrophic commands before OS process dispatch")
    void testCommandGuardrailBlocksCatastrophicCommands() {
        CommandGuardrail guardrail = new CommandGuardrail();
        ShellExecutionTool shellTool = new ShellExecutionTool(Duration.ofSeconds(10), guardrail, new com.sovereign.core.tools.ProcessControlTool());

        List<String> catastrophicCommands = List.of(
                "rm -rf /",
                "rm -rf /*",
                "format C:",
                ":(){ :|:& };:",
                "%0|%0",
                "shutdown /s",
                "reboot",
                "del /f /s /q C:\\*",
                "Remove-Item C:\\ -Recurse -Force",
                "mkfs.ext4 /dev/sda"
        );

        for (String catastrophicCmd : catastrophicCommands) {
            assertThat(CommandGuardrail.isAllowed(catastrophicCmd))
                    .as("Guardrail must reject: %s", catastrophicCmd)
                    .isFalse();

            assertThatThrownBy(() -> CommandGuardrail.validate(catastrophicCmd))
                    .as("validate() must throw SecurityException for: %s", catastrophicCmd)
                    .isInstanceOf(SecurityException.class);

            assertThatThrownBy(() -> shellTool.execute(catastrophicCmd))
                    .as("ShellExecutionTool must block before process dispatch for: %s", catastrophicCmd)
                    .isInstanceOf(SecurityException.class);
        }

        // Verify benign commands are explicitly allowed
        assertThat(CommandGuardrail.isAllowed("echo \"safe command\"")).isTrue();
        assertThat(CommandGuardrail.isAllowed("dir")).isTrue();
        assertThat(CommandGuardrail.isAllowed("Get-ChildItem")).isTrue();
    }

    @Test
    @DisplayName("Test 4: WorkspaceBoundary rejects path traversal attempts (../../etc/passwd or system roots)")
    void testWorkspaceBoundaryRejectsPathTraversal(@TempDir Path tempWorkspace) throws IOException {
        WorkspaceBoundary boundary = new WorkspaceBoundary(tempWorkspace);
        FileSystemTool fsTool = new FileSystemTool(boundary);

        List<String> traversalPaths = List.of(
                "../../etc/passwd",
                "..\\..\\Windows\\System32",
                "C:\\Windows\\System32",
                "/etc/passwd",
                "/root/.ssh/id_rsa",
                "subdir/../../../../escaped.txt"
        );

        for (String dangerousPath : traversalPaths) {
            assertThat(boundary.isWithinBoundary(dangerousPath))
                    .as("Boundary should not allow dangerous path: %s", dangerousPath)
                    .isFalse();

            assertThatThrownBy(() -> boundary.resolve(dangerousPath))
                    .as("resolve() must throw SecurityException for: %s", dangerousPath)
                    .isInstanceOf(SecurityException.class);

            assertThatThrownBy(() -> fsTool.readFile(dangerousPath))
                    .as("readFile() must throw SecurityException for: %s", dangerousPath)
                    .isInstanceOf(SecurityException.class);
        }

        // Verify valid file operations work within boundary
        Path written = fsTool.atomicWriteFile("notes.txt", "Sovereign Assistant Workspace Data");
        assertThat(written).exists();
        assertThat(fsTool.readFile("notes.txt")).isEqualTo("Sovereign Assistant Workspace Data");

        String checksum = fsTool.computeChecksum("notes.txt", "SHA-256");
        assertThat(checksum)
                .as("Checksum must be non-empty hex string")
                .isNotBlank()
                .hasSize(64);

        assertThat(fsTool.exists("notes.txt")).isTrue();
        assertThat(fsTool.walkDirectory(".", 2)).isNotEmpty();
    }

    @Test
    @DisplayName("Test 5: SovereignClient cleanly initializes Shree AI OS runtime with 0 errors")
    void testSovereignClientInitializesShreeAiOsRuntime() {
        try (SovereignClient client = SovereignClient.create()) {
            assertThat(client.isInitialized())
                    .as("Runtime must be initialized")
                    .isTrue();

            assertThat(client.isRunning())
                    .as("Runtime must be running")
                    .isTrue();

            assertThat(client.getPlatformRuntime())
                    .as("ShreePlatformRuntime bridge must be present")
                    .isNotNull();

            assertThat(client.getRuntimeService())
                    .as("DefaultRuntimeService must be wired")
                    .isNotNull();

            assertThat(client.getPlanningSdk())
                    .as("PlanningSDK facade must be bound")
                    .isNotNull();

            assertThat(client.getMemorySdk())
                    .as("MemorySDK facade must be bound")
                    .isNotNull();

            assertThat(client.getReasoningSdk())
                    .as("ReasoningSDK facade must be bound")
                    .isNotNull();

            assertThat(client.getDeveloperSdk())
                    .as("DeveloperSDK facade must be bound")
                    .isNotNull();

            assertThat(client.getShreeAI())
                    .as("ShreeAI instance must be bound")
                    .isNotNull();
        }
    }
}
