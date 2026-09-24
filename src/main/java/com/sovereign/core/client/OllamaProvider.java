package com.sovereign.core.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>OllamaProvider</b>
 *
 * <p>Zero-cost, fully offline LLM provider that calls a locally-running
 * <a href="https://ollama.com">Ollama</a> server at {@code http://localhost:11434}.
 * Ollama hosts open-weight models (Llama 3.2, Phi-4, Mistral, Gemma 3, etc.)
 * entirely on the user's machine — no API key, no subscription, no internet required.</p>
 *
 * <h3>One-time setup (free)</h3>
 * <pre>
 *   winget install Ollama.Ollama
 *   ollama pull phi4-mini          # 2.3 GB — fast on CPU
 *   ollama pull llama3.2           # 2 GB   — very capable
 * </pre>
 *
 * <p>Sovereign automatically probes {@code localhost:11434} at startup.
 * If Ollama is running, it is added to the LLM chain as a fallback after Gemini.
 * If Ollama is not installed, this provider is silently skipped.</p>
 *
 * <h3>Chain position</h3>
 * <pre>Gemini (free tier) → Ollama (local) → in-memory (deterministic)</pre>
 */
public class OllamaProvider {

    private static final Logger LOG = Logger.getLogger(OllamaProvider.class.getName());

    /** Ollama REST API base URL. */
    public static final String OLLAMA_BASE_URL = "http://localhost:11434";

    /** Chat completions endpoint (OpenAI-compatible). */
    private static final String CHAT_ENDPOINT = OLLAMA_BASE_URL + "/api/chat";

    /** Tags endpoint — used to probe whether Ollama is running. */
    private static final String TAGS_ENDPOINT = OLLAMA_BASE_URL + "/api/tags";

    /** Default model — changeable via {@code sovereign.ollama.model} JVM property. */
    public static final String DEFAULT_MODEL = "phi4-mini";

    private final String model;

    public OllamaProvider() {
        this(resolveModel());
    }

    public OllamaProvider(String model) {
        this.model = (model != null && !model.isBlank()) ? model.trim() : DEFAULT_MODEL;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Probes the local Ollama server to check if it is running.
     * Returns {@code true} if the server responds to the {@code /api/tags} endpoint
     * within 1 second. Non-throwing — returns {@code false} on any error.
     */
    public static boolean isAvailable() {
        try {
            URL url = URI.create(TAGS_ENDPOINT).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1_000);
            conn.setReadTimeout(1_000);
            int status = conn.getResponseCode();
            conn.disconnect();
            return status == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the resolved model name (from JVM property, env var, or default).
     */
    public static String resolveModel() {
        String prop = System.getProperty("sovereign.ollama.model");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("SOVEREIGN_OLLAMA_MODEL");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_MODEL;
    }

    /**
     * Sends a chat message to the local Ollama model and returns the response text.
     *
     * <p>Supports multi-turn: pass the full conversation history as {@code turns},
     * which will be serialised into Ollama's {@code messages[]} format.</p>
     *
     * @param systemPrompt  the JARVIS/Sovereign persona instruction
     * @param userMessage   the current user query
     * @param history       prior conversation turns for multi-turn context (may be empty)
     * @return the model's response, or {@code null} if Ollama is unreachable
     */
    public String chat(String systemPrompt, String userMessage,
                       List<com.sovereign.core.memory.ConversationTurn> history) {
        try {
            String requestBody = buildChatRequest(systemPrompt, userMessage, history);

            URL url = URI.create(CHAT_ENDPOINT).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(60_000); // local inference can be slow on CPU

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status == 200) {
                String responseJson = new String(
                        conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String text = extractContent(responseJson);
                if (text != null && !text.isBlank()) {
                    LOG.info("[OLLAMA] " + model + " responded (" + text.length() + " chars).");
                    return text.trim();
                }
                LOG.warning("[OLLAMA] 200 OK but no content in response.");
                return null;
            } else {
                LOG.warning("[OLLAMA] HTTP " + status + " from " + CHAT_ENDPOINT);
                return null;
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[OLLAMA] Chat error: " + e.getMessage());
            return null;
        }
    }

    /** Convenience overload — no conversation history. */
    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, java.util.Collections.emptyList());
    }

    public String getModel() { return model; }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Builds the Ollama {@code /api/chat} JSON request body.
     * Uses the OpenAI-compatible messages format that Ollama supports.
     */
    private String buildChatRequest(String systemPrompt, String userMessage,
                                     List<com.sovereign.core.memory.ConversationTurn> history) {
        StringBuilder messages = new StringBuilder("[");

        // System message
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.append("{\"role\":\"system\",\"content\":")
                    .append(jsonString(systemPrompt)).append("}");
        }

        // History turns
        if (history != null) {
            for (com.sovereign.core.memory.ConversationTurn turn : history) {
                if (messages.length() > 1) messages.append(",");
                String ollamaRole = "assistant".equals(turn.role()) ? "assistant" : "user";
                messages.append("{\"role\":\"").append(ollamaRole).append("\",\"content\":")
                        .append(jsonString(turn.content())).append("}");
            }
        }

        // Current user message
        if (messages.length() > 1) messages.append(",");
        messages.append("{\"role\":\"user\",\"content\":").append(jsonString(userMessage)).append("}");
        messages.append("]");

        return "{\"model\":\"" + model + "\","
                + "\"messages\":" + messages + ","
                + "\"stream\":false}";
    }

    /**
     * Extracts the {@code message.content} value from an Ollama chat response JSON.
     * Example response: {@code {"message":{"role":"assistant","content":"Hello!"},...}}
     */
    static String extractContent(String json) {
        if (json == null || json.isBlank()) return null;

        // Look for "content": "<value>" — handles both streaming and non-streaming
        int idx = json.indexOf("\"content\":");
        if (idx < 0) return null;
        int startQuote = json.indexOf('"', idx + 10);
        if (startQuote < 0) return null;
        int end = startQuote + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"' && json.charAt(end - 1) != '\\') break;
            end++;
        }
        if (end >= json.length()) return null;
        return json.substring(startQuote + 1, end)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim();
    }

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}
