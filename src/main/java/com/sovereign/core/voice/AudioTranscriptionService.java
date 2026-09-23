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

    /**
     * Sentinel returned when Gemini replied with HTTP 429 (Too Many Requests).
     * The caller (REPL runner) should back off before retrying.
     */
    public static final String RATE_LIMITED_MARKER = "[RATE_LIMITED]";

    private static final Logger LOG = Logger.getLogger(AudioTranscriptionService.class.getName());

    /**
     * Default Gemini model for audio transcription.
     * Priority: {@code sovereign.stt.model} JVM property →
     *           {@code SOVEREIGN_STT_MODEL} env var → {@code "gemini-2.5-flash"}.
     *
     * <p>Set {@code -Dsovereign.stt.model=gemini-3.6-flash} (or any active model identifier
     * visible in the Shree AI OS runtime) to override without recompiling.</p>
     */
    private static final String DEFAULT_STT_MODEL = "gemini-2.5-flash";

    /**
     * Gemini generateContent REST endpoint template.
     * {@code %s} = model name, second {@code %s} = API key.
     */
    private static final String GEMINI_ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    /**
     * Multilingual STT system instruction sent to Gemini.
     * Handles English, Hindi, Hinglish, and Indian-English accents accurately.
     */
    private static final String STT_INSTRUCTION =
            "You are an expert speech-to-text transcriber. " +
            "Transcribe the following audio accurately. " +
            "The speaker may speak in English, Hindi, or Hinglish (Indian English accent with Hindi words mixed in). " +
            "Handle regional Indian accents gracefully. " +
            "Output ONLY the verbatim transcription — no punctuation commentary, no labels, no explanation. " +
            "If the audio is pure background noise or silence, return '[SILENCE]'.";

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
     * Resolves the Gemini STT model name at runtime:
     * <ol>
     *   <li>{@code sovereign.stt.model} JVM system property</li>
     *   <li>{@code SOVEREIGN_STT_MODEL} environment variable</li>
     *   <li>{@code shree.llm.gemini.model} JVM system property (Shree AI OS runtime)</li>
     *   <li>{@link #DEFAULT_STT_MODEL} ({@value #DEFAULT_STT_MODEL})</li>
     * </ol>
     */
    static String resolveModel() {
        String prop = System.getProperty("sovereign.stt.model");
        if (prop != null && !prop.isBlank()) return prop.trim();

        String env = System.getenv("SOVEREIGN_STT_MODEL");
        if (env != null && !env.isBlank()) return env.trim();

        String shreeModel = System.getProperty("shree.llm.gemini.model");
        if (shreeModel != null && !shreeModel.isBlank()) return shreeModel.trim();

        return DEFAULT_STT_MODEL;
    }

    /**
     * Transcribes WAV audio bytes using the best available tier.
     *
     * @param wavBytes raw WAV bytes (must include RIFF header)
     * @return transcribed text, {@value #SILENCE_MARKER} for silence/unintelligible audio,
     *         {@value #RATE_LIMITED_MARKER} if Gemini returned HTTP 429,
     *         or {@code null} if all tiers fail (signals fallback to heuristic)
     */
    public String transcribeWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) {
            return null; // too short to be a valid WAV — let caller handle
        }

        // Tier 1 — Gemini Multimodal STT (online, highest quality)
        if (config.hasGeminiKey()) {
            String result = transcribeWithGemini(wavBytes, config.getGeminiApiKey());
            if (RATE_LIMITED_MARKER.equals(result)) {
                return RATE_LIMITED_MARKER; // propagate to caller for backoff
            }
            if (result != null) {
                return sanitize(result);
            }
            // Gemini key is present but all attempts failed.
            // Deliberately do NOT fall through to SAPI: Windows SAPI dictation produces
            // phonetic nonsense on non-US/Indian accents ("Ernest ward has a lot").
            // Return [SILENCE] so the ambient loop skips this turn cleanly.
            LOG.warning("[STT] Gemini transcription failed with key present — returning [SILENCE] to protect downstream.");
            return SILENCE_MARKER;
        }

        // Tier 2 — Local Whisper CLI (only when Gemini key is absent)
        if (isWhisperAvailable()) {
            String result = transcribeWithWhisper(wavBytes);
            if (result != null && !result.isBlank()) {
                return sanitize(result);
            }
        }

        // Tier 3 — Windows SAPI offline (PowerShell System.Speech)
        // ONLY reached when no Gemini key is present (no cloud STT configured at all).
        // When Gemini is configured, SAPI is bypassed unconditionally — see above.
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

    /**
     * Calls Gemini generateContent using the model resolved by {@link #resolveModel()}.
     * <ul>
     *   <li>HTTP 200  → parse and return transcript</li>
     *   <li>HTTP 404  → model not found; logs actionable hint to set {@code sovereign.stt.model}</li>
     *   <li>HTTP 429  → rate limited; returns {@link #RATE_LIMITED_MARKER} immediately</li>
     *   <li>Other 4xx/5xx → logs error body and returns {@code null}</li>
     * </ul>
     */
    private String transcribeWithGemini(byte[] wavBytes, String apiKey) {
        // Resolve the active model at call-time so JVM property overrides take effect dynamically
        String model = resolveModel();
        LOG.info("[STT] Using Gemini model: " + model + " (override via -Dsovereign.stt.model=<name>)");

        // Base64-encode — standard RFC 4648, no line breaks
        String base64Audio = Base64.getEncoder().encodeToString(wavBytes);
        String requestBody = buildGeminiRequest(base64Audio);

        try {
            String urlStr = String.format(GEMINI_ENDPOINT_TEMPLATE, model, apiKey);
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();

            if (status == 200) {
                String responseBody = new String(
                        conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String text = extractGeminiText(responseBody);
                if (text != null) {
                    LOG.info("[STT] Gemini (" + model + ") transcribed successfully.");
                    return text;
                }
                LOG.warning("[STT] Gemini (" + model + ") returned 200 but no text found in response.");
                return null;

            } else if (status == 404) {
                LOG.warning("[STT] Gemini model '" + model + "' not found (HTTP 404). "
                        + "Set -Dsovereign.stt.model=<valid-model> or SOVEREIGN_STT_MODEL env var "
                        + "to override (e.g. gemini-2.5-flash, gemini-2.0-flash).");
                return null;

            } else if (status == 429) {
                LOG.warning("[STT] Gemini rate limited (HTTP 429). Caller will back off.");
                return RATE_LIMITED_MARKER;

            } else {
                String errorBody = readErrorBody(conn);
                LOG.warning("[STT] Gemini (" + model + ") HTTP " + status + ": "
                        + errorBody.substring(0, Math.min(300, errorBody.length())));
                return null;
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[STT] Gemini (" + model + ") error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds the Gemini generateContent JSON body with inline audio data.
     * Uses system_instruction + inline_data (Base64 WAV, audio/wav MIME type).
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

    /** Safely reads the error body from an HttpURLConnection without throwing. */
    private static String readErrorBody(HttpURLConnection conn) {
        try {
            java.io.InputStream errStream = conn.getErrorStream();
            if (errStream == null) return "";
            return new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }
}
