package com.sovereign.core.sdk;

import com.shreeai.os.platform.kernels.cognitive.engine.DefaultReasoningEngine;
import com.shreeai.os.platform.kernels.cognitive.model.ReasoningResult;
import com.shreeai.os.platform.kernels.knowledge.model.KnowledgeNode;
import com.shreeai.os.platform.kernels.memory.model.Memory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * <b>ReasoningSDK</b>
 *
 * <p>Facade exposing cognitive and causal reasoning capabilities of the Shree AI OS
 * kernel to Sovereign Assistant components.</p>
 */
public class ReasoningSDK {

    private final DefaultReasoningEngine engine;

    public ReasoningSDK() {
        this(new DefaultReasoningEngine());
    }

    public ReasoningSDK(DefaultReasoningEngine engine) {
        this.engine = Objects.requireNonNull(engine, "DefaultReasoningEngine must not be null");
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
}
