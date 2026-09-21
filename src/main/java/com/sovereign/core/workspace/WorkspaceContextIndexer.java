package com.sovereign.core.workspace;

import com.shreeai.os.platform.kernels.project.model.ProjectSummary;
import com.shreeai.os.platform.sdk.ProjectSDK;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>WorkspaceContextIndexer</b>
 *
 * <p>Repository intelligence indexer that inspects build descriptors, discovers project structure,
 * and caches workspace context for the cognitive planner.</p>
 */
public class WorkspaceContextIndexer {

    private final Path rootPath;
    private final ProjectSDK projectSdk;
    private volatile WorkspaceContext cachedContext;

    public WorkspaceContextIndexer() {
        this(Paths.get("").toAbsolutePath().normalize(), null);
    }

    public WorkspaceContextIndexer(Path rootPath) {
        this(rootPath, null);
    }

    public WorkspaceContextIndexer(Path rootPath, ProjectSDK projectSdk) {
        this.rootPath = Objects.requireNonNull(rootPath, "Root path must not be null").toAbsolutePath().normalize();
        this.projectSdk = projectSdk;
    }

    /**
     * Retrieves the cached workspace context or indexes the repository if not yet cached.
     */
    public WorkspaceContext getContext() {
        if (cachedContext == null) {
            synchronized (this) {
                if (cachedContext == null) {
                    cachedContext = index();
                }
            }
        }
        return cachedContext;
    }

    /**
     * Forces a re-index of the repository workspace.
     */
    public WorkspaceContext refresh() {
        synchronized (this) {
            cachedContext = index();
            return cachedContext;
        }
    }

    /**
     * Performs filesystem inspection of build descriptors and constructs the {@link WorkspaceContext}.
     */
    public WorkspaceContext index() {
        String projectName = rootPath.getFileName() != null ? rootPath.getFileName().toString() : "workspace";
        String projectVersion = "1.0.0";
        String buildTool = "unknown";
        String framework = "none";
        List<String> sourceDirs = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();

        Path pomXml = rootPath.resolve("pom.xml");
        Path gradleBuild = rootPath.resolve("build.gradle");
        Path gradleKts = rootPath.resolve("build.gradle.kts");
        Path packageJson = rootPath.resolve("package.json");

        if (Files.exists(pomXml)) {
            buildTool = "Maven";
            metadata.put("descriptor", "pom.xml");
            try {
                String pomContent = Files.readString(pomXml, StandardCharsets.UTF_8);
                Matcher artifactMatcher = Pattern.compile("<artifactId>(.*?)</artifactId>").matcher(pomContent);
                if (artifactMatcher.find()) {
                    projectName = artifactMatcher.group(1).trim();
                }
                Matcher versionMatcher = Pattern.compile("<version>(.*?)</version>").matcher(pomContent);
                if (versionMatcher.find()) {
                    projectVersion = versionMatcher.group(1).trim();
                }
                if (pomContent.contains("spring-boot")) {
                    framework = "Spring Boot";
                } else {
                    framework = "Java";
                }
            } catch (IOException ignored) {
            }

            // Discover standard Maven source roots
            checkAndAddSourceDir(rootPath.resolve("src/main/java"), sourceDirs);
            checkAndAddSourceDir(rootPath.resolve("src/test/java"), sourceDirs);
            checkAndAddSourceDir(rootPath.resolve("apps/sovereign-assistant/src/main/java"), sourceDirs);
            checkAndAddSourceDir(rootPath.resolve("apps/sovereign-assistant/src/test/java"), sourceDirs);

        } else if (Files.exists(gradleBuild) || Files.exists(gradleKts)) {
            buildTool = "Gradle";
            framework = "Java/Kotlin";
            metadata.put("descriptor", Files.exists(gradleBuild) ? "build.gradle" : "build.gradle.kts");

            checkAndAddSourceDir(rootPath.resolve("src/main/java"), sourceDirs);
            checkAndAddSourceDir(rootPath.resolve("src/main/kotlin"), sourceDirs);

        } else if (Files.exists(packageJson)) {
            buildTool = "npm";
            metadata.put("descriptor", "package.json");
            try {
                String pkgContent = Files.readString(packageJson, StandardCharsets.UTF_8);
                Matcher nameMatcher = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(pkgContent);
                if (nameMatcher.find()) {
                    projectName = nameMatcher.group(1).trim();
                }
                Matcher verMatcher = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(pkgContent);
                if (verMatcher.find()) {
                    projectVersion = verMatcher.group(1).trim();
                }
                if (pkgContent.contains("\"react\"")) {
                    framework = "React";
                } else if (pkgContent.contains("\"express\"")) {
                    framework = "Express";
                } else {
                    framework = "Node.js";
                }
            } catch (IOException ignored) {
            }

            checkAndAddSourceDir(rootPath.resolve("src"), sourceDirs);
            checkAndAddSourceDir(rootPath.resolve("lib"), sourceDirs);
        }

        // Integrate with Shree AI OS ProjectSDK if available
        if (projectSdk != null) {
            try {
                ProjectSummary summary = projectSdk.analyze(rootPath);
                if (summary != null) {
                    if (summary.projectName() != null && !summary.projectName().isBlank()) {
                        projectName = summary.projectName();
                    }
                    if (summary.framework() != null && !summary.framework().isBlank()) {
                        framework = summary.framework();
                    }
                    if (summary.buildSystem() != null && !summary.buildSystem().isBlank()) {
                        buildTool = summary.buildSystem();
                    }
                    metadata.put("projectSdkAnalyzed", "true");
                }
            } catch (Exception ignored) {
            }
        }

        WorkspaceContext context = new WorkspaceContext(
                rootPath,
                projectName,
                projectVersion,
                buildTool,
                framework,
                sourceDirs,
                metadata
        );

        this.cachedContext = context;
        return context;
    }

    private void checkAndAddSourceDir(Path dir, List<String> list) {
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            list.add(rootPath.relativize(dir).toString().replace('\\', '/'));
        }
    }

    public Path getRootPath() {
        return rootPath;
    }

    public ProjectSDK getProjectSdk() {
        return projectSdk;
    }
}
