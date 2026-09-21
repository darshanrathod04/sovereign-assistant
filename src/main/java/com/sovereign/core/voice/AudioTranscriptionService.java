package com.sovereign.core.voice;

import com.sovereign.core.config.ProviderConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>AudioTranscriptionService</b>
 *
 * <p>Multi-tier audio transcription service that converts raw WAV/PCM bytes into
 * human-readable text for Sovereign's voice pipeline. Implements a three-tier cascade:</p>
 *
 * <ol>
 *   <li><b>Gemini Multimodal STT</b> — sends Base64-encoded WAV inline to the
 *       Gemini {@code generateContent} API. Used when {@code GEMINI_API_KEY} is present.</li>
 *   <li><b>Local Whisper CLI</b> — invokes {@code whisper} or {@code faster-whisper}
 *       if installed on the host.</li>
 *   <li><b>Windows SAPI (offline)</b> — uses PowerShell
 *       {@code System.Speech.Recognition.SpeechRecognitionEngine} as a local
 *       offline fallback when running on Windows.</li>
 * </ol>
 *
 * <p>All tiers return {@code "[SILENCE]"} for audio that is silent or unintelligible,
 * and an empty string if no tier can produce a result (triggering the legacy heuristic
 * fallback in {@link SpeechToTextAdapter}).</p>
 */
public class AudioTranscriptionService {

    /** Sentinel returned by any tier when audio is silent or unintelligible. */
    public static final String SILENCE_MARKER = "[SILENCE]";

    private static final Logger LOG = Logger.getLogger(AudioTranscriptionService.class.getName());

    /** Gemini endpoint for generateContent (REST v1beta). */
    private static final String GEMINI_ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=%s";

    /** Transcription system instruction sent to Gemini. */
    private static final String STT_INSTRUCTION =
            "Listen to this audio clip. Transcribe exactly what the user said in natural text. " +
            "Return ONLY the transcribed text, nothing else. " +
            "If the audio is silent or unintelligible, return '[SILENCE]'.";

    private final ProviderConfig config;

    public AudioTranscriptionService() {
        this(ProviderConfig.load());
    }

