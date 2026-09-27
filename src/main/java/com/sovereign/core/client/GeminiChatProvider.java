package com.sovereign.core.client;

import com.sovereign.core.memory.ConversationContextWindow;
import com.sovereign.core.memory.ConversationTurn;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>GeminiChatProvider</b>
 *
 * <p>Zero-cost conversational provider that calls Google Gemini's free tier
 * ({@code generativelanguage.googleapis.com}) directly over REST, transmitting the
 * <em>full structured multi-turn conversation</em> as a Gemini {@code contents[]}
 * array — role-mapped ({@code user} / {@code model}) — together with a
 * {@code system_instruction} block.</p>
 *
 * <p>This is what makes Sovereign's chat truly multi-turn: instead of flattening the
 * dialogue into a single text prompt, the model receives each prior exchange as a
 * distinct, role-tagged turn and therefore maintains real conversational coherence
 * (pronoun resolution, follow-up questions, topic continuity).</p>
 *
 * <h3>Free tier (no credit card)</h3>
 * <pre>
 *   Model      : gemini-2.5-flash
 *   Rate limit : 15 requests/minute
 *   Daily      : 1,500 requests/day (~1 million tokens)
 *   Cost       : $0.00
 * </pre>
 *
 * <h3>Chain position</h3>
 * <pre>Gemini (free tier) → Shree AI OS runtime → Ollama (local, free) → in-memory (deterministic)</pre>
 *
 * <h3>Failure behaviour</h3>
 * <p>Never throws. Returns {@code null} when the call cannot be completed so the caller
 * falls through to the next tier, and {@link #RATE_LIMITED_MARKER} on HTTP 429 so the
 * caller can immediately route to the local Ollama fallback without wasting the free quota.</p>
 */
public class GeminiChatProvider {

    private static final Logger LOG = Logger.getLogger(GeminiChatProvider.class.getName());

    /** Gemini generateContent REST endpoint template: {@code %s} = model, {@code %s} = API key. */
    public static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    /** Default free-tier model — overridable via {@code -Dsovereign.llm.model=<name>}. */
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";

    /** Sentinel returned when Gemini answers HTTP 429 (free-tier rate limit exhausted). */
    public static final String RATE_LIMITED_MARKER = "__GEMINI_RATE_LIMITED__";

    private final String apiKey;
    private final String model;

    public GeminiChatProvider(String apiKey) {
        this(apiKey, resolveModel());
    }

    public GeminiChatProvider(String apiKey, String model) {
        this.apiKey = (apiKey != null) ? apiKey.trim() : null;
        this.model = (model != null && !model.isBlank()) ? model.trim() : DEFAULT_MODEL;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Resolves the chat model from JVM property, environment variable, or default.
     * Precedence: {@code -Dsovereign.llm.model} → {@code SOVEREIGN_LLM_MODEL} → {@link #DEFAULT_MODEL}.
     */
    public static String resolveModel() {
        String prop = System.getProperty("sovereign.llm.model");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("SOVEREIGN_LLM_MODEL");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_MODEL;
    }

    /** Returns {@code true} when a non-blank API key is configured. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getModel() {
        return model;
    }

    /**
     * Sends a multi-turn conversational request to Gemini.
     *
     * @param systemPrompt JARVIS system instruction (may be null)
     * @param userMessage  the current user message
     * @param turns        full conversation history, oldest first (may be null/empty)
     * @return the model's reply text, {@link #RATE_LIMITED_MARKER} on HTTP 429,
     *         or {@code null} when unavailable (caller falls through to the next tier)
     */
    public String chat(String systemPrompt, String userMessage, List<ConversationTurn> turns) {
        if (!isConfigured() || userMessage == null || userMessage.isBlank()) {
            return null;
        }

        String requestBody = buildRequestJson(systemPrompt, userMessage, turns);

        try {
            String urlStr = String.format(ENDPOINT_TEMPLATE, model, apiKey);
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(30_000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();

            if (status == 200) {
                String responseBody = new String(
                        conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String text = extractText(responseBody);
                if (text != null && !text.isBlank()) {
                    LOG.info("[SOVEREIGN] Gemini (" + model + ") multi-turn reply: "
                            + text.length() + " chars.");
                    return text;
                }
                LOG.warning("[SOVEREIGN] Gemini (" + model + ") returned 200 but no text part found.");
                return null;

            } else if (status == 429) {
                LOG.warning("[SOVEREIGN] Gemini free-tier rate limit reached (HTTP 429). "
                        + "Routing to local Ollama fallback — zero cost preserved.");
                return RATE_LIMITED_MARKER;

            } else if (status == 404) {
                LOG.warning("[SOVEREIGN] Gemini model '" + model + "' not found (HTTP 404). "
                        + "Set -Dsovereign.llm.model=<valid-model> (e.g. gemini-2.5-flash).");
                return null;

            } else {
                LOG.warning("[SOVEREIGN] Gemini (" + model + ") HTTP " + status + ": " + readErrorBody(conn));
                return null;
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[SOVEREIGN] Gemini (" + model + ") chat error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds the Gemini {@code generateContent} JSON request body.
     *
     * <p>Emits {@code system_instruction} plus a role-mapped {@code contents[]} array in
     * which assistant turns are serialized as {@code model} (Gemini's required role name).
     * The current user message is appended last.</p>
     *
     * <p>Exposed for deterministic unit testing of the request serialization
     * (no network access required).</p>
     */
    public String buildRequestJson(String systemPrompt, String userMessage, List<ConversationTurn> turns) {
        List<ConversationTurn> full = new ArrayList<>();
        if (turns != null) {
            full.addAll(turns);
        }

        // The REPL records the current user turn before calling analyze(). Only
        // append it here when the caller supplied prior history without it.
        boolean currentAlreadyPresent = !full.isEmpty();
        if (currentAlreadyPresent) {
            ConversationTurn last = full.get(full.size() - 1);
            currentAlreadyPresent = last != null
                    && "user".equals(last.role())
                    && last.content() != null
                    && last.content().trim().equals(userMessage == null ? "" : userMessage.trim());
        }
        if (!currentAlreadyPresent && userMessage != null && !userMessage.isBlank()) {
            full.add(ConversationTurn.user(userMessage));
        }

        String contents = ConversationContextWindow.toGeminiContentsJson(normalizeHistory(full));

        StringBuilder body = new StringBuilder();
        body.append("{\n");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.append("  \"system_instruction\": {\n")
                .append("    \"parts\": [{\"text\": ").append(jsonString(systemPrompt)).append("}]\n")
                .append("  },\n");
        }
        body.append("  \"contents\": ").append(contents).append(",\n");
        body.append("  \"generationConfig\": {\"temperature\": 0.7, \"maxOutputTokens\": 2048}\n");
        body.append("}");
        return body.toString();
    }

    /**
     * Normalizes a turn list into a Gemini-legal multi-turn {@code contents[]} sequence.
     *
     * <p>Gemini rejects multi-turn payloads that do not alternate roles, so this
     * drops leading assistant turns (the first turn must be {@code user}) and merges
     * consecutive same-role turns into a single entry separated by a newline.</p>
     *
     * <p>Exposed for deterministic unit testing (no network access required).</p>
     */
    public static List<ConversationTurn> normalizeHistory(List<ConversationTurn> turns) {
        List<ConversationTurn> out = new ArrayList<>();
        if (turns == null) return out;

        for (ConversationTurn t : turns) {
            if (t == null || t.content() == null || t.content().isBlank()) continue;
            if (out.isEmpty() && !"user".equals(t.role())) continue; // first turn must be user

            if (!out.isEmpty() && out.get(out.size() - 1).role().equals(t.role())) {
                ConversationTurn prev = out.remove(out.size() - 1);
                out.add(new ConversationTurn(prev.role(),
                        prev.content() + "\n" + t.content(), prev.timestamp()));
            } else {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Extracts the first {@code text} part from a Gemini generateContent JSON response.
     * Uses simple substring parsing to avoid requiring a JSON library dependency.
     *
     * <p>Exposed for deterministic unit testing (no network access required).</p>
     */
    public static String extractText(String json) {
        if (json == null || json.isBlank()) return null;
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
        return raw.replace("\\n", "\n")
                  .replace("\\t", "\t")
                  .replace("\\\"", "\"")
                  .replace("\\\\", "\\")
                  .trim();
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private static String readErrorBody(HttpURLConnection conn) {
        try {
            byte[] bytes = conn.getErrorStream() != null
                    ? conn.getErrorStream().readAllBytes() : new byte[0];
            String body = new String(bytes, StandardCharsets.UTF_8);
            return body.substring(0, Math.min(300, body.length()));
        } catch (Exception e) {
            return "(unavailable)";
        }
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

