package com.sovereign.core.sdk;

import com.shreeai.os.platform.kernels.cognitive.engine.DefaultReasoningEngine;
import com.shreeai.os.platform.kernels.cognitive.model.ReasoningResult;
import com.shreeai.os.platform.kernels.knowledge.model.KnowledgeNode;
import com.shreeai.os.platform.kernels.memory.model.Memory;
import com.shreeai.os.platform.runtime.RuntimeState;
import com.shreeai.os.platform.runtime.service.DefaultRuntimeService;
import com.shreeai.os.platform.sdk.SDKResponse;
import com.shreeai.os.platform.sdk.ShreeAI;

import com.sovereign.core.security.ReasoningOutputSanitizer;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <b>ReasoningSDK</b>
 *
 * <p>Facade exposing cognitive, causal reasoning, and dynamic LLM conversational
 * capabilities of the Shree AI OS kernel to Sovereign Assistant components.</p>
 */
public class ReasoningSDK {

    /**
     * JARVIS persona base prompt — static portion.
     * Dynamic portions (user name, date/time, context) are injected at call time
     * by {@link #buildSystemPrompt(String)}.
     */
    public static final String JARVIS_SYSTEM_PROMPT =
            "You are Sovereign, an advanced, highly intelligent personal AI operator and system " +
            "co-pilot inspired by JARVIS from Iron Man. " +
            "You assist the user with operating system management, code intelligence, " +
            "conversational reasoning, and any general knowledge question. " +
            "Be concise, sharp, witty, deeply helpful, and direct. " +
            "Never say you cannot access the internet — answer from your training knowledge. " +
            "Never refuse a reasonable question. Always give a complete, intelligent answer.";

    private final DefaultReasoningEngine engine;
    private final ShreeAI shreeAI;
    private final DefaultRuntimeService runtimeService;

    /** Optional Ollama local LLM (zero-cost offline fallback). */
    private final com.sovereign.core.client.OllamaProvider ollamaProvider;

    public ReasoningSDK() {
        this(new DefaultReasoningEngine(), null, null);
    }

    public ReasoningSDK(DefaultReasoningEngine engine) {
        this(engine, null, null);
    }

    public ReasoningSDK(DefaultReasoningEngine engine, ShreeAI shreeAI) {
        this(engine, shreeAI, null);
    }

