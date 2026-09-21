package com.sovereign.cli;

import com.sovereign.core.client.SovereignClient;
import com.sovereign.core.daemon.WorkspaceAlert;
import com.sovereign.core.daemon.WorkspaceWatcherDaemon;
import com.sovereign.core.memory.EpisodicSessionLedger;
import com.sovereign.core.memory.ProceduralSkillStore;
import com.sovereign.core.memory.UserMemoryProfile;
import com.sovereign.core.react.engine.AutonomousOperator;
import com.sovereign.core.react.model.OperatorEvent;
import com.sovereign.core.react.planner.GoalDecomposer;
import com.sovereign.core.react.recovery.CausalErrorRecoveryEngine;
import com.sovereign.core.tools.FileSystemTool;
import com.sovereign.core.tools.ProcessControlTool;
import com.sovereign.core.tools.ShellExecutionTool;
import com.sovereign.core.voice.SpeechToTextAdapter;
import com.sovereign.core.voice.TextToSpeechSynthesizer;
import com.sovereign.core.voice.VoiceConfig;
import com.sovereign.core.workspace.WorkspaceContext;
import com.sovereign.core.workspace.WorkspaceContextIndexer;

import java.io.IOException;
import java.util.Scanner;

/**
 * <b>SovereignReplRunner</b>
 *
 * <p>Interactive CLI skeleton, single-command runner, ambient voice interface,
 * and autonomous ReAct operator interface for Sovereign Assistant powered by Shree AI OS.</p>
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

    private final UserMemoryProfile userProfile;
    private final ProceduralSkillStore skillStore;
    private final WorkspaceContextIndexer contextIndexer;
    private EpisodicSessionLedger episodicLedger;

    private final VoiceConfig voiceConfig;
    private final SpeechToTextAdapter sttAdapter;
    private final TextToSpeechSynthesizer ttsSynthesizer;
    private WorkspaceWatcherDaemon watcherDaemon;
    private boolean ambientVoiceEnabled = false;

    private SovereignClient client;
    private AutonomousOperator autonomousOperator;

    public SovereignReplRunner() {
        this.shellTool = new ShellExecutionTool();
        this.fileSystemTool = new FileSystemTool();
        this.processControlTool = new ProcessControlTool();
        this.userProfile = UserMemoryProfile.createDefault();
        this.skillStore = ProceduralSkillStore.createDefault();
        this.contextIndexer = new WorkspaceContextIndexer();

        this.voiceConfig = VoiceConfig.defaultConfig();
        this.sttAdapter = new SpeechToTextAdapter(voiceConfig);
        this.ttsSynthesizer = new TextToSpeechSynthesizer(voiceConfig);
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

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("speak")) {
            if (startIndex + 1 >= args.length) {
                System.err.println("Error: speak requires text. Usage: sovereign speak \"<text>\"");
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = startIndex + 1; i < args.length; i++) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(args[i]);
            }
            return handleSpeak(sb.toString().trim());
        }

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("listen")) {
            runAmbientVoiceLoop();
            return 0;
        }

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("watch")) {
            return runWatcherDaemon(true);
        }

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("memory")) {
            printMemory();
            return 0;
        }

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("context")) {
            printContext();
            return 0;
        }

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("learn")) {
            if (startIndex + 1 >= args.length) {
                System.err.println("Error: learn requires an assignment expression. Usage: sovereign learn <alias>=<command/goal>");
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = startIndex + 1; i < args.length; i++) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(args[i]);
            }
            handleLearn(sb.toString().trim());
            return 0;
        }

        if (startIndex < args.length && (args[startIndex].equalsIgnoreCase("repl") || args[startIndex].equalsIgnoreCase("interactive"))) {
            runInteractiveLoop();
            return 0;
        }

        System.out.println("Unknown arguments. Usage:");
        System.out.println("  sovereign run \"<goal>\"");
        System.out.println("  sovereign exec \"<command>\"");
        System.out.println("  sovereign speak \"<text>\"");
        System.out.println("  sovereign listen");
        System.out.println("  sovereign watch");
        System.out.println("  sovereign memory");
        System.out.println("  sovereign context");
        System.out.println("  sovereign learn <alias>=<goal>");
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

        long start = System.currentTimeMillis();
        AutonomousOperator.OperatorResult result = autonomousOperator.execute(goal);
        long elapsed = System.currentTimeMillis() - start;

        if (episodicLedger != null) {
            episodicLedger.recordGoal(result, elapsed);
        }

        if (ambientVoiceEnabled && ttsSynthesizer != null) {
            ttsSynthesizer.speak(result.summary());
        }

        return result.success() ? 0 : 1;
    }

    public int handleSpeak(String text) {
        boolean ok = ttsSynthesizer.speak(text);
        System.out.println("[SPOKEN] " + text);
        return ok ? 0 : 1;
    }

    public void runAmbientVoiceLoop() {
        System.out.println("\n>>> [SOVEREIGN AMBIENT] Voice Loop Active.");
        System.out.println("    Listening for wake-words ('Hey Sovereign', 'Jarvis')...");
        System.out.println("    Type prompt or speech transcript below (or 'exit' to return).\n");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("ambient-voice> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                System.out.println("Exiting ambient voice loop.");
                break;
            }
            if (line.isEmpty()) {
                continue;
            }

            String intent = line;
            if (sttAdapter.hasWakeWord(line)) {
                intent = sttAdapter.stripWakeWord(line);
                System.out.println("[WAKE-WORD DETECTED] Processing intent: " + intent);
            }

            if (intent.isEmpty()) {
                ttsSynthesizer.speak("I am listening. How can I assist you?");
                continue;
            }

            try {
                this.ambientVoiceEnabled = true;
                executeAutonomousGoal(intent);
            } finally {
                this.ambientVoiceEnabled = false;
            }
        }
    }

    public int runWatcherDaemon(boolean blocking) {
        ensureWatcher();
        System.out.println(">>> [SOVEREIGN WATCHER] Background workspace watcher active on: " + watcherDaemon.getRootPath());
        if (blocking) {
            System.out.println("Press Enter to stop monitoring...");
            new Scanner(System.in).nextLine();
            stopWatcher();
            System.out.println("Workspace monitoring stopped.");
        }
        return 0;
    }

    public synchronized void ensureWatcher() {
        if (watcherDaemon == null || !watcherDaemon.isRunning()) {
            WorkspaceContext ctx = contextIndexer.getContext();
            watcherDaemon = new WorkspaceWatcherDaemon(ctx.rootPath());
            watcherDaemon.addListener(alert -> {
                String prefix = alert.isErrorOrLog() ? "[WATCHER WARNING]" : "[WATCHER EVENT]";
                System.out.printf("%s %s%n", prefix, alert.description());
            });
            try {
                watcherDaemon.start();
            } catch (IOException e) {
                System.err.println("Failed to start workspace watcher daemon: " + e.getMessage());
            }
        }
    }

    public synchronized void stopWatcher() {
        if (watcherDaemon != null) {
            watcherDaemon.stop();
        }
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
            } else if (line.startsWith("speak ")) {
                handleSpeak(line.substring(6).trim());
            } else if (line.equalsIgnoreCase("listen")) {
                runAmbientVoiceLoop();
            } else if (line.equalsIgnoreCase("watch")) {
                handleWatchToggle();
            } else if (line.startsWith("read ")) {
                handleRead(line.substring(5).trim());
            } else if (line.startsWith("write ")) {
                handleWrite(line.substring(6).trim());
            } else if (line.equalsIgnoreCase("memory")) {
                printMemory();
            } else if (line.equalsIgnoreCase("context")) {
                printContext();
            } else if (line.startsWith("learn ")) {
                handleLearn(line.substring(6).trim());
            } else if (line.equalsIgnoreCase("processes")) {
                handleProcesses();
            } else if (line.equalsIgnoreCase("status")) {
                handleStatus();
            } else {
                System.out.println("Unrecognized command. Type 'help' for command list.");
            }
        }

        stopWatcher();
        if (client != null) {
            client.shutdown();
        }
    }

    private void handleWatchToggle() {
        if (watcherDaemon != null && watcherDaemon.isRunning()) {
            stopWatcher();
            System.out.println("Workspace watcher daemon stopped.");
        } else {
            ensureWatcher();
            System.out.println("Workspace watcher daemon started in background.");
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
            if (episodicLedger == null) {
                this.episodicLedger = new EpisodicSessionLedger(client != null ? client.getMemorySdk() : null);
            }
            GoalDecomposer decomposer = new GoalDecomposer(
                    client != null ? client.getPlanningSdk() : null,
                    contextIndexer,
                    userProfile,
                    skillStore
            );
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

    public void printMemory() {
        ensureOperator();
        System.out.println("=== USER MEMORY PROFILE ===");
        System.out.println("Preferred Shell  : " + userProfile.getPreferredShell());
        System.out.println("Preferred Editor : " + userProfile.getPreferredEditor());
        System.out.println("Custom Aliases   : " + userProfile.getCustomAliases());
        System.out.println("Favorite Projects: " + userProfile.getFavoriteProjects());
        System.out.println("\n=== EPISODIC GOAL HISTORY ===");
        var recent = episodicLedger.getRecentGoals(10);
        if (recent.isEmpty()) {
            System.out.println("No past goal executions recorded in episodic ledger.");
        } else {
            recent.forEach(e -> System.out.printf("  [%s] %s | Success: %s (%dms)%n",
                    e.goalId(), e.description(), e.success(), e.durationMs()));
        }
        System.out.println("\n=== PROCEDURAL SKILLS ===");
        skillStore.getAllSkills().forEach((name, def) ->
                System.out.printf("  Skill '%s' -> %s%n", name, def.recipe()));
    }

    public void printContext() {
        WorkspaceContext ctx = contextIndexer.getContext();
        System.out.println("=== WORKSPACE CONTEXT ===");
        System.out.println("Root Path       : " + ctx.rootPath());
        System.out.println("Project Name    : " + ctx.projectName());
        System.out.println("Project Version : " + ctx.projectVersion());
        System.out.println("Build Tool      : " + ctx.buildTool());
        System.out.println("Framework       : " + ctx.detectedFramework());
        System.out.println("Source Dirs     : " + ctx.sourceDirectories());
        System.out.println("Metadata        : " + ctx.metadata());
    }

    public void handleLearn(String learnArg) {
        if (!learnArg.contains("=")) {
            System.err.println("Usage: learn <alias>=<command/goal>");
            return;
        }
        int eq = learnArg.indexOf('=');
        String alias = learnArg.substring(0, eq).trim();
        String recipe = learnArg.substring(eq + 1).trim();

        skillStore.registerSkill(alias, recipe);
        userProfile.setAlias(alias, recipe);
        System.out.printf("Successfully learned skill: '%s' -> '%s'%n", alias, recipe);
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
              speak <text>            Synthesize and speak text response (TTS)
              listen                  Enter conversational ambient voice loop with wake-word
              watch                   Toggle background workspace watcher daemon
              read <file>             Read file within workspace boundary
              write <file> <content>  Write file within workspace boundary
              memory                  List recent episodic goals and active user profile
              context                 Display detected workspace information
              learn <alias>=<goal>    Save reusable procedural skill
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

    public UserMemoryProfile getUserProfile() {
        return userProfile;
    }

    public EpisodicSessionLedger getEpisodicLedger() {
        ensureOperator();
        return episodicLedger;
    }

    public ProceduralSkillStore getSkillStore() {
        return skillStore;
    }

    public WorkspaceContextIndexer getContextIndexer() {
        return contextIndexer;
    }

    public VoiceConfig getVoiceConfig() {
        return voiceConfig;
    }

    public SpeechToTextAdapter getSttAdapter() {
        return sttAdapter;
    }

    public TextToSpeechSynthesizer getTtsSynthesizer() {
        return ttsSynthesizer;
    }

    public WorkspaceWatcherDaemon getWatcherDaemon() {
        return watcherDaemon;
    }
}