    public AudioTranscriptionService(ProviderConfig config) {
        this.config = config != null ? config : ProviderConfig.of(null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Transcribes WAV audio bytes using the best available tier.
     *
     * @param wavBytes raw WAV bytes (must include RIFF header)
     * @return transcribed text, {@value #SILENCE_MARKER} for silence/unintelligible audio,
     *         or {@code null} if all tiers fail (signals fallback to heuristic)
     */
    public String transcribeWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) {
            return null; // too short to be a valid WAV — let caller handle
        }

        // Tier 1 — Gemini Multimodal STT (online, highest quality)
        if (config.hasGeminiKey()) {
            String result = transcribeWithGemini(wavBytes, config.getGeminiApiKey());
            if (result != null) {
                return sanitize(result);
            }
            LOG.warning("[STT] Gemini transcription failed — falling through to Whisper/SAPI.");
        }

        // Tier 2 — Local Whisper CLI
        if (isWhisperAvailable()) {
            String result = transcribeWithWhisper(wavBytes);
            if (result != null && !result.isBlank()) {
                return sanitize(result);
            }
        }

        // Tier 3 — Windows SAPI offline (PowerShell System.Speech)
        if (isWindows()) {
            String result = transcribeWithWindowsSapi(wavBytes);
            if (result != null && !result.isBlank()) {
                return sanitize(result);
            }
        }

        return null; // all tiers exhausted — caller uses legacy heuristic
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tier 1: Gemini Multimodal STT
    // ─────────────────────────────────────────────────────────────────────────

    private String transcribeWithGemini(byte[] wavBytes, String apiKey) {
        try {
            String base64Audio = Base64.getEncoder().encodeToString(wavBytes);

            // Build JSON request body — inline_data with audio/wav MIME type
            String requestBody = buildGeminiRequest(base64Audio);

            URL url = URI.create(String.format(GEMINI_ENDPOINT_TEMPLATE, apiKey)).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status == 200) {
                String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return extractGeminiText(responseBody);
            } else {
                String errorBody = "";
                try {
                    errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                LOG.warning("[STT] Gemini HTTP " + status + ": " + errorBody.substring(0, Math.min(200, errorBody.length())));
                return null;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[STT] Gemini transcription error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds the Gemini generateContent JSON body with inline audio data.
     * Uses system instruction + inline_data (Base64 WAV).
     */
    private static String buildGeminiRequest(String base64Audio) {
        return "{\n" +
               "  \"system_instruction\": {\n" +
               "    \"parts\": [{\"text\": " + jsonString(STT_INSTRUCTION) + "}]\n" +
               "  },\n" +
               "  \"contents\": [{\n" +
               "    \"role\": \"user\",\n" +
               "    \"parts\": [{\n" +
               "      \"inline_data\": {\n" +
               "        \"mime_type\": \"audio/wav\",\n" +
               "        \"data\": \"" + base64Audio + "\"\n" +
               "      }\n" +
               "    }]\n" +
               "  }]\n" +
               "}";
    }

    /**
     * Extracts the {@code text} value from a Gemini generateContent JSON response.
     * Uses simple substring parsing to avoid requiring a JSON library dependency.
     */
    static String extractGeminiText(String json) {
        if (json == null || json.isBlank()) return null;
        // Look for "text": "<value>" — Gemini response structure
        int textIdx = json.indexOf("\"text\":");
        if (textIdx < 0) return null;
        int startQuote = json.indexOf('"', textIdx + 7);
        if (startQuote < 0) return null;
        int endQuote = startQuote + 1;
        while (endQuote < json.length()) {
            char c = json.charAt(endQuote);
            if (c == '"' && json.charAt(endQuote - 1) != '\\') break;
            endQuote++;
        }
        if (endQuote >= json.length()) return null;
        String raw = json.substring(startQuote + 1, endQuote);
        // Unescape basic JSON string escapes
        return raw.replace("\\n", "\n")
                  .replace("\\t", "\t")
                  .replace("\\\"", "\"")
                  .replace("\\\\", "\\")
                  .trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tier 2: Local Whisper CLI
    // ─────────────────────────────────────────────────────────────────────────

    boolean isWhisperAvailable() {
        return probeCommand("whisper") || probeCommand("faster-whisper");
    }

    private String transcribeWithWhisper(byte[] wavBytes) {
        Path tempWav = null;
        Path tempOut = null;
        try {
            tempWav = Files.createTempFile("sovereign_stt_", ".wav");
            Files.write(tempWav, wavBytes);
            tempOut = Files.createTempDirectory("sovereign_whisper_");

            String whisperBin = probeCommand("faster-whisper") ? "faster-whisper" : "whisper";
            ProcessBuilder pb = new ProcessBuilder(
                    whisperBin,
                    tempWav.toAbsolutePath().toString(),
                    "--output_format", "txt",
                    "--output_dir", tempOut.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean done = proc.waitFor(20, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() == 0) {
                String baseName = tempWav.getFileName().toString();
                int dot = baseName.lastIndexOf('.');
                String txtName = (dot > 0 ? baseName.substring(0, dot) : baseName) + ".txt";
                Path txtFile = tempOut.resolve(txtName);
                if (Files.exists(txtFile)) {
                    return Files.readString(txtFile).trim();
                }
            }
            return null;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[STT] Whisper error: " + e.getMessage());
            return null;
        } finally {
            silentDelete(tempWav);
            silentDeleteDir(tempOut);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tier 3: Windows SAPI (PowerShell System.Speech.Recognition)
    // ─────────────────────────────────────────────────────────────────────────

    private String transcribeWithWindowsSapi(byte[] wavBytes) {
        Path tempWav = null;
        try {
            tempWav = Files.createTempFile("sovereign_sapi_", ".wav");
            Files.write(tempWav, wavBytes);

            String psPath = tempWav.toAbsolutePath().toString().replace("\\", "\\\\");
            // PowerShell script: load WAV into SpeechRecognitionEngine and recognize once
            String psScript = String.format(
                    "Add-Type -AssemblyName System.Speech; " +
                    "$engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine; " +
                    "$engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar)); " +
                    "$engine.SetInputToWaveFile('%s'); " +
                    "$result = $engine.Recognize(); " +
                    "if ($result) { Write-Output $result.Text } else { Write-Output '[SILENCE]' }",
                    psPath
            );

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", psScript
            );
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            boolean done = proc.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() == 0) {
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return out.isBlank() ? SILENCE_MARKER : out;
            }
            return null;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[STT] Windows SAPI error: " + e.getMessage());
            return null;
        } finally {
            silentDelete(tempWav);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String sanitize(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isBlank()) return SILENCE_MARKER;
        return t;
    }

    private static boolean probeCommand(String cmd) {
        try {
            Process proc = new ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean done = proc.waitFor(1, TimeUnit.SECONDS);
            return done && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void silentDelete(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        }
    }

    private static void silentDeleteDir(Path dir) {
        if (dir == null) return;
        try {
            java.io.File[] files = dir.toFile().listFiles();
            if (files != null) {
                for (java.io.File f : files) f.delete();
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {}
    }

    /** Minimal JSON string escaping for the Gemini request body. */
    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}
