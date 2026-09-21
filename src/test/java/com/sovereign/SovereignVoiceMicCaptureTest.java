package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.voice.MicrophoneAudioCapture;
import com.sovereign.core.voice.SpeechToTextAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <b>SovereignVoiceMicCaptureTest</b>
 *
 * <p>Headless-safe verification tests for {@link MicrophoneAudioCapture} and related
 * voice pipeline changes. All tests must pass in CI environments with no audio hardware.</p>
 */
public class SovereignVoiceMicCaptureTest {

    // ─── Test 1: AudioFormat is correct ──────────────────────────────────────

    @Test
    @DisplayName("Test 1: MicrophoneAudioCapture exposes correct 16kHz/16-bit/Mono AudioFormat")
    void testAudioFormatConstants() {
        MicrophoneAudioCapture mic = new MicrophoneAudioCapture();
        AudioFormat fmt = mic.getFormat();

        assertThat(fmt).isNotNull();
        assertThat(fmt.getSampleRate()).isEqualTo(MicrophoneAudioCapture.SAMPLE_RATE);
        assertThat(fmt.getSampleSizeInBits()).isEqualTo(MicrophoneAudioCapture.BITS_PER_SAMPLE);
        assertThat(fmt.getChannels()).isEqualTo(MicrophoneAudioCapture.CHANNELS);
        assertThat(fmt.getEncoding()).isEqualTo(AudioFormat.Encoding.PCM_SIGNED);
        assertThat(fmt.isBigEndian()).isFalse(); // little-endian

        // Verify class-level constants
        assertThat(MicrophoneAudioCapture.SAMPLE_RATE).isEqualTo(16_000.0f);
        assertThat(MicrophoneAudioCapture.BITS_PER_SAMPLE).isEqualTo(16);
        assertThat(MicrophoneAudioCapture.CHANNELS).isEqualTo(1);
        assertThat(MicrophoneAudioCapture.VAD_THRESHOLD).isPositive();
        assertThat(MicrophoneAudioCapture.SILENCE_CUTOFF_MS).isEqualTo(1_500L);
        assertThat(MicrophoneAudioCapture.MAX_RECORDING_MS).isEqualTo(10_000L);
    }

    // ─── Test 2: Constructor does not throw in headless/CI environments ────

    @Test
    @DisplayName("Test 2: MicrophoneAudioCapture initializes without throwing in headless/CI environments")
    void testConstructorDoesNotThrowHeadless() {
        assertThatCode(MicrophoneAudioCapture::new)
                .as("MicrophoneAudioCapture constructor must not throw even when no mic hardware is present")
                .doesNotThrowAnyException();
    }

    // ─── Test 3: isAvailable() returns a boolean without throwing ─────────

    @Test
    @DisplayName("Test 3: isAvailable() reports microphone presence without throwing")
    void testIsAvailableNeverThrows() {
        MicrophoneAudioCapture mic = new MicrophoneAudioCapture();
        // Should always complete — true on machines with mic, false on headless CI
        boolean available = mic.isAvailable();
        // Just ensure no exception was thrown. We cannot assert the value since it depends on hardware.
        assertThat(available).isIn(true, false);
    }

    // ─── Test 4: captureUtterance() returns empty bytes when no mic ───────

    @Test
    @DisplayName("Test 4: captureUtterance() returns empty byte array when mic is unavailable")
    void testCaptureUtteranceReturnsEmptyWhenUnavailable() {
        MicrophoneAudioCapture mic = new MicrophoneAudioCapture();
        if (!mic.isAvailable()) {
            byte[] result = mic.captureUtterance();
            assertThat(result).isNotNull().isEmpty();
        }
        // If mic IS available, skip — we don't want to block CI for real audio
    }

    // ─── Test 5: buildWav() produces valid 44-byte RIFF header + data ────

