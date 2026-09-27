package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.voice.AudioTranscriptionService;
import com.sovereign.core.voice.VoskSpeechRecognizer;
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
 * <b>SovereignPhase5SpeechTest</b>
 *
 * <p>Unit and integration tests for Phase 5: Free STT and offline voice intelligence.
 * Verifies Whisper model/language resolution, Vosk offline recognizer discovery,
 * multi-tier fallback cascade, and REPL diagnostic commands.</p>
 */
public class SovereignPhase5SpeechTest {

    @Test
    @DisplayName("Test 1: Whisper model and language resolution respect system properties")
    void testWhisperConfigResolution() {
        String originalModel = System.getProperty("sovereign.whisper.model");
        String originalLang = System.getProperty("sovereign.whisper.language");
        try {
            System.clearProperty("sovereign.whisper.model");
            System.clearProperty("sovereign.whisper.language");

            assertThat(AudioTranscriptionService.resolveWhisperModel()).isEqualTo("small");
            assertThat(AudioTranscriptionService.resolveWhisperLanguage()).isEqualTo("en");

            System.setProperty("sovereign.whisper.model", "tiny");
            System.setProperty("sovereign.whisper.language", "hi");

            assertThat(AudioTranscriptionService.resolveWhisperModel()).isEqualTo("tiny");
            assertThat(AudioTranscriptionService.resolveWhisperLanguage()).isEqualTo("hi");
        } finally {
            if (originalModel != null) System.setProperty("sovereign.whisper.model", originalModel);
            else System.clearProperty("sovereign.whisper.model");

            if (originalLang != null) System.setProperty("sovereign.whisper.language", originalLang);
            else System.clearProperty("sovereign.whisper.language");
        }
    }

    @Test
    @DisplayName("Test 2: VoskSpeechRecognizer detects model presence and handles missing model safely")
    void testVoskRecognizerDiscovery(@TempDir Path tempDir) throws Exception {
        VoskSpeechRecognizer missingRecognizer = new VoskSpeechRecognizer(tempDir.resolve("non_existent"));
        assertThat(missingRecognizer.isAvailable()).isFalse();
        assertThat(missingRecognizer.transcribe(new byte[100])).isNull();

        Path validModelDir = tempDir.resolve("vosk-model-en");
        Files.createDirectories(validModelDir.resolve("am"));
        VoskSpeechRecognizer availableRecognizer = new VoskSpeechRecognizer(validModelDir);
        assertThat(availableRecognizer.isAvailable()).isTrue();
        assertThat(availableRecognizer.getModelPath()).isEqualTo(validModelDir);
    }

    @Test
    @DisplayName("Test 3: AudioTranscriptionService resolves tier and safe silence fallback")
    void testTranscriptionTierResolution() {
        ProviderConfig offlineConfig = ProviderConfig.of(null, null);
        AudioTranscriptionService stt = new AudioTranscriptionService(offlineConfig);

        String activeTier = stt.getActiveTier();
        assertThat(activeTier).isIn("whisper", "vosk", "sapi", "heuristic");

        // Bytes shorter than 44 bytes (invalid WAV) returns null
        assertThat(stt.transcribeWav(new byte[10])).isNull();
        assertThat(stt.transcribeWav(null)).isNull();
    }

    @Test
    @DisplayName("Test 4: SovereignReplRunner printVoiceStatus renders without error")
    void testReplVoiceStatusOutput() {
        ProviderConfig offlineConfig = ProviderConfig.of(null, null);
        SovereignReplRunner runner = new SovereignReplRunner(offlineConfig);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        try {
            System.setOut(new PrintStream(baos));
            assertThatCode(runner::printVoiceStatus).doesNotThrowAnyException();
            String output = baos.toString();
            assertThat(output).contains("SOVEREIGN SPEECH & VOICE INTELLIGENCE");
            assertThat(output).contains("Active STT Tier");
            assertThat(output).contains("Whisper Model");
        } finally {
            System.setOut(origOut);
        }
    }
}
