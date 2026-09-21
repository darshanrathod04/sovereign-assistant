package com.sovereign.cli;

import com.sovereign.core.client.SovereignClient;
import com.sovereign.core.react.engine.AutonomousOperator;
import com.sovereign.core.react.model.OperatorEvent;
import com.sovereign.core.react.planner.GoalDecomposer;
import com.sovereign.core.react.recovery.CausalErrorRecoveryEngine;
import com.sovereign.core.tools.FileSystemTool;
import com.sovereign.core.tools.ProcessControlTool;
import com.sovereign.core.tools.ShellExecutionTool;

import java.util.Scanner;

/**
 * <b>SovereignReplRunner</b>
 *
 * <p>Interactive CLI skeleton, single-command runner, and autonomous ReAct operator
 * interface for Sovereign Assistant powered by Shree AI OS.</p>
 */
public class SovereignReplRunner {

    public static final String BANNER =
            """
            ============================================================
                   Sovereign OS Operator [Powered by Shree AI OS]
            ============================================================
            """;

    private final ShellExecutionTool shellTool;
    private final FileSystemTool fileSystemTool;
    private final ProcessControlTool processControlTool;
    private SovereignClient client;
    private AutonomousOperator autonomousOperator;

    public SovereignReplRunner() {
        this.shellTool = new ShellExecutionTool();
        this.fileSystemTool = new FileSystemTool();
        this.processControlTool = new ProcessControlTool();
    }

    public static void main(String[] args) {
        SovereignReplRunner runner = new SovereignReplRunner();
        int exitCode = runner.run(args);
        if (args.length > 0) {
            System.exit(exitCode);
        }
    }

    public int run(String[] args) {
        if (args != null && args.length > 0) {
            return handleArgs(args);
        } else {
            runInteractiveLoop();
            return 0;
        }
    }

