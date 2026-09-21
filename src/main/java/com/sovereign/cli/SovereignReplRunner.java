package com.sovereign.cli;

import com.sovereign.core.client.SovereignClient;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.daemon.WorkspaceAlert;
import com.sovereign.core.daemon.WorkspaceWatcherDaemon;
import com.sovereign.core.intent.IntentClassificationResult;
import com.sovereign.core.intent.IntentRouter;
import com.sovereign.core.intent.UserIntentType;
import com.sovereign.core.memory.EpisodicSessionLedger;
import com.sovereign.core.memory.ProceduralSkillStore;
import com.sovereign.core.memory.UserMemoryProfile;
import com.sovereign.core.react.engine.AutonomousOperator;
import com.sovereign.core.react.model.GoalStatus;
import com.sovereign.core.react.model.OperatorEvent;
import com.sovereign.core.react.planner.GoalDecomposer;
import com.sovereign.core.react.recovery.CausalErrorRecoveryEngine;
import com.sovereign.core.tools.FileSystemTool;
import com.sovereign.core.tools.ProcessControlTool;
import com.sovereign.core.tools.ShellExecutionTool;
import com.sovereign.core.voice.MicrophoneAudioCapture;
import com.sovereign.core.voice.SpeechToTextAdapter;
import com.sovereign.core.voice.TextToSpeechSynthesizer;
import com.sovereign.core.voice.VoiceConfig;
import com.sovereign.core.workspace.WorkspaceContext;
import com.sovereign.core.workspace.WorkspaceContextIndexer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

/**
 * <b>SovereignReplRunner</b>
 *
 * <p>Interactive CLI skeleton, single-command runner, ambient voice interface,
 * intent-routed conversational loop, and autonomous ReAct operator interface
 * for Sovereign Assistant powered by Shree AI OS.</p>
 */
public class SovereignReplRunner {

    public record ChatTurn(String role, String text) {}

    public static final String BANNER =
            """
            ============================================================
                     SOVEREIGN ASSISTANT - AI SYSTEM OPERATOR
                             Powered by Shree AI OS
            ============================================================
            """;

    private final ShellExecutionTool shellTool;
    private final FileSystemTool fileSystemTool;
    private final ProcessControlTool processControlTool;

    private final UserMemoryProfile userProfile;
    private final ProceduralSkillStore skillStore;
    private final WorkspaceContextIndexer contextIndexer;
    private final IntentRouter intentRouter;
    private final ProviderConfig providerConfig;
    private EpisodicSessionLedger episodicLedger;

    private final VoiceConfig voiceConfig;
    private final SpeechToTextAdapter sttAdapter;
    private final TextToSpeechSynthesizer ttsSynthesizer;
    private WorkspaceWatcherDaemon watcherDaemon;
    private boolean ambientVoiceEnabled = false;

    private final Instant sessionStartTime = Instant.now();
    private SovereignClient client;
    private AutonomousOperator autonomousOperator;
    private final List<ChatTurn> conversationHistory = new ArrayList<>();

    public SovereignReplRunner() {
        this(ProviderConfig.load());
    }

    public SovereignReplRunner(ProviderConfig providerConfig) {
        this.shellTool = new ShellExecutionTool();
        this.fileSystemTool = new FileSystemTool();
        this.processControlTool = new ProcessControlTool();
        this.userProfile = UserMemoryProfile.createDefault();
        this.skillStore = ProceduralSkillStore.createDefault();
        this.contextIndexer = new WorkspaceContextIndexer();
        this.intentRouter = new IntentRouter();
        this.providerConfig = providerConfig != null ? providerConfig : ProviderConfig.load();

        this.voiceConfig = VoiceConfig.defaultConfig();
        this.sttAdapter = new SpeechToTextAdapter(voiceConfig);
        this.ttsSynthesizer = new TextToSpeechSynthesizer(voiceConfig);

        // Initialize runtime client singleton once at startup
        ensureClient();
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

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("status")) {
            handleStatus();
            return 0;
        }

