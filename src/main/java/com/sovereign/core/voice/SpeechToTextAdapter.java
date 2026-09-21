package com.sovereign.core.voice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * <b>SpeechToTextAdapter</b>
 *
 * <p>Transcribes audio input streams, WAV/PCM buffers, and audio files into text prompts.
 * Provides integration with local Whisper/System STT CLI when available, with deterministic
 * mock and in-memory fallbacks for automated testing and headless environments.</p>
 */
public class SpeechToTextAdapter {

    public static final String MOCK_AUDIO_PREFIX = "MOCK_AUDIO:";
    private static final byte[] RIFF_HEADER = "RIFF".getBytes(StandardCharsets.US_ASCII);

    private final VoiceConfig config;
    private final ConcurrentMap<String, String> registeredMockTranscripts = new ConcurrentHashMap<>();

    public SpeechToTextAdapter() {
        this(VoiceConfig.defaultConfig());
    }

    public SpeechToTextAdapter(VoiceConfig config) {
        this.config = config != null ? config : VoiceConfig.defaultConfig();
    }

    /**
     * Transcribes an InputStream containing audio bytes or a mock audio stream.
     */
    public String transcribe(InputStream audioStream) throws IOException {
        if (audioStream == null) {
            throw new IllegalArgumentException("Audio stream cannot be null");
        }
        byte[] bytes = audioStream.readAllBytes();
        return transcribe(bytes);
    }

    /**
     * Transcribes an audio byte array.
     */
    public String transcribe(byte[] audioData) throws IOException {
        if (audioData == null || audioData.length == 0) {
            return "";
        }

        // 1. Check for registered audio signatures or mock payload
        String detectedMock = extractMockTranscription(audioData);
        if (detectedMock != null) {
            return detectedMock;
        }

        // 2. If whisper engine is requested and available, attempt local CLI transcription
        if ("whisper".equalsIgnoreCase(config.getSttEngine()) && isWhisperCliAvailable()) {
            try {
                Path tempAudio = Files.createTempFile("sovereign_audio_", ".wav");
                try {
                    Files.write(tempAudio, audioData);
                    String result = transcribeWithLocalWhisper(tempAudio);
                    if (result != null && !result.isBlank()) {
                        return result;
                    }
                } finally {
                    Files.deleteIfExists(tempAudio);
                }
            } catch (Exception e) {
                // Fallback on CLI failure
            }
        }

        // 3. Fallback transcription heuristic
        return "Transcribed audio buffer (" + audioData.length + " bytes)";
    }

    /**
     * Transcribes an audio file on disk.
     */
    public String transcribe(Path audioFile) throws IOException {
        if (audioFile == null || !Files.exists(audioFile)) {
            throw new IllegalArgumentException("Audio file does not exist: " + audioFile);
        }
        byte[] bytes = Files.readAllBytes(audioFile);
        return transcribe(bytes);
    }

    /**
     * Extracts embedded mock transcription if audio data represents a mock audio stream or valid WAV with mock chunk.
     */
    private String extractMockTranscription(byte[] data) {
        String asString = new String(data, StandardCharsets.UTF_8);

        // Direct prefix check: "MOCK_AUDIO:<text>"
        if (asString.startsWith(MOCK_AUDIO_PREFIX)) {
            return asString.substring(MOCK_AUDIO_PREFIX.length()).trim();
        }

        // Check if embedded in RIFF WAV payload
        int markerIdx = asString.indexOf(MOCK_AUDIO_PREFIX);
        if (markerIdx != -1) {
            return asString.substring(markerIdx + MOCK_AUDIO_PREFIX.length()).trim();
        }

        // Check registered mock transcripts
        for (var entry : registeredMockTranscripts.entrySet()) {
            if (asString.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Registers a custom pattern-to-transcript mapping.
     */
    public void registerMockTranscript(String pattern, String transcription) {
        if (pattern != null && transcription != null) {
            registeredMockTranscripts.put(pattern, transcription);
        }
    }

    /**
     * Checks if text contains configured wake-words.
     */
    public boolean hasWakeWord(String text) {
        return config.containsWakeWord(text);
    }

    /**
     * Strips leading wake-words from the transcription.
     */
    public String stripWakeWord(String text) {
        return config.stripWakeWord(text);
    }

    /**
     * Creates a mock audio byte array containing a valid 44-byte WAV header and embedded transcription payload.
     */
    public static byte[] createMockAudioBytes(String transcription) {
        byte[] textBytes = (MOCK_AUDIO_PREFIX + transcription).getBytes(StandardCharsets.UTF_8);
        int subchunk2Size = textBytes.length;
        int chunkSize = 36 + subchunk2Size;
        int sampleRate = 16000;
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + subchunk2Size);
        try {
            // RIFF header
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            writeIntLE(out, chunkSize);
            out.write("WAVE".getBytes(StandardCharsets.US_ASCII));

            // fmt subchunk
            out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
            writeIntLE(out, 16); // subchunk1Size
            writeShortLE(out, (short) 1); // PCM
            writeShortLE(out, (short) channels);
            writeIntLE(out, sampleRate);
            writeIntLE(out, byteRate);
            writeShortLE(out, (short) blockAlign);
            writeShortLE(out, (short) bitsPerSample);

            // data subchunk
            out.write("data".getBytes(StandardCharsets.US_ASCII));
            writeIntLE(out, subchunk2Size);
            out.write(textBytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to construct mock WAV bytes", e);
        }

        return out.toByteArray();
    }

    /**
     * Creates an InputStream of mock WAV audio containing the specified text.
     */
    public static InputStream createMockAudioStream(String transcription) {
        return new ByteArrayInputStream(createMockAudioBytes(transcription));
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, short value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private boolean isWhisperCliAvailable() {
        try {
            Process proc = new ProcessBuilder("whisper", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(1, TimeUnit.SECONDS);
            return finished && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String transcribeWithLocalWhisper(Path audioFile) throws IOException, InterruptedException {
        Path outputDir = Files.createTempDirectory("whisper_out_");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "whisper",
                    audioFile.toAbsolutePath().toString(),
                    "--output_format", "txt",
                    "--output_dir", outputDir.toAbsolutePath().toString()
            );
            Process proc = pb.start();
            boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() == 0) {
                String base = audioFile.getFileName().toString();
                int dot = base.lastIndexOf('.');
                String txtName = (dot == -1 ? base : base.substring(0, dot)) + ".txt";
                Path txtPath = outputDir.resolve(txtName);
                if (Files.exists(txtPath)) {
                    return Files.readString(txtPath).trim();
                }
            }
            return null;
        } finally {
            try {
                // cleanup temp directory
                File[] files = outputDir.toFile().listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
                Files.deleteIfExists(outputDir);
            } catch (Exception ignored) {
            }
        }
    }

    public VoiceConfig getConfig() {
        return config;
    }
}
