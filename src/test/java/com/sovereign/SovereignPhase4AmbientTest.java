package com.sovereign;

import com.sovereign.core.client.SovereignClient;
import com.sovereign.core.daemon.WorkspaceAlert;
import com.sovereign.core.daemon.WorkspaceWatcherDaemon;
import com.sovereign.core.react.engine.AutonomousOperator;
import com.sovereign.core.react.planner.GoalDecomposer;
import com.sovereign.core.react.recovery.CausalErrorRecoveryEngine;
import com.sovereign.core.tools.FileSystemTool;
import com.sovereign.core.tools.ProcessControlTool;
import com.sovereign.core.tools.ShellExecutionTool;
import com.sovereign.core.voice.SpeechToTextAdapter;
import com.sovereign.core.voice.TextToSpeechSynthesizer;
import com.sovereign.core.voice.VoiceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignPhase4AmbientTest</b>
 *
 * <p>Phase 4 Verification Test Suite validating Ambient Voice Bridge (STT, TTS),
 * Workspace Watcher Daemon, and End-to-End Voice-to-Action Autonomous ReAct Loop.</p>
 */
public class SovereignPhase4AmbientTest {

    @Test
    @DisplayName("Test 1: SpeechToTextAdapter correctly transcribes mock audio stream to command text")
    void testSpeechToTextAdapterTranscribesMockAudio() throws Exception {
        VoiceConfig config = VoiceConfig.defaultConfig();
        SpeechToTextAdapter stt = new SpeechToTextAdapter(config);

        String rawPrompt = "Hey Sovereign, run tests";
        try (InputStream audioStream = SpeechToTextAdapter.createMockAudioStream(rawPrompt)) {
            String transcript = stt.transcribe(audioStream);
            assertThat(transcript).isEqualTo(rawPrompt);
            assertThat(stt.hasWakeWord(transcript)).isTrue();

            String intent = stt.stripWakeWord(transcript);
            assertThat(intent).isEqualTo("run tests");
        }

        // Additional wake-word test with Jarvis
        String jarvisPrompt = "Jarvis: build project";
        byte[] audioBytes = SpeechToTextAdapter.createMockAudioBytes(jarvisPrompt);
        String jarvisTranscript = stt.transcribe(audioBytes);
        assertThat(jarvisTranscript).isEqualTo(jarvisPrompt);
        assertThat(stt.hasWakeWord(jarvisTranscript)).isTrue();
        assertThat(stt.stripWakeWord(jarvisTranscript)).isEqualTo("build project");
    }

    @Test
    @DisplayName("Test 2: TextToSpeechSynthesizer cleanly synthesizes text responses without runtime failure")
    void testTextToSpeechSynthesizerExecution() {
        VoiceConfig silentConfig = VoiceConfig.silentConfig();
        TextToSpeechSynthesizer silentTts = new TextToSpeechSynthesizer(silentConfig);

        boolean silentResult = silentTts.speak("System diagnostics completed successfully.");
        assertThat(silentResult).isTrue();
        assertThat(silentTts.getLastSpokenText()).isEqualTo("System diagnostics completed successfully.");
        assertThat(silentTts.getSpokenHistory()).contains("System diagnostics completed successfully.");

        // Default platform TTS execution (with fallback on headless/unsupported systems)
        VoiceConfig platformConfig = VoiceConfig.defaultConfig();
        TextToSpeechSynthesizer platformTts = new TextToSpeechSynthesizer(platformConfig);
        boolean platformResult = platformTts.speak("Sovereign operator online and ready.");
        assertThat(platformResult).isTrue();
        assertThat(platformTts.getLastSpokenText()).isEqualTo("Sovereign operator online and ready.");
        assertThat(platformTts.getSpokenHistory()).hasSize(1);
    }

    @Test
    @DisplayName("Test 3: WorkspaceWatcherDaemon detects file modifications in a watched temporary directory")
    void testWorkspaceWatcherDaemonCapturesModifications(@TempDir Path tempDir) throws Exception {
        try (WorkspaceWatcherDaemon watcher = new WorkspaceWatcherDaemon(tempDir)) {
            CountDownLatch alertLatch = new CountDownLatch(1);
            AtomicReference<WorkspaceAlert> capturedAlert = new AtomicReference<>();

            watcher.addListener(alert -> {
                capturedAlert.set(alert);
                alertLatch.countDown();
            });

            watcher.start();
            assertThat(watcher.isRunning()).isTrue();

            // Create a test error log inside watched directory
            Path logFile = tempDir.resolve("build-failure.log");
            Files.writeString(logFile, "BUILD ERROR: compilation failure in Module A");

            boolean received = alertLatch.await(5, TimeUnit.SECONDS);
            assertThat(received).as("Expected watcher daemon to detect file creation within 5 seconds").isTrue();

            WorkspaceAlert alert = capturedAlert.get();
            assertThat(alert).isNotNull();
            assertThat(alert.isErrorOrLog()).isTrue();
            assertThat(alert.path().getFileName().toString()).isEqualTo("build-failure.log");

            watcher.stop();
            assertThat(watcher.isRunning()).isFalse();
        }
    }

    @Test
    @DisplayName("Test 4: End-to-end voice-to-action simulation: simulated audio -> STT -> ReAct loop -> TTS")
    void testEndToEndVoiceToActionPipeline() throws Exception {
        try (SovereignClient client = SovereignClient.create()) {
            VoiceConfig voiceConfig = VoiceConfig.silentConfig();
            SpeechToTextAdapter stt = new SpeechToTextAdapter(voiceConfig);
            TextToSpeechSynthesizer tts = new TextToSpeechSynthesizer(voiceConfig);

            GoalDecomposer decomposer = new GoalDecomposer(client.getPlanningSdk(), null, null, null);
            CausalErrorRecoveryEngine recovery = new CausalErrorRecoveryEngine(client.getReasoningSdk());
            AutonomousOperator operator = new AutonomousOperator(
                    decomposer,
                    recovery,
                    new ShellExecutionTool(),
                    new FileSystemTool(),
                    new ProcessControlTool()
            );

            // Step 1: Ingest simulated spoken audio command
            String voiceInput = "Hey Sovereign, echo Phase4_Voice_Loop_Verified";
            InputStream audioStream = SpeechToTextAdapter.createMockAudioStream(voiceInput);

            // Step 2: Transcribe via STT
            String transcript = stt.transcribe(audioStream);
            assertThat(transcript).isEqualTo(voiceInput);
            assertThat(stt.hasWakeWord(transcript)).isTrue();

            // Step 3: Strip wake-word to extract operator goal
            String intent = stt.stripWakeWord(transcript);
            assertThat(intent).isEqualTo("echo Phase4_Voice_Loop_Verified");

            // Step 4: Execute autonomous ReAct loop
            AutonomousOperator.OperatorResult opResult = operator.execute(intent);
            assertThat(opResult.success()).isTrue();
            assertThat(opResult.stepResults()).hasSize(1);
            assertThat(opResult.stepResults().get(0).stdout()).contains("Phase4_Voice_Loop_Verified");

            // Step 5: Synthesize spoken summary via TTS
            boolean spoken = tts.speak(opResult.summary());
            assertThat(spoken).isTrue();
            assertThat(tts.getLastSpokenText()).isEqualTo(opResult.summary());
            assertThat(tts.getSpokenHistory()).contains(opResult.summary());
        }
    }
}