        if (startIndex < args.length && args[startIndex].equalsIgnoreCase("keys")) {
            printKeys();
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

        // Natural argument pass-through (e.g. sovereign "hello", sovereign "I am Darshan")
        if (startIndex < args.length) {
            StringBuilder sb = new StringBuilder();
            for (int i = startIndex; i < args.length; i++) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(args[i]);
            }
            handleNaturalInput(sb.toString().trim());
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
        System.out.println("  sovereign status");
        System.out.println("  sovereign keys");
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

        // Check intent: Prevent CHAT, MEMORY, and non-tool PROJECT queries from reaching shell_exec
        IntentClassificationResult classification = intentRouter.classify(goal);
        if (classification.intentType() == UserIntentType.CHAT
                || classification.intentType() == UserIntentType.MEMORY
                || (classification.intentType() == UserIntentType.PROJECT && !isToolExecutionProjectQuery(goal))) {
            handleNaturalInput(goal);
            return 0;
        }

        String normalizedGoal = classification.normalizedQuery().isEmpty() ? goal : classification.normalizedQuery();

        System.out.println("\n>>> [SOVEREIGN OPERATOR] Autonomous ReAct Loop Initiated for Goal:");
        System.out.println("    \"" + normalizedGoal + "\"\n");

        long start = System.currentTimeMillis();
        AutonomousOperator.OperatorResult result = autonomousOperator.execute(normalizedGoal);
        long elapsed = System.currentTimeMillis() - start;

        if (episodicLedger != null) {
            episodicLedger.recordGoal(result, classification.intentType(), classification.confidence(), elapsed);
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

        MicrophoneAudioCapture mic = new MicrophoneAudioCapture();

        if (mic.isAvailable()) {
            // ── Physical microphone path ──────────────────────────────────────
            System.out.println("    [MIC] Hardware microphone detected — live audio capture enabled.");
            System.out.println("    Speak clearly. Say 'exit' or 'quit' to return.\n");
            runMicVoiceLoop(mic);
        } else {
            // ── Headless / no-mic fallback ────────────────────────────────────
            System.out.println("    [MIC] No hardware microphone available — console transcript mode.");
            System.out.println("    Type prompt or speech transcript below (or 'exit' to return).\n");
            runConsoleVoiceLoop();
        }
    }

    /**
     * Live ambient loop using physical microphone capture + VAD.
     * Records → transcribes → filters silence → routes → speaks in a continuous loop.
     */
    private void runMicVoiceLoop(MicrophoneAudioCapture mic) {
        while (true) {
            System.out.print("\n[SOVEREIGN AMBIENT] Listening... (speak now, or Ctrl+C to stop)\n");
            try {
                java.io.InputStream wavStream = mic.captureUtteranceAsWav();
                byte[] wavBytes = wavStream.readAllBytes();
                if (wavBytes.length == 0) {
                    System.out.println("[MIC] No audio detected — still listening.");
                    continue;
                }

                String transcript = sttAdapter.transcribe(wavBytes);

                // Filter silence / unintelligible audio — do NOT call LLM
                if (transcript == null || transcript.isBlank()
                        || com.sovereign.core.voice.AudioTranscriptionService.SILENCE_MARKER.equals(transcript)) {
                    System.out.println("[MIC SILENCE] No speech detected — still listening.");
                    continue;
                }

                // Display real user speech
                System.out.println("[USER VOICE] \"" + transcript + "\"");

                // Exit command detection
                String lower = transcript.strip().toLowerCase(java.util.Locale.ROOT);
                if (lower.equals("exit") || lower.equals("quit") || lower.equals("stop")) {
                    System.out.println("Exiting ambient voice loop.");
                    break;
                }

                // Strip wake-word prefix if present
                String intent = transcript;
                if (sttAdapter.hasWakeWord(transcript)) {
                    intent = sttAdapter.stripWakeWord(transcript);
                    System.out.println("[WAKE-WORD] Processing intent: " + intent);
                }

                if (intent == null || intent.isBlank()) {
                    ttsSynthesizer.speak("I am listening. How can I assist you?");
                    continue;
                }

                // Route through IntentRouter → LLM/action → speak response back
                try {
                    this.ambientVoiceEnabled = true;
                    handleNaturalInput(intent);
                } finally {
                    this.ambientVoiceEnabled = false;
                }

            } catch (java.io.IOException e) {
                System.out.println("[MIC] Audio capture error: " + e.getMessage() + " — retrying.");
            }
        }
    }

    /**
     * Console-based fallback voice loop for headless / no-mic environments.
     * Accepts typed transcript lines and routes them through the same intent pipeline.
     */
    private void runConsoleVoiceLoop() {
        java.util.Scanner scanner = new java.util.Scanner(System.in);
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
                handleNaturalInput(intent);
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
            } else if (line.equalsIgnoreCase("speak")) {
                System.out.println("Usage: speak <text to speak>");
                System.out.println("Example: speak Hello, I am Sovereign.");
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
            } else if (line.equalsIgnoreCase("keys") || line.equalsIgnoreCase("sovereign keys")) {
                printKeys();
            } else {
                // Conversational natural prompt evaluation
                handleNaturalInput(line);
            }
        }

