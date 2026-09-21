package com.sovereign.core.tools;

import com.sovereign.core.security.CommandGuardrail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * <b>ShellExecutionTool</b>
 *
 * <p>Deterministic host OS shell execution tool with strict security guardrail enforcement,
 * non-blocking real-time stdout/stderr capture, and configurable timeout protection.</p>
 */
public class ShellExecutionTool {

    public record ShellResult(
            int exitCode,
            String stdout,
            String stderr,
            long executionTimeMs,
            boolean timedOut
    ) {
        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }
    }

    private final Duration defaultTimeout;
    private final CommandGuardrail guardrail;
    private final ProcessControlTool processControlTool;

    public ShellExecutionTool() {
        this(Duration.ofSeconds(30), new CommandGuardrail(), new ProcessControlTool());
    }

    public ShellExecutionTool(Duration defaultTimeout) {
        this(defaultTimeout, new CommandGuardrail(), new ProcessControlTool());
    }

    public ShellExecutionTool(Duration defaultTimeout, CommandGuardrail guardrail, ProcessControlTool processControlTool) {
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "Default timeout must not be null");
        this.guardrail = Objects.requireNonNull(guardrail, "CommandGuardrail must not be null");
        this.processControlTool = Objects.requireNonNull(processControlTool, "ProcessControlTool must not be null");
    }

    /**
     * Executes a shell command using default timeout.
     */
    public ShellResult execute(String command) {
        return execute(command, defaultTimeout);
    }

    /**
     * Executes a shell command using a specific timeout.
     */
    public ShellResult execute(String command, Duration timeout) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Command must not be null or blank");
        }

        // 1. Guardrail validation before OS process dispatch
        guardrail.validateCommand(command);

        // 2. Build OS-specific command line
        List<String> commandList = buildShellCommandLine(command);

        ProcessBuilder processBuilder = new ProcessBuilder(commandList);
        long startTime = System.currentTimeMillis();

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return new ShellResult(-1, "", "Failed to start process: " + e.getMessage(), elapsed, false);
        }

        // Register process in session process tracker
        processControlTool.registerSessionProcess(process.pid());

        // 3. Asynchronous non-blocking stream capture
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));

        // 4. Await completion or timeout
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        long executionTimeMs = System.currentTimeMillis() - startTime;

        if (!finished) {
            // Graceful and complete process tree termination on timeout
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }

            String partialStdout = stdoutFuture.getNow("");
            String partialStderr = stderrFuture.getNow("");
            String timeoutMessage = (partialStderr.isEmpty() ? "" : partialStderr + System.lineSeparator())
                    + "Command timed out after " + timeout.toMillis() + "ms";

            return new ShellResult(-1, partialStdout, timeoutMessage, executionTimeMs, true);
        }

        int exitCode = process.exitValue();
        String stdout;
        String stderr;
        try {
            stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            stdout = stdoutFuture.getNow("");
        }
        try {
            stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            stderr = stderrFuture.getNow("");
        }

        return new ShellResult(exitCode, stdout, stderr, executionTimeMs, false);
    }

    private List<String> buildShellCommandLine(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> commandList = new ArrayList<>();
        if (isWindows) {
            commandList.add("powershell.exe");
            commandList.add("-NoProfile");
            commandList.add("-NonInteractive");
            commandList.add("-ExecutionPolicy");
            commandList.add("Bypass");
            commandList.add("-Command");
            commandList.add(command);
        } else {
            commandList.add("/bin/bash");
            commandList.add("-c");
            commandList.add(command);
        }
        return commandList;
    }

    private static String readStream(InputStream inputStream) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                output.append(buffer, 0, charsRead);
            }
        } catch (IOException ignored) {
        }
        return output.toString();
    }

    public CommandGuardrail getGuardrail() {
        return guardrail;
    }

    public ProcessControlTool getProcessControlTool() {
        return processControlTool;
    }
}
