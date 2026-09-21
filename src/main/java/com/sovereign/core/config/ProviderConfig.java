package com.sovereign.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>ProviderConfig</b>
 *
 * <p>Central configuration and credential resolver for LLM providers (Gemini, OpenAI, in-memory)
 * adhering to strict 3-tier priority resolution:</p>
 * <ol>
 *   <li>System Environment Variables (e.g. {@code GEMINI_API_KEY}, {@code OPENAI_API_KEY})</li>
 *   <li>JVM System Properties (e.g. {@code -DGEMINI_API_KEY=...}, {@code -DOPENAI_API_KEY=...})</li>
 *   <li>Optional {@code .env} file in current workspace ({@code ./.env}) or user home ({@code ~/.sovereign/.env})</li>
 * </ol>
 *
 * <p>Ensures that if no valid cloud credentials are present, the active LLM chain is locked directly
 * to {@code "in-memory"} to prevent unauthenticated outbound network calls that produce HTTP 401/404 dumps.</p>
 */
public class ProviderConfig {

    private static final Logger LOGGER = Logger.getLogger(ProviderConfig.class.getName());

    public enum CredentialSource {
        ENVIRONMENT,
        JVM_PROPERTY,
        DOT_ENV,
        DIRECT,
        NONE
    }

    private final String geminiApiKey;
    private final CredentialSource geminiSource;
    private final String openAiApiKey;
    private final CredentialSource openAiSource;

    public ProviderConfig(String geminiApiKey, CredentialSource geminiSource,
                          String openAiApiKey, CredentialSource openAiSource) {
        this.geminiApiKey = cleanKey(geminiApiKey);
        this.geminiSource = (this.geminiApiKey != null) ? geminiSource : CredentialSource.NONE;
        this.openAiApiKey = cleanKey(openAiApiKey);
        this.openAiSource = (this.openAiApiKey != null) ? openAiSource : CredentialSource.NONE;
    }

    /**
     * Factory for direct mock/simulated key injection (used in testing and explicit overrides).
     */
    public static ProviderConfig of(String geminiApiKey, String openAiApiKey) {
        return new ProviderConfig(
                geminiApiKey,
                geminiApiKey != null ? CredentialSource.DIRECT : CredentialSource.NONE,
                openAiApiKey,
                openAiApiKey != null ? CredentialSource.DIRECT : CredentialSource.NONE
        );
    }

    /**
     * Resolves LLM provider credentials following standard 3-tier precedence:
     * 1. Environment Variable -> 2. JVM System Property -> 3. .env File.
     */
    public static ProviderConfig load() {
        // Check for explicit in-memory bypass flag (useful in tests or offline environments)
        if ("true".equalsIgnoreCase(System.getProperty("sovereign.in-memory-only"))
                || "in-memory".equalsIgnoreCase(System.getProperty("shree.llm.chain"))) {
            return ProviderConfig.of(null, null);
        }

        Map<String, String> dotEnvEntries = loadDotEnvEntries();

        // 1. Resolve Gemini Key
        String geminiKey = null;
        CredentialSource geminiSource = CredentialSource.NONE;

        String envGemini = firstNonBlank(System.getenv("GEMINI_API_KEY"), System.getenv("GOOGLE_API_KEY"));
        if (envGemini != null) {
            geminiKey = envGemini;
            geminiSource = CredentialSource.ENVIRONMENT;
        } else {
            String propGemini = firstNonBlank(
                    System.getProperty("GEMINI_API_KEY"),
                    firstNonBlank(
                            System.getProperty("gemini.api.key"),
                            firstNonBlank(
                                    System.getProperty("gemini.api-key"),
                                    firstNonBlank(
                                            System.getProperty("shree.llm.gemini.api-key"),
                                            System.getProperty("GOOGLE_API_KEY")
                                    )
                            )
                    )
            );
            if (propGemini != null) {
                geminiKey = propGemini;
                geminiSource = CredentialSource.JVM_PROPERTY;
            } else if (dotEnvEntries.containsKey("GEMINI_API_KEY")) {
                geminiKey = dotEnvEntries.get("GEMINI_API_KEY");
                geminiSource = CredentialSource.DOT_ENV;
            } else if (dotEnvEntries.containsKey("GOOGLE_API_KEY")) {
                geminiKey = dotEnvEntries.get("GOOGLE_API_KEY");
                geminiSource = CredentialSource.DOT_ENV;
            }
        }

        // 2. Resolve OpenAI Key
        String openAiKey = null;
        CredentialSource openAiSource = CredentialSource.NONE;

        String envOpenAi = System.getenv("OPENAI_API_KEY");
        if (envOpenAi != null && !envOpenAi.isBlank() && !isSuppressedKey(envOpenAi)) {
            openAiKey = envOpenAi;
            openAiSource = CredentialSource.ENVIRONMENT;
        } else {
            String propOpenAi = firstNonBlank(
                    System.getProperty("OPENAI_API_KEY"),
                    firstNonBlank(
                            System.getProperty("openai.api.key"),
                            firstNonBlank(
                                    System.getProperty("openai.api-key"),
                                    System.getProperty("shree.llm.openai.api-key")
                            )
                    )
            );
            if (propOpenAi != null && !isSuppressedKey(propOpenAi)) {
                openAiKey = propOpenAi;
                openAiSource = CredentialSource.JVM_PROPERTY;
            } else if (dotEnvEntries.containsKey("OPENAI_API_KEY")) {
                String dotVal = dotEnvEntries.get("OPENAI_API_KEY");
                if (!isSuppressedKey(dotVal)) {
                    openAiKey = dotVal;
                    openAiSource = CredentialSource.DOT_ENV;
                }
            }
        }

        return new ProviderConfig(geminiKey, geminiSource, openAiKey, openAiSource);
    }