        stopWatcher();
        if (client != null) {
            client.shutdown();
        }
    }

    /**
     * Evaluates and routes natural conversational prompts across CHAT, MEMORY, PROJECT, SYSTEM, and EXECUTE.
     */
    public void handleNaturalInput(String input) {
        ensureOperator();
        // Strip any copy-paste prompt-prefix noise (sovereign>, $, >) before routing
        String normalized = IntentRouter.normalizeInput(input);
        IntentClassificationResult classification = intentRouter.classify(normalized);

        switch (classification.intentType()) {
            case CHAT -> {
                ensureClient();
                String grounding = buildChatGroundingContext();
                String reply = null;
                if (client != null && client.reasoning() != null) {
                    try {
                        reply = client.reasoning().analyze(normalized, grounding);
                    } catch (Exception ignored) {
                    }
                }
                if (reply == null || reply.isBlank()) {
                    String userName = userProfile.getUserName();
                    reply = userName != null && !userName.isBlank()
                            ? "Hello " + userName + "! Sovereign online and ready to assist."
                            : "Hello! I am Sovereign Operator, powered by Shree AI OS. How can I help you today?";
                }
                System.out.println(reply);
                if (ambientVoiceEnabled && ttsSynthesizer != null) {
                    ttsSynthesizer.speak(reply);
                }
                recordConversationTurn(normalized, reply);
                if (episodicLedger != null) {
                    episodicLedger.recordGoal(
                            UUID.randomUUID().toString(),
                            UserIntentType.CHAT,
                            normalized,
                            "Conversational LLM interaction",
                            GoalStatus.SUCCESS,
                            classification.confidence(),
                            reply,
                            25
                    );
                }
            }
            case MEMORY -> {
                String op = classification.entities().get("operation");
                if ("STORE_NAME".equals(op)) {
                    String name = classification.entities().get("userName");
                    userProfile.setUserName(name);
                    try {
                        userProfile.saveToFile(UserMemoryProfile.DEFAULT_PROFILE_PATH);
                    } catch (Exception ignored) {
                    }
                    String reply = "Nice to meet you, " + name + ". I've updated my memory with your name.";
                    System.out.println(reply);
                    if (ambientVoiceEnabled) ttsSynthesizer.speak(reply);
                    if (episodicLedger != null) {
                        episodicLedger.recordGoal(
                                UUID.randomUUID().toString(),
                                UserIntentType.MEMORY,
                                normalized,
                                "Store user name: " + name,
                                GoalStatus.SUCCESS,
                                classification.confidence(),
                                reply,
                                10
                        );
                    }
                } else if ("RECALL_NAME".equals(op)) {
                    String name = userProfile.getUserName();
                    String reply = name != null && !name.isBlank()
                            ? "Your name is " + name + "."
                            : "I don't know your name yet. You can tell me by saying 'I am <name>'.";
                    System.out.println(reply);
                    if (ambientVoiceEnabled) ttsSynthesizer.speak(reply);
                    if (episodicLedger != null) {
                        episodicLedger.recordGoal(
                                UUID.randomUUID().toString(),
                                UserIntentType.MEMORY,
                                normalized,
                                "Recall user name",
                                GoalStatus.SUCCESS,
                                classification.confidence(),
                                reply,
                                10
                        );
                    }
                } else if ("STORE_PREFERENCE".equals(op)) {
                    String k = classification.entities().get("preferenceKey");
                    String v = classification.entities().get("preferenceValue");
                    userProfile.setProperty(k, v);
                    try {
                        userProfile.saveToFile(UserMemoryProfile.DEFAULT_PROFILE_PATH);
                    } catch (Exception ignored) {
                    }
                    String reply = "I have updated your " + k + " preference to: " + v;
                    System.out.println(reply);
                    if (ambientVoiceEnabled) ttsSynthesizer.speak(reply);
                } else {
                    printMemory();
                }
            }
            case PROJECT -> {
                ensureClient();
                WorkspaceContext ctx = contextIndexer.getContext();
                String lower = normalized.toLowerCase();
                if (lower.contains("source") || lower.contains("java") || lower.contains("find")) {
                    executeAutonomousGoal("Find all Java source directories");
                } else {
                    if (client != null && client.getProjectSdk() != null) {
                        try {
                            client.getProjectSdk().analyze(ctx.rootPath());
                        } catch (Exception ignored) {
                        }
                    }
                    System.out.println("=== PROJECT INTELLIGENCE SUMMARY ===");
                    System.out.println("Project Name   : " + ctx.projectName());
                    System.out.println("Project Version: " + ctx.projectVersion());
                    System.out.println("Build Tool     : " + ctx.buildTool());
                    System.out.println("Framework      : " + ctx.detectedFramework());
                    System.out.println("Source Roots   : " + ctx.sourceDirectories());

                    String executiveSummary = null;
                    if (client != null && client.reasoning() != null) {
                        String facts = "Project Name: " + ctx.projectName() + "\n"
                                + "Project Version: " + ctx.projectVersion() + "\n"
                                + "Build Tool: " + ctx.buildTool() + "\n"
                                + "Framework: " + ctx.detectedFramework() + "\n"
                                + "Source Roots: " + ctx.sourceDirectories();
                        try {
                            executiveSummary = client.reasoning().analyze(
                                    "Summarize the workspace project structure and architecture concisely for Darshan:",
                                    facts
                            );
                        } catch (Exception ignored) {
                        }
                        if (executiveSummary != null && !executiveSummary.isBlank()) {
                            System.out.println("\n[EXECUTIVE SUMMARY]\n" + executiveSummary);
                        }
                    }

                    if (ambientVoiceEnabled && ttsSynthesizer != null) {
                        String spoken = executiveSummary != null && !executiveSummary.isBlank()
                                ? executiveSummary
                                : "Project intelligence summary complete for " + ctx.projectName();
                        ttsSynthesizer.speak(spoken);
                    }

                    if (episodicLedger != null) {
                        episodicLedger.recordGoal(
                                UUID.randomUUID().toString(),
                                UserIntentType.PROJECT,
                                normalized,
                                "Project intelligence summary",
                                GoalStatus.SUCCESS,
                                classification.confidence(),
                                "Project " + ctx.projectName() + " (" + ctx.buildTool() + ")",
                                25
                        );
                    }
                }
            }
            case SYSTEM -> {
                String op = classification.entities() != null ? classification.entities().get("operation") : null;
                if ("KEYS".equalsIgnoreCase(op) || normalized.toLowerCase().contains("keys")) {
                    printKeys();
                } else {
                    handleStatus();
                }
            }
            case EXECUTE -> {
                String cmd = classification.normalizedQuery().isEmpty() ? normalized : classification.normalizedQuery();
                executeAutonomousGoal(cmd);
            }
        }
    }

    public void recordConversationTurn(String userText, String assistantText) {
        conversationHistory.add(new ChatTurn("user", userText));
        conversationHistory.add(new ChatTurn("assistant", assistantText));
        while (conversationHistory.size() > 10) {
            conversationHistory.remove(0);
        }
    }

    public List<ChatTurn> getConversationHistory() {
        return Collections.unmodifiableList(conversationHistory);
    }

    public String buildChatGroundingContext() {
        StringBuilder sb = new StringBuilder();
        String userName = userProfile.getUserName();
        sb.append("User: ").append(userName != null && !userName.isBlank() ? userName : "Darshan").append("\n");
        sb.append("Preferred Shell: ").append(userProfile.getPreferredShell()).append("\n");
        sb.append("Preferred Editor: ").append(userProfile.getPreferredEditor()).append("\n");
        if (contextIndexer != null) {
            WorkspaceContext ctx = contextIndexer.getContext();
            sb.append("Active Workspace: ").append(ctx.projectName())
                    .append(" (").append(ctx.buildTool())
                    .append(", ").append(ctx.detectedFramework())
                    .append(", Roots: ").append(ctx.sourceDirectories()).append(")\n");
        }
        if (!conversationHistory.isEmpty()) {
            sb.append("Recent Dialogue History:\n");
            for (ChatTurn turn : conversationHistory) {
                sb.append("  ").append(turn.role().toUpperCase()).append(": ").append(turn.text()).append("\n");
            }
        }
        return sb.toString();
    }

    private boolean isToolExecutionProjectQuery(String query) {
        if (query == null) return false;
        String lower = query.toLowerCase();
        return lower.contains("find") || lower.contains("walk") || lower.contains("list files");
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

    private synchronized SovereignClient ensureClient() {
        if (client == null) {
            try {
                client = SovereignClient.create(providerConfig);
            } catch (Exception e) {
                System.err.println("Warning: SovereignClient init fallback: " + e.getMessage());
            }
        }
        return client;
    }

    private synchronized void ensureOperator() {
        if (autonomousOperator == null) {
            ensureClient();
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
        System.out.println("User Name        : " + (userProfile.getUserName() != null ? userProfile.getUserName() : "<not set>"));
        System.out.println("Preferred Shell  : " + userProfile.getPreferredShell());
        System.out.println("Preferred Editor : " + userProfile.getPreferredEditor());
        System.out.println("Custom Aliases   : " + userProfile.getCustomAliases());
        System.out.println("Favorite Projects: " + userProfile.getFavoriteProjects());
        System.out.println("Properties       : " + userProfile.getProperties());
        System.out.println("\n=== EPISODIC GOAL HISTORY ===");
        var recent = episodicLedger.getRecentGoals(10);
        if (recent.isEmpty()) {
            System.out.println("No past goal executions recorded in episodic ledger.");
        } else {
            recent.forEach(e -> System.out.printf("  [%s] [%s] %s | Status: %s (%dms)%n",
                    e.goalId(), e.intent(), e.description(), e.status(), e.durationMs()));
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
        ensureClient();
        ensureOperator();
        long uptimeSec = Duration.between(sessionStartTime, Instant.now()).toSeconds();
        String state = client != null ? client.getPlatformRuntime().getState().name() : "IN_MEMORY";
        int memoryCount = episodicLedger != null ? episodicLedger.getAllEntries().size() : 0;
        String userName = userProfile.getUserName() != null ? userProfile.getUserName() : "Anonymous";

        System.out.println("=== SOVEREIGN OPERATOR STATUS ===");
        System.out.println("Runtime State   : " + state);
        System.out.println("Session Uptime  : " + uptimeSec + "s");
        System.out.println("Memory Entries  : " + memoryCount);
        System.out.println("Identified User : " + userName);
        System.out.println("Preferred Shell : " + userProfile.getPreferredShell());
        System.out.println("Preferred Editor: " + userProfile.getPreferredEditor());
        System.out.println("Active SDKs     : PlanningSDK, ReasoningSDK, MemorySDK, ProjectSDK, DeveloperSDK (5 initialized)");
    }

    public void printKeys() {
        String geminiMasked = ProviderConfig.maskKey(providerConfig.getGeminiApiKey());
        String geminiStatus = providerConfig.hasGeminiKey()
                ? geminiMasked + " [ACTIVE - " + providerConfig.getGeminiSource() + "]"
                : "[NOT DETECTED]";

        String openAiMasked = ProviderConfig.maskKey(providerConfig.getOpenAiApiKey());
        String openAiStatus = providerConfig.hasOpenAiKey()
                ? openAiMasked + " [ACTIVE - " + providerConfig.getOpenAiSource() + "]"
                : "[NOT DETECTED]";

        System.out.println("""
                ============================================================
                           SOVEREIGN NEURAL LINK & CREDENTIALS
                ============================================================
                 Gemini API Key : %s
                 OpenAI API Key : %s
                 Active LLM Chain: %s
                 Neural Link    : %s
                 Status         : %s
                ============================================================
                """.formatted(
                geminiStatus,
                openAiStatus,
                providerConfig.resolveActiveChain(),
                providerConfig.isOnline() ? "ONLINE (" + providerConfig.getActiveProviderDisplayName() + ")" : "OFFLINE (Deterministic In-Memory)",
                providerConfig.getBanner()
        ));
    }

    public ProviderConfig getProviderConfig() {
        return providerConfig;
    }

    private void printHelp() {
        System.out.println("""
            Commands:
              <natural query>         Conversational input routed via IntentRouter (e.g. 'hello', 'I am Darshan')
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
              status                  Check Sovereign Operator runtime status and session uptime
              keys                    Inspect LLM provider detection, active router chain, and masked keys
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

    public IntentRouter getIntentRouter() {
        return intentRouter;
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

    public SovereignClient getClient() {
        return ensureClient();
    }
}
