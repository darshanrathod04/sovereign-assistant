package com.sovereign.core.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * <b>MicrophoneAudioCapture</b>
 *
 * <p>Captures real physical microphone audio using the Java Sound API ({@link TargetDataLine}).
 * Records at 16 kHz / 16-bit / Mono (the standard format for speech recognition engines),
 * applies a lightweight RMS-energy Voice Activity Detection (VAD) threshold to automatically
 * start and stop recording, and wraps the captured PCM data in a valid WAV stream ready for
 * {@link SpeechToTextAdapter#transcribe(InputStream)}.</p>
 *
 * <h3>Headless / CI safety</h3>
 * <p>If no physical microphone line is available (headless server, CI pipeline, no audio
 * device connected), the constructor succeeds but {@link #isAvailable()} returns
 * {@code false} and {@link #captureUtterance()} returns an empty byte array.
 * A clear warning is logged to {@code System.out} so developers know the fallback is active.</p>
 *
 * <h3>Recording lifecycle</h3>
 * <ol>
 *   <li>Audio chunks (256-sample frames) are read continuously.</li>
 *   <li>When RMS energy of a chunk exceeds {@link #VAD_THRESHOLD} the utterance buffer starts.</li>
 *   <li>After {@link #SILENCE_CUTOFF_MS} ms of continuous silence following speech, recording stops.</li>
 *   <li>A hard {@link #MAX_RECORDING_MS} timeout prevents indefinite blocking.</li>
 * </ol>
 */
public class MicrophoneAudioCapture {

    // ── Audio format: 16 kHz, 16-bit, Mono, Signed, Little-Endian ─────────
    public static final float SAMPLE_RATE     = 16_000.0f;
    public static final int   BITS_PER_SAMPLE = 16;
    public static final int   CHANNELS        = 1;

    /** RMS amplitude threshold for voice activity detection (0–32768 range). */
    public static final double VAD_THRESHOLD = 500.0;

    /** Consecutive silence duration (ms) after speech that triggers end-of-utterance. */
    public static final long SILENCE_CUTOFF_MS = 1_500L;

    /** Maximum recording duration (ms) — hard safety cap per utterance. */
    public static final long MAX_RECORDING_MS = 10_000L;

    /** Bytes per audio frame (chunk) read from the line in a single iteration. */
    private static final int CHUNK_FRAMES = 1024;

    private final AudioFormat format;
    private final boolean available;

    // ── Dependency-injection seam for testing ──────────────────────────────
    /** Allows tests (or callers) to inject a simulated TargetDataLine. */
    private TargetDataLine injectedLine = null;

    public MicrophoneAudioCapture() {
        this.format = buildFormat();
        this.available = probeAvailability(format);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when a hardware microphone line can be opened on this machine.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns the {@link AudioFormat} used for recording.
     */
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Injects a custom {@link TargetDataLine} — intended for unit tests that need to
     * simulate mic input without real hardware.
     */
    public void injectLine(TargetDataLine line) {
        this.injectedLine = line;
    }

    /**
     * Captures one spoken utterance from the physical microphone and returns the raw
     * PCM bytes (not yet WAV-wrapped).
     *
     * <p>The method blocks until either:</p>
     * <ul>
     *   <li>Voice activity is detected and then {@link #SILENCE_CUTOFF_MS} ms of silence elapses, or</li>
     *   <li>{@link #MAX_RECORDING_MS} ms total recording time elapses.</li>
     * </ul>
     *
     * <p>Returns an empty {@code byte[]} if the microphone is unavailable or no audio is captured.</p>
     */
    public byte[] captureUtterance() {
        if (!available && injectedLine == null) {
            return new byte[0];
        }

        TargetDataLine line = null;
        try {
            line = acquireLine();
            if (line == null) {
                return new byte[0];
            }
            line.start();
            return doRecord(line);
        } catch (LineUnavailableException e) {
            System.out.println("[VOICE] Microphone line unavailable during capture: " + e.getMessage());
            return new byte[0];
        } finally {
            if (line != null && line.isOpen()) {
                line.stop();
                line.close();
            }
        }
    }

    /**
     * Captures one utterance and wraps the PCM data in a valid WAV
     * {@link InputStream}, suitable for passing directly to
     * {@link SpeechToTextAdapter#transcribe(InputStream)}.
     *
     * @return WAV stream, or empty stream if no audio was captured
     */
    public InputStream captureUtteranceAsWav() throws IOException {
        byte[] pcm = captureUtterance();
        if (pcm.length == 0) {
            return new ByteArrayInputStream(new byte[0]);
        }
        return new ByteArrayInputStream(buildWav(pcm));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    private static AudioFormat buildFormat() {
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                SAMPLE_RATE,
                BITS_PER_SAMPLE,
                CHANNELS,
                CHANNELS * (BITS_PER_SAMPLE / 8), // frame size = 2 bytes
                SAMPLE_RATE,
                false // little-endian
        );
    }

    /**
     * Probes whether a TargetDataLine for the given format can be opened.
     * Returns {@code false} cleanly if no audio hardware is present.
     */
    private static boolean probeAvailability(AudioFormat fmt) {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                System.out.println("[VOICE] No hardware microphone detected. " +
                        "Falling back to console transcript input.");
                return false;
            }
            // Briefly open and immediately close to confirm the line is accessible
            TargetDataLine probe = (TargetDataLine) AudioSystem.getLine(info);
            probe.open(fmt);
            probe.close();
            return true;
        } catch (Exception e) {
            System.out.println("[VOICE] No hardware microphone detected. " +
                    "Falling back to console transcript input.");
            return false;
        }
    }

    /**
     * Acquires the recording line — either the injected test line or a real hardware line.
     */
    private TargetDataLine acquireLine() throws LineUnavailableException {
        if (injectedLine != null) {
            if (!injectedLine.isOpen()) {
                injectedLine.open(format);
            }
            return injectedLine;
        }
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        return line;
    }

    /**
     * Core VAD-based recording loop. Reads audio in chunks, tracks RMS energy,
     * and accumulates voiced audio until silence cutoff or max duration.
     */
    private byte[] doRecord(TargetDataLine line) {
        int bytesPerFrame = format.getFrameSize();
        int chunkBytes    = CHUNK_FRAMES * bytesPerFrame;
        byte[] chunk      = new byte[chunkBytes];

        ByteArrayOutputStream utteranceBuffer = new ByteArrayOutputStream();
        boolean voiceStarted  = false;
        long    silenceStartMs = 0;
        long    recordingStart = System.currentTimeMillis();

        while (true) {
            long elapsed = System.currentTimeMillis() - recordingStart;
            if (elapsed >= MAX_RECORDING_MS) {
                break; // hard timeout
            }

            int read = line.read(chunk, 0, chunk.length);
            if (read <= 0) {
                continue;
            }

            double rms = computeRms(chunk, read);

            if (rms > VAD_THRESHOLD) {
                // Voice detected
                voiceStarted  = true;
                silenceStartMs = 0;
                utteranceBuffer.write(chunk, 0, read);
            } else {
                if (voiceStarted) {
                    // Accumulate silence into buffer for natural trailing audio
                    utteranceBuffer.write(chunk, 0, read);

                    if (silenceStartMs == 0) {
                        silenceStartMs = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - silenceStartMs >= SILENCE_CUTOFF_MS) {
                        break; // end of utterance
                    }
                }
                // else: pre-speech silence — discard and keep listening
            }
        }

        return utteranceBuffer.toByteArray();
    }

    /**
     * Computes the RMS (root-mean-square) energy of a 16-bit little-endian PCM chunk.
     * Exposed as {@code public static} so test utilities can verify VAD thresholds directly.
     */
    public static double computeRms(byte[] buffer, int length) {
        long sumSquares = 0;
        int samples = length / 2; // 16-bit = 2 bytes per sample
        for (int i = 0; i + 1 < length; i += 2) {
            // Little-endian 16-bit signed sample
            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            sumSquares += (long) sample * sample;
        }
        if (samples == 0) return 0.0;
        return Math.sqrt((double) sumSquares / samples);
    }

    /**
     * Wraps raw PCM bytes in a minimal valid WAV file header (RIFF/WAVE/fmt /data).
     */
    public static byte[] buildWav(byte[] pcmData) throws IOException {
        int sampleRate    = (int) SAMPLE_RATE;
        int byteRate      = sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8);
        int blockAlign    = CHANNELS * (BITS_PER_SAMPLE / 8);
        int dataChunkSize = pcmData.length;
        int riffChunkSize = 36 + dataChunkSize;

        ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + dataChunkSize);
        // RIFF chunk
        writeAscii(wav, "RIFF");
        writeIntLE(wav, riffChunkSize);
        writeAscii(wav, "WAVE");
        // fmt sub-chunk
        writeAscii(wav, "fmt ");
        writeIntLE(wav, 16);                    // sub-chunk size (PCM)
        writeShortLE(wav, (short) 1);           // PCM format
        writeShortLE(wav, (short) CHANNELS);
        writeIntLE(wav, sampleRate);
        writeIntLE(wav, byteRate);
        writeShortLE(wav, (short) blockAlign);
        writeShortLE(wav, (short) BITS_PER_SAMPLE);
        // data sub-chunk
        writeAscii(wav, "data");
        writeIntLE(wav, dataChunkSize);
        wav.write(pcmData);
        return wav.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8)  & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, short v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }
}
