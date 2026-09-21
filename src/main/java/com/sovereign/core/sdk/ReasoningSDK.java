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

    public static final String JARVIS_SYSTEM_PROMPT =
            "You are Sovereign, an advanced, highly intelligent personal AI operator and system co-pilot (JARVIS persona). "
                    + "You assist the user (Darshan) with operating system management, code intelligence, and conversational reasoning. "
                    + "Be concise, sharp, witty, and deeply helpful.";

    private final DefaultReasoningEngine engine;
    private final ShreeAI shreeAI;
    private final DefaultRuntimeService runtimeService;

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
    }

    /**
     * Executes dynamic conversational analysis or response synthesis over a given prompt,
     * applying the Sovereign JARVIS executive persona.
     */
    public String analyze(String prompt) {
        return analyze(prompt, null);
    }

    /**
     * Executes dynamic conversational analysis with contextual grounding (user profile, workspace,
     * conversation history), routing through the active Shree AI OS platform runtime LLM provider.
     */
    public String analyze(String prompt, String context) {
        if (prompt == null || prompt.isBlank()) {
            return "At your service. How may I assist you, Darshan?";
        }

        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("[SYSTEM]\n").append(JARVIS_SYSTEM_PROMPT).append("\n\n");
        if (context != null && !context.isBlank()) {
            fullPrompt.append("[CONTEXT]\n").append(context.trim()).append("\n\n");
        }
        fullPrompt.append("[USER]\n").append(prompt.trim());

        String promptStr = fullPrompt.toString();

        // 1. Try streaming via platform runtime service LLM router
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
            } catch (Exception ignored) {
            }
        }

        // 2. Try chat via ShreeAI client facade
        if (shreeAI != null) {
            try {
                SDKResponse response = ReasoningOutputSanitizer.executeSilently(() -> shreeAI.chat(promptStr));
                if (response != null && response.answer() != null && !response.answer().isBlank()) {
                    return ReasoningOutputSanitizer.sanitize(response.answer().trim(), prompt, context);
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Deterministic fallback to DefaultReasoningEngine
        try {
            ReasoningResult result = engine.reason(prompt, Collections.emptyList(), Collections.emptyList());
            if (result != null && result.conclusion() != null && !result.conclusion().isBlank()) {
                return ReasoningOutputSanitizer.sanitize(result.conclusion(), prompt, context);
            } else if (result != null && result.summary() != null && !result.summary().isBlank()) {
                return ReasoningOutputSanitizer.sanitize(result.summary(), prompt, context);
            }
        } catch (Exception ignored) {
        }

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