    private int handleArgs(String[] args) {
        int startIndex = 0;
        if (args[0].equalsIgnoreCase("sovereign")) {
            startIndex = 1;
        }

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("exec")) {
            if (startIndex + 1 >= args.length) {
                System.err.println("Error: exec requires a command string argument. Usage: sovereign exec \"<command>\"");
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = startIndex + 1; i < args.length; i++) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(args[i]);
            }
            return executeCommand(sb.toString().trim());
        }

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("run")) {
            if (startIndex + 1 >= args.length) {
                System.err.println("Error: run requires a goal description string. Usage: sovereign run \"<goal>\"");
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = startIndex + 1; i < args.length; i++) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(args[i]);
            }
            return executeAutonomousGoal(sb.toString().trim());
        }

        if (startIndex < args.length && (args[startIndex].equalsIgnoreCase("repl") || args[startIndex].equalsIgnoreCase("interactive"))) {
            runInteractiveLoop();
            return 0;
        }

        System.out.println("Unknown arguments. Usage:");
        System.out.println("  sovereign run \"<goal>\"");
        System.out.println("  sovereign exec \"<command>\"");
        System.out.println("  sovereign repl");
        return 1;
    }

    public int executeCommand(String command) {
        try {
            ShellExecutionTool.ShellResult result = shellTool.execute(command);
            if (!result.stdout().isEmpty()) {
                System.out.print(result.stdout());
            }
            if (!result.stderr().isEmpty()) {
                System.err.print(result.stderr());
            }
            return result.exitCode();
        } catch (SecurityException se) {
            System.err.println("GUARDRAIL VIOLATION: " + se.getMessage());
            return 126;
        } catch (Exception e) {
            System.err.println("Execution error: " + e.getMessage());
            return 1;
        }
    }

    public int executeAutonomousGoal(String goal) {
        ensureOperator();
        System.out.println("\n>>> [SOVEREIGN OPERATOR] Autonomous ReAct Loop Initiated for Goal:");
        System.out.println("    \"" + goal + "\"\n");

        AutonomousOperator.OperatorResult result = autonomousOperator.execute(goal);
        return result.success() ? 0 : 1;
    }

    public void runInteractiveLoop() {
        System.out.println(BANNER);
        System.out.println("Type 'help' for available commands or 'exit' / 'quit' to close.\n");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("sovereign> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                System.out.println("Shutting down Sovereign Operator session. Goodbye.");
                break;
            } else if (line.equalsIgnoreCase("help")) {
                printHelp();
            } else if (line.startsWith("run ")) {
                String goal = line.substring(4).trim();
                executeAutonomousGoal(goal);
            } else if (line.startsWith("exec ")) {
                String cmd = line.substring(5).trim();
                executeCommand(cmd);
            } else if (line.startsWith("read ")) {
                handleRead(line.substring(5).trim());
            } else if (line.startsWith("write ")) {
                handleWrite(line.substring(6).trim());
            } else if (line.equalsIgnoreCase("processes")) {
                handleProcesses();
            } else if (line.equalsIgnoreCase("status")) {
                handleStatus();
            } else {
                System.out.println("Unrecognized command. Type 'help' for command list.");
            }
        }

        if (client != null) {
            client.shutdown();
        }
    }

    private synchronized void ensureOperator() {
        if (autonomousOperator == null) {
            if (client == null) {
                try {
                    client = SovereignClient.create();
                } catch (Exception e) {
                    System.err.println("Warning: SovereignClient init fallback: " + e.getMessage());
                }
            }
            GoalDecomposer decomposer = new GoalDecomposer(client != null ? client.getPlanningSdk() : null);
            CausalErrorRecoveryEngine recoveryEngine = new CausalErrorRecoveryEngine(client != null ? client.getReasoningSdk() : null);
            autonomousOperator = new AutonomousOperator(decomposer, recoveryEngine, shellTool, fileSystemTool, processControlTool);

            // Wire real-time streaming ReAct trace listener
            autonomousOperator.addListener(event -> {
                if (event instanceof OperatorEvent.StepStarted started) {
                    System.out.println("[THOUGHT] " + started.thought());
                    System.out.println("[ACTION: " + started.step().toolName() + "] " + started.step().description());
                } else if (event instanceof OperatorEvent.StepCompleted completed) {
                    if (completed.result().success()) {
                        System.out.println("[OBSERVATION] " + completed.result().observation());
                    } else {
                        System.err.println("[OBSERVATION (FAILED)] " + completed.result().observation() + " | " + completed.result().stderr());
                    }
                    System.out.println("[NEXT STEP]");
                } else if (event instanceof OperatorEvent.SelfCorrecting correcting) {
                    System.out.println("[SELF-CORRECTING] " + correcting.diagnosis());
                    System.out.println("[ACTION: " + correcting.mitigationStep().toolName() + "] " + correcting.mitigationStep().description());
                } else if (event instanceof OperatorEvent.GoalFinished finished) {
                    if (finished.success()) {
                        System.out.println("\n[GOAL SUCCESS] " + finished.summary() + "\n");
                    } else {
                        System.err.println("\n[GOAL FAILED] " + finished.summary() + "\n");
                    }
                }
            });
        }
    }

    private void handleRead(String path) {
        try {
            String content = fileSystemTool.readFile(path);
            System.out.println(content);
        } catch (Exception e) {
            System.err.println("Read error: " + e.getMessage());
        }
    }

    private void handleWrite(String rest) {
        int spaceIdx = rest.indexOf(' ');
        if (spaceIdx == -1) {
            System.err.println("Usage: write <path> <content>");
            return;
        }
        String path = rest.substring(0, spaceIdx).trim();
        String content = rest.substring(spaceIdx + 1);
        try {
            fileSystemTool.atomicWriteFile(path, content);
            System.out.println("Successfully written to " + path);
        } catch (Exception e) {
            System.err.println("Write error: " + e.getMessage());
        }
    }

    private void handleProcesses() {
        var processes = processControlTool.queryProcesses(null);
        System.out.println("Active processes count: " + processes.size());
        processes.stream().limit(15).forEach(p ->
                System.out.printf("  PID %d | %s | Session: %s%n", p.pid(), p.name(), p.isSessionProcess())
        );
    }

    private void handleStatus() {
        if (client == null) {
            try {
                client = SovereignClient.create();
            } catch (Exception e) {
                System.err.println("Failed to initialize SovereignClient: " + e.getMessage());
                return;
            }
        }
        System.out.println("Shree AI OS Runtime Status: " + client.getPlatformRuntime().getState());
        System.out.println("Is Initialized: " + client.isInitialized());
        System.out.println("Is Running: " + client.isRunning());
    }

    private void printHelp() {
        System.out.println("""
            Commands:
              run <goal>              Execute autonomous ReAct cognitive loop on goal
              exec <command>          Execute host OS shell command directly
              read <file>             Read file within workspace boundary
              write <file> <content>  Write file within workspace boundary
              processes               List top active processes
              status                  Check Shree AI OS runtime status
              help                    Show this help message
              exit / quit             Exit REPL
            """);
    }

    public ShellExecutionTool getShellTool() {
        return shellTool;
    }

    public FileSystemTool getFileSystemTool() {
        return fileSystemTool;
    }

    public ProcessControlTool getProcessControlTool() {
        return processControlTool;
    }

    public AutonomousOperator getAutonomousOperator() {
        ensureOperator();
        return autonomousOperator;
    }
}
