package com.sovereign.core.workspace;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>WorkspaceContext</b>
 *
 * <p>Structured metadata describing an active workspace/repository, its build descriptors,
 * detected frameworks, and canonical source paths.</p>
 */
public record WorkspaceContext(
        Path rootPath,
        String projectName,
        String projectVersion,
        String buildTool,
        String detectedFramework,
        List<String> sourceDirectories,
        Map<String, String> metadata
) {
    public WorkspaceContext {
        Objects.requireNonNull(rootPath, "rootPath must not be null");
        projectName = projectName != null && !projectName.isBlank() ? projectName : rootPath.getFileName().toString();
        projectVersion = projectVersion != null ? projectVersion : "1.0.0";
        buildTool = buildTool != null ? buildTool : "unknown";
        detectedFramework = detectedFramework != null ? detectedFramework : "none";
        sourceDirectories = sourceDirectories != null ? List.copyOf(sourceDirectories) : Collections.emptyList();
        metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
    }

    public boolean isMaven() {
        return "Maven".equalsIgnoreCase(buildTool);
    }

    public boolean isGradle() {
        return "Gradle".equalsIgnoreCase(buildTool);
    }

    public boolean isNpm() {
        return "npm".equalsIgnoreCase(buildTool);
    }

    /**
     * Resolves the canonical command for running tests in this workspace.
     */
    public String getTestCommand() {
        if (isMaven()) {
            return "mvn test";
        } else if (isGradle()) {
            return "gradle test";
        } else if (isNpm()) {
            return "npm test";
        }
        return "echo \"No build tool detected for test execution\"";
    }

    /**
     * Resolves the canonical command for compiling or building this workspace.
     */
    public String getBuildCommand() {
        if (isMaven()) {
            return "mvn compile";
        } else if (isGradle()) {
            return "gradle build";
        } else if (isNpm()) {
            return "npm run build";
        }
        return "echo \"No build tool detected for compilation\"";
    }
}