    @Test
    @DisplayName("Test 5: buildWav() wraps PCM data in a valid WAV container with correct RIFF header")
    void testBuildWavProducesValidHeader() throws Exception {
        byte[] pcm = new byte[3200]; // 0.1s of silence at 16kHz/16-bit
        byte[] wav = MicrophoneAudioCapture.buildWav(pcm);

        // WAV = 44-byte header + PCM data
        assertThat(wav).isNotNull();
        assertThat(wav.length).isEqualTo(44 + pcm.length);

        // RIFF magic
        assertThat(new String(wav, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4)).isEqualTo("WAVE");
        assertThat(new String(wav, 12, 4)).isEqualTo("fmt ");
        assertThat(new String(wav, 36, 4)).isEqualTo("data");

        // fmt sub-chunk size = 16 for PCM (little-endian int at offset 16)
        int fmtSize = (wav[16] & 0xFF) | ((wav[17] & 0xFF) << 8)
                    | ((wav[18] & 0xFF) << 16) | ((wav[19] & 0xFF) << 24);
        assertThat(fmtSize).isEqualTo(16);

        // Audio format = 1 (PCM) at offset 20 (short LE)
        int audioFmt = (wav[20] & 0xFF) | ((wav[21] & 0xFF) << 8);
        assertThat(audioFmt).isEqualTo(1);

        // Channels at offset 22
        int channels = (wav[22] & 0xFF) | ((wav[23] & 0xFF) << 8);
        assertThat(channels).isEqualTo(1);

        // Sample rate at offset 24
        int sampleRate = (wav[24] & 0xFF) | ((wav[25] & 0xFF) << 8)
                       | ((wav[26] & 0xFF) << 16) | ((wav[27] & 0xFF) << 24);
        assertThat(sampleRate).isEqualTo(16_000);

        // Bits per sample at offset 34
        int bps = (wav[34] & 0xFF) | ((wav[35] & 0xFF) << 8);
        assertThat(bps).isEqualTo(16);
    }

    // ─── Test 6: computeRms() returns 0 for silent buffer ────────────────

    @Test
    @DisplayName("Test 6: computeRms() returns 0.0 for a fully-silent (all-zero) PCM buffer")
    void testComputeRmsOnSilence() {
        byte[] silence = new byte[3200]; // all zeros = PCM silence
        double rms = MicrophoneAudioCapture.computeRms(silence, silence.length);
        assertThat(rms).isEqualTo(0.0);
    }

    // ─── Test 7: computeRms() returns > threshold for loud signal ────────

    @Test
    @DisplayName("Test 7: computeRms() returns > VAD_THRESHOLD for a full-scale sine-like PCM signal")
    void testComputeRmsOnLoudSignal() {
        // Fill a buffer with max-value 16-bit samples (0x7FFF = 32767)
        int samples = 1024;
        byte[] loud = new byte[samples * 2];
        for (int i = 0; i < loud.length; i += 2) {
            // 0x7FFF little-endian
            loud[i]     = (byte) 0xFF;
            loud[i + 1] = (byte) 0x7F;
        }
        double rms = MicrophoneAudioCapture.computeRms(loud, loud.length);
        assertThat(rms).isGreaterThan(MicrophoneAudioCapture.VAD_THRESHOLD);
    }

    // ─── Test 8: captureUtteranceAsWav() returns non-null InputStream ────

    @Test
    @DisplayName("Test 8: captureUtteranceAsWav() returns a non-null InputStream without throwing")
    void testCaptureUtteranceAsWavNeverThrows() {
        assertThatCode(() -> {
            MicrophoneAudioCapture mic = new MicrophoneAudioCapture();
            if (!mic.isAvailable()) {
                InputStream stream = mic.captureUtteranceAsWav();
                assertThat(stream).isNotNull();
                // On headless: empty byte stream
                byte[] bytes = stream.readAllBytes();
                assertThat(bytes).isNotNull().isEmpty();
            }
        }).doesNotThrowAnyException();
    }

    // ─── Test 9: WAV from buildWav is transcribable via SpeechToTextAdapter

    @Test
    @DisplayName("Test 9: WAV-wrapped mock audio bytes transcribe correctly via SpeechToTextAdapter")
    void testWavWrappedMockAudioTranscribesCorrectly() throws Exception {
        // Use SpeechToTextAdapter's mock infrastructure
        String expected = "hey sovereign run integration tests";
        byte[] mockWav = SpeechToTextAdapter.createMockAudioBytes(expected);

        SpeechToTextAdapter stt = new SpeechToTextAdapter();
        String transcript = stt.transcribe(new ByteArrayInputStream(mockWav));
        assertThat(transcript).isEqualTo(expected);
    }

    // ─── Test 10: Bare 'speak' in REPL interactive loop prints usage ──────

    @Test
    @DisplayName("Test 10: Bare 'speak' command in interactive mode prints usage help, not shell error")
    void testBareSpeakCommandPrintsUsage() {
        SovereignReplRunner runner = new SovereignReplRunner();

        // Feed "speak\nexit\n" to simulate the interactive REPL receiving bare 'speak'
        String simulatedInput = "speak\nexit\n";
        java.io.InputStream originalIn = System.in;
        java.io.PrintStream originalOut = System.out;
        try {
            System.setIn(new ByteArrayInputStream(simulatedInput.getBytes()));
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            System.setOut(new java.io.PrintStream(captured));
            runner.runInteractiveLoop();
            String output = captured.toString();
            assertThat(output).containsIgnoringCase("Usage: speak");
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }
}
