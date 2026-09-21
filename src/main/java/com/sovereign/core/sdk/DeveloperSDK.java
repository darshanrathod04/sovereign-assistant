package com.sovereign.core.sdk;

import com.shreeai.os.platform.kernels.developer.engine.DefaultDeveloperAgentEngine;
import com.shreeai.os.platform.kernels.response.model.DeveloperResponse;

import java.util.Objects;

/**
 * <b>DeveloperSDK</b>
 *
 * <p>Facade exposing developer kernel capabilities (code intelligence, patch planning,
 * and AST analysis) from Shree AI OS to Sovereign Assistant.</p>
 */
public class DeveloperSDK {

    private final DefaultDeveloperAgentEngine engine;

    public DeveloperSDK() {
        this(new DefaultDeveloperAgentEngine());
    }

    public DeveloperSDK(DefaultDeveloperAgentEngine engine) {
        this.engine = Objects.requireNonNull(engine, "DefaultDeveloperAgentEngine must not be null");
    }

    /**
     * Analyzes a developer instruction or task description.
     */
    public DeveloperResponse analyze(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Instruction must not be null or blank");
        }
        return engine.analyze(instruction);
    }

    /**
     * Analyzes an instruction alongside relevant code context.
     */
    public DeveloperResponse analyze(String instruction, String code) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Instruction must not be null or blank");
        }
        return engine.analyze(instruction, code != null ? code : "");
    }

    /**
     * Synthesizes code analysis and recommendations for source code.
     */
    public DeveloperResponse analyzeWithCode(String instruction, String code) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Instruction must not be null or blank");
        }
        return engine.analyzeWithCode(instruction, code != null ? code : "");
    }

    public DefaultDeveloperAgentEngine getEngine() {
        return engine;
    }
}