    /**
     * Resolves credentials strictly from a given .env file path.
     */
    public static ProviderConfig fromDotEnv(Path envPath) {
        Map<String, String> entries = parseDotEnvFile(envPath);
        String geminiKey = entries.getOrDefault("GEMINI_API_KEY", entries.get("GOOGLE_API_KEY"));
        String openAiKey = entries.get("OPENAI_API_KEY");
        return new ProviderConfig(
                geminiKey, geminiKey != null ? CredentialSource.DOT_ENV : CredentialSource.NONE,
                openAiKey, openAiKey != null ? CredentialSource.DOT_ENV : CredentialSource.NONE
        );
    }

    /**
     * Resolves the active LLM router chain according to available credentials:
     * <ul>
     *   <li>If {@code GEMINI_API_KEY} is present: {@code "gemini,openai,in-memory"}</li>
     *   <li>If only {@code OPENAI_API_KEY} is present: {@code "openai,in-memory"}</li>
     *   <li>If neither key is present: {@code "in-memory"}</li>
     * </ul>
     */
    public String resolveActiveChain() {
        if (hasGeminiKey()) {
            return "gemini,openai,in-memory";
        } else if (hasOpenAiKey()) {
            return "openai,in-memory";
        } else {
            return "in-memory";
        }
    }

    /**
     * Configures JVM properties so underlying Shree AI OS and Spring components
     * consume the resolved provider chain and credentials.
     */
    public void applySystemProperties() {
        String chain = resolveActiveChain();
        System.setProperty("shree.llm.chain", chain);
        System.setProperty("SHREE_LLM_CHAIN", chain);

        if (hasGeminiKey()) {
            System.setProperty("shree.llm.gemini.api-key", geminiApiKey);
            System.setProperty("gemini.api.key", geminiApiKey);
            System.setProperty("GEMINI_API_KEY", geminiApiKey);
        }
        if (hasOpenAiKey()) {
            System.setProperty("shree.llm.openai.api-key", openAiApiKey);
            System.setProperty("openai.api.key", openAiApiKey);
            System.setProperty("OPENAI_API_KEY", openAiApiKey);
        }
    }

    public boolean hasGeminiKey() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    public boolean hasOpenAiKey() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }

    public boolean hasAnyKey() {
        return hasGeminiKey() || hasOpenAiKey();
    }

    public boolean isOnline() {
        return hasAnyKey();
    }

    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    public CredentialSource getGeminiSource() {
        return geminiSource;
    }

    public String getOpenAiApiKey() {
        return openAiApiKey;
    }

    public CredentialSource getOpenAiSource() {
        return openAiSource;
    }

    public String getActiveProviderDisplayName() {
        if (hasGeminiKey()) {
            return "Gemini Live";
        } else if (hasOpenAiKey()) {
            return "OpenAI";
        } else {
            return "Deterministic In-Memory Engine Active";
        }
    }

    public String getBanner() {
        if (isOnline()) {
            return "[SOVEREIGN CORE] Neural Link: ONLINE (Provider: " + getActiveProviderDisplayName() + ")";
        } else {
            return "[SOVEREIGN CORE] Neural Link: OFFLINE (Deterministic In-Memory Engine Active)";
        }
    }

    /**
     * Returns the primary API key for bootstrapping the client facade.
     */
    public String resolvePrimaryApiKey() {
        if (hasGeminiKey()) {
            return geminiApiKey;
        } else if (hasOpenAiKey()) {
            return openAiApiKey;
        } else {
            return "deterministic-fallback-key";
        }
    }

    /**
     * Produces a secure masked string for display (e.g. "test-g...****").
     */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "[NOT DETECTED]";
        }
        String clean = key.trim();
        if (clean.length() <= 8) {
            return "****";
        }
        return clean.substring(0, 6) + "...****";
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper & .env parser methods
    // ─────────────────────────────────────────────────────────────────────────────

    private static Map<String, String> loadDotEnvEntries() {
        // Check current workspace .env first
        Path workspaceEnv = Path.of(".env");
        if (Files.isRegularFile(workspaceEnv)) {
            return parseDotEnvFile(workspaceEnv);
        }

        // Check user home ~/.sovereign/.env
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path homeEnv = Path.of(userHome, ".sovereign", ".env");
            if (Files.isRegularFile(homeEnv)) {
                return parseDotEnvFile(homeEnv);
            }
        }

        return Collections.emptyMap();
    }

    public static Map<String, String> parseDotEnvFile(Path envFile) {
        if (envFile == null || !Files.isRegularFile(envFile)) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                if (rawLine == null) continue;
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim();
                    // Strip enclosing quotes if present
                    if ((val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2)
                            || (val.startsWith("'") && val.endsWith("'") && val.length() >= 2)) {
                        val = val.substring(1, val.length() - 1).trim();
                    }
                    if (!key.isEmpty() && !val.isEmpty()) {
                        map.put(key, val);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read .env file at " + envFile + ": " + e.getMessage());
        }
        return Collections.unmodifiableMap(map);
    }

    private static String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary.trim();
        }
        return null;
    }

    private static String cleanKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        return isSuppressedKey(trimmed) ? null : trimmed;
    }

    private static boolean isSuppressedKey(String key) {
        if (key == null) return true;
        String lower = key.trim().toLowerCase();
        return lower.isEmpty() || lower.equals("none") || lower.equals("disabled") || lower.equals("null");
    }
}