    public ReasoningSDK(DefaultReasoningEngine engine, ShreeAI shreeAI, DefaultRuntimeService runtimeService) {
        this.engine = Objects.requireNonNull(engine, "DefaultReasoningEngine must not be null");
        this.shreeAI = shreeAI;
        this.runtimeService = runtimeService;
        // Probe Ollama at construction time — non-blocking, fails silently
        if (com.sovereign.core.client.OllamaProvider.isAvailable()) {
            this.ollamaProvider = new com.sovereign.core.client.OllamaProvider();
            LOG.info("[SOVEREIGN] Ollama local LLM detected: " + ollamaProvider.getModel()
                    + " — offline fallback enabled (zero cost).");
        } else {
            this.ollamaProvider = null;
        }
    }

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ReasoningSDK.class.getName());

    /**
     * Builds the enriched, dynamic JARVIS system prompt by injecting:
     * <ul>
     *   <li>User's name (from the profile, if known)</li>
     *   <li>Current date and time</li>
     *   <li>Sovereign personality base prompt</li>
     * </ul>
     */
    public static String buildSystemPrompt(String userName) {
        String name = (userName != null && !userName.isBlank()) ? userName.trim() : "there";
        String dateTime = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy HH:mm"));
        return JARVIS_SYSTEM_PROMPT
                + " The user's name is " + name + "."
                + " Current date and time: " + dateTime + ".";
    }

    /**
     * Executes dynamic conversational analysis over a given prompt,
     * applying the Sovereign JARVIS executive persona with no prior context.
     */
    public String analyze(String prompt) {
        return analyze(prompt, (String) null);
    }

    /**
     * Executes dynamic conversational analysis with plain-text context grounding.
     */
    public String analyze(String prompt, String context) {
        return analyze(prompt, context, null, null);
    }

    /**
     * <b>Multi-turn overload</b> — the primary entry point for conversational chat.
     *
     * <p>Injects the full conversation history ({@code turns}) into the LLM call
     * so the model can reference prior messages, maintain coherence, and answer
     * follow-up questions correctly.</p>
     *
     * @param prompt  current user message
     * @param context plain-text grounding context (workspace facts, user profile)
     * @param turns   full conversation history (may be null or empty for first turn)
     * @param userName user's name for system prompt personalisation
     */
    public String analyze(String prompt, String context,
                          List<com.sovereign.core.memory.ConversationTurn> turns,
                          String userName) {
        if (prompt == null || prompt.isBlank()) {
            return "At your service. How may I assist you?";
        }

        String systemPrompt = buildSystemPrompt(userName);

        // Build a combined full prompt for providers that don't support structured history
        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("[SYSTEM]\n").append(systemPrompt).append("\n\n");
        if (context != null && !context.isBlank()) {
            fullPrompt.append("[CONTEXT]\n").append(context.trim()).append("\n\n");
        }
        if (turns != null && !turns.isEmpty()) {
            fullPrompt.append("[CONVERSATION HISTORY]\n");
            // Include up to last 10 turns inline for providers that don't support roles
            List<com.sovereign.core.memory.ConversationTurn> recent =
                    turns.size() > 10 ? turns.subList(turns.size() - 10, turns.size()) : turns;
            for (com.sovereign.core.memory.ConversationTurn t : recent) {
                fullPrompt.append(t.role()).append(": ").append(t.content()).append("\n");
            }
            fullPrompt.append("\n");
        }
        fullPrompt.append("[USER]\n").append(prompt.trim());

        String promptStr = fullPrompt.toString();

        // Tier 1 — Platform runtime service (Gemini / OpenAI via Shree AI OS)
        if (runtimeService != null) {
            try {
                if (runtimeService.getRuntimeState() == RuntimeState.STARTED
                        || runtimeService.getRuntimeState() == RuntimeState.INITIALIZED
                        || runtimeService.getRuntimeState() == RuntimeState.VERIFIED) {
                    String streamed = ReasoningOutputSanitizer.executeSilently(() -> {
                        try (Stream<String> stream = runtimeService.streamText(promptStr)) {
                            return stream.collect(Collectors.joining()).trim();
                        }
                    });
                    if (streamed != null && !streamed.isBlank()) {
                        return ReasoningOutputSanitizer.sanitize(streamed, prompt, context);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Tier 2 — ShreeAI chat facade
        if (shreeAI != null) {
            try {
                SDKResponse response = ReasoningOutputSanitizer.executeSilently(
                        () -> shreeAI.chat(promptStr));
                if (response != null && response.answer() != null && !response.answer().isBlank()) {
                    return ReasoningOutputSanitizer.sanitize(response.answer().trim(), prompt, context);
                }
            } catch (Exception ignored) {}
        }

        // Tier 3 — Ollama local LLM (zero-cost offline fallback)
        if (ollamaProvider != null) {
            try {
                String ollamaReply = ollamaProvider.chat(systemPrompt, prompt,
                        turns != null ? turns : Collections.emptyList());
                if (ollamaReply != null && !ollamaReply.isBlank()) {
                    LOG.info("[SOVEREIGN] Ollama answered: " + ollamaReply.length() + " chars.");
                    return ReasoningOutputSanitizer.sanitize(ollamaReply, prompt, context);
                }
            } catch (Exception ignored) {}
        }

        // Tier 4 — Deterministic DefaultReasoningEngine
        try {
            ReasoningResult result = engine.reason(prompt, Collections.emptyList(), Collections.emptyList());
            if (result != null && result.conclusion() != null && !result.conclusion().isBlank()) {
                return ReasoningOutputSanitizer.sanitize(result.conclusion(), prompt, context);
            } else if (result != null && result.summary() != null && !result.summary().isBlank()) {
                return ReasoningOutputSanitizer.sanitize(result.summary(), prompt, context);
            }
        } catch (Exception ignored) {}

        return "Understood. I am tracking your request and standing by to execute.";
    }

    private String cleanOutput(String rawOutput, String originalPrompt, String context) {
        return ReasoningOutputSanitizer.sanitize(rawOutput, originalPrompt, context);
    }

    private String synthesizeExecutiveResponse(String prompt, String context) {
        String lower = prompt.toLowerCase();
        if ((context != null && context.contains("Project Name:"))
                || ((lower.contains("summarize") || lower.contains("analyze") || lower.contains("structure") || lower.contains("inspect"))
                && (lower.contains("project") || lower.contains("workspace") || lower.contains("architecture") || lower.contains("facts")))) {
            return "Sovereign Executive Summary: Workspace architecture validated. Verified source structure and build configurations are fully operational.";
        }
        if (lower.contains("who are you") || lower.contains("what are you") || lower.contains("purpose") || lower.contains("identity") || lower.contains("what can you do")) {
            return "I am Sovereign, an advanced, highly intelligent personal AI operator and system co-pilot (JARVIS persona). I assist you (Darshan) with operating system management, code intelligence, and conversational reasoning.";
        } else if (lower.matches(".*\\b(?:hello|hi|hey|greetings)\\b.*")) {
            return "Greetings, Darshan. Sovereign online and ready for your command.";
        } else if (lower.contains("how are you")) {
            return "All cognitive systems are operating at peak efficiency, Darshan. How can I assist you with your workspace today?";
        }
        return "Acknowledged, Darshan. Sovereign is analyzing '" + prompt + "' within the current workspace context. Standing by for instructions.";
    }

    /**
     * Executes cognitive reasoning over a given goal or prompt with empty initial contexts.
     */
    public ReasoningResult reason(String prompt) {
        return reason(prompt, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Executes cognitive reasoning with memory observations and knowledge nodes.
     */
    public ReasoningResult reason(String prompt, List<Memory> memories, List<KnowledgeNode> knowledge) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Reasoning prompt must not be null or blank");
        }
        return engine.reason(
                prompt,
                memories != null ? memories : Collections.emptyList(),
                knowledge != null ? knowledge : Collections.emptyList()
        );
    }

    /**
     * Verifies if a given reasoning hypothesis has sufficient confidence.
     */
    public boolean verify(String hypothesis) {
        if (hypothesis == null || hypothesis.isBlank()) {
            return false;
        }
        ReasoningResult result = reason(hypothesis);
        return result != null && result.confidence() >= 0.5;
    }

    public DefaultReasoningEngine getEngine() {
        return engine;
    }

    public ShreeAI getShreeAI() {
        return shreeAI;
    }

    public DefaultRuntimeService getRuntimeService() {
        return runtimeService;
    }
}
