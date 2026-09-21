package com.sovereign.core.intent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>IntentRouter</b>
 *
 * <p>Deterministic, pattern-matched intent classifier that categorizes user prompts into
 * CHAT, MEMORY, EXECUTE, PROJECT, and SYSTEM channels before calling planning engines.
 * Prevents conversational inputs and memory queries from reaching host shell execution.</p>
 *
 * <p><b>Routing priority (highest → lowest):</b>
 * SYSTEM → CHAT-greeting → MEMORY → PROJECT → CHAT-question/banter/NL → EXECUTE (only known shell tokens)</p>
 */
public class IntentRouter {

    // ─── SYSTEM ──────────────────────────────────────────────────────────────
    private static final Pattern SYSTEM_PATTERN = Pattern.compile(
            "^\\s*(status|uptime|health|config|exit|quit|help|processes|keys|sovereign\\s+keys|show\\s+keys)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // ─── GREETINGS (CHAT) ─────────────────────────────────────────────────────
    /**
     * Bare greetings with no substantial trailing content:
     * "hello", "hi there", "hey!", "good morning", "thanks", "bye".
     */
    private static final Pattern GREETINGS_PATTERN = Pattern.compile(
            "^\\s*(?:hello|hi|hey|greetings|good\\s+(?:morning|afternoon|evening|day)|howdy|sup|what's\\s+up|how\\s+are\\s+you|who\\s+are\\s+you|thank\\s+you|thanks|bye|goodbye)[!?,.\\s]*(?:how\\s+are\\s+you|what's\\s+up|who\\s+are\\s+you|there|all)?[!?,.\\s]*$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Greeting with ANY trailing text ("hello sovereign", "hi how can you help me", etc.).
     * Routes to CHAT so the live LLM handles the full message — prevents multi-word
     * greetings with extra tokens or whitespace from falling through to EXECUTE.
     */
    private static final Pattern GREETING_PREFIX_PATTERN = Pattern.compile(
            "^(?:hello|hi|hey|greetings)(?:\\s+.*)?$",
            Pattern.CASE_INSENSITIVE
    );

    // ─── MEMORY ───────────────────────────────────────────────────────────────
    /**
     * Compound greeting + name introduction:
     * "hello i am rahul", "hi my name is alex", "hey sovereign i am darshan"
     * Captures the name in group 1.
     */
    private static final Pattern COMPOUND_GREETING_PATTERN = Pattern.compile(
            "(?i)^(?:hello|hi|hey|greetings)?(?:\\s+(?:sovereign|there|friend))?\\s*[,!]?\\s*(?:i\\s+am|i'm|my\\s+name\\s+is)\\s+([a-zA-Z0-9_.-]+)[.!]?$"
    );

    /** Plain name store: "I am Darshan", "My name is Rahul". */
    private static final Pattern STORE_NAME_PATTERN = Pattern.compile(
            "(?i)^(?:i\\s+am|my\\s+name\\s+is|call\\s+me|remember\\s+that\\s+i\\s+am)\\s+([a-zA-Z0-9_.-]+)[.!]?$"
    );

    private static final Pattern RECALL_NAME_PATTERN = Pattern.compile(
            "(?i)^.*(?:what\\s+is\\s+my\\s+name|what's\\s+my\\s+name|who\\s+am\\s+i|do\\s+you\\s+know\\s+my\\s+name).*$"
    );

    private static final Pattern STORE_PREF_PATTERN = Pattern.compile(
            "(?i)^(?:remember\\s+(?:my\\s+)?|set\\s+(?:my\\s+)?(?:preferred\\s+)?)(editor|shell)\\s+(?:is\\s+|to\\s+)?([a-zA-Z0-9_.\\s-]+?)[.!]?$"
    );

    private static final Pattern RECALL_PREF_PATTERN = Pattern.compile(
            "(?i)^.*(?:what\\s+are\\s+my\\s+preferences|show\\s+(?:my\\s+)?preferences|show\\s+memory|what(?:'s|\\s+is)\\s+my\\s+(?:editor|shell)).*$"
    );

    // ─── PROJECT ──────────────────────────────────────────────────────────────
    private static final Pattern PROJECT_PREFIX_PATTERN = Pattern.compile(
            "^(?i)(?:please\\s+|can\\s+you\\s+)?(?:analyze|inspect|explain|summarize|find)\\b"
    );

    private static final Pattern WORKSPACE_KEYWORDS_PATTERN = Pattern.compile(
            "(?i)\\b(?:workspace|project|repo|repository|codebase|source|architecture|java|maven|gradle|pom|structure|dependencies|dirs|directories|tree)\\b"
    );

    private static final Pattern PROJECT_PATTERN = Pattern.compile(
            "(?i)^.*(?:workspace\\s+summary|project\\s+structure|show\\s+(?:project\\s+)?dependencies|show\\s+source\\s+dirs).*$"
    );

    // ─── CHAT: open-ended questions, world knowledge, banter ─────────────────
    /**
     * Broad pattern that catches all informational/conversational queries that should
     * go to the live LLM provider:
     * "what is java", "how can you help me", "tell me how to build jarvis",
     * "explain recursion", "write a sorting function", "define abstraction",
     * "code a REST API", "guide me through TDD", "describe microservices".
     */
    private static final Pattern CONVERSATIONAL_QUESTION_PATTERN = Pattern.compile(
            "^(?i)(?:what|why|how|who|when|where|" +
            "can\\s+you|could\\s+you|would\\s+you|" +
            "tell\\s+me|brainstorm|discuss|compare|" +
            "write|code|define|guide|give\\s+me|" +
            "help\\s+me(?:\\s+with)?|explain|suggest|" +
            "think\\s+about|do\\s+you\\s+know|describe|" +
            "show\\s+me|teach\\s+me|list)\\b"
    );

    private static final Pattern CONVERSATIONAL_BANTER_PATTERN = Pattern.compile(
            "^(?i)(?:good\\s+job|nice\\s+work|awesome|cool|great|wow|interesting|" +
            "haha|lol|joke|tell\\s+a\\s+joke|inspire\\s+me|" +
            "who\\s+are\\s+you|what\\s+are\\s+you|what\\s+is\\s+your\\s+purpose|" +
            "introduce\\s+yourself|what\\s+can\\s+you\\s+do|what\\s+do\\s+you\\s+do|" +
            "help\\s+me|how\\s+can\\s+you\\s+help|what\\s+are\\s+your\\s+capabilities)[!.,?\\s]*$"
    );

    // ─── EXECUTE helpers ──────────────────────────────────────────────────────
    /**
     * Strips leading imperative verbs that precede shell commands:
     * "Run git status" → "git status", "Please execute mvn test" → "mvn test".
     */
    private static final Pattern IMPERATIVE_PREFIX_PATTERN = Pattern.compile(
            "^(?i)(?:please\\s+run|can\\s+you\\s+run|please\\s+execute|run|execute|check|show|open|build|test|please)\\s+"
    );

    /**
     * Prompt-prefix noise: terminal copy-paste artifacts.
     * "sovereign> Run git status" → "Run git status".
     * "$ git status" → "git status".
     */
    private static final Pattern PROMPT_PREFIX_PATTERN = Pattern.compile(
            "^(?i)(?:sovereign\\s*>\\s*|\\$\\s*|>\\s+)"
    );

    /**
     * First-token guard — only these executable names are safe to forward to shell_exec.
     * Everything else is natural language and routes to CHAT (live LLM).
     */
    private static final Pattern KNOWN_SHELL_TOKEN_PATTERN = Pattern.compile(
            "(?i)^(?:git|mvn|mvnw|gradle|gradlew|npm|npx|yarn|cargo|docker|kubectl|helm|curl|wget|" +
            "echo|ls|dir|cat|type|find|grep|ping|ssh|scp|" +
            "python|python3|java|node|go|rustc|make|cmake|" +
            "bash|sh|cmd|powershell|pwsh|" +
            "az|gcloud|terraform|ansible|apt|brew|choco|winget|pip|pip3)(?:\\s|$)"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Public static helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Normalizes raw input before classification:
     * <ol>
     *   <li>Strips REPL prompt-prefix noise ({@code sovereign>}, {@code $}, {@code >}).</li>
     *   <li>Collapses consecutive whitespace to a single space.</li>
     *   <li>Trims leading/trailing whitespace.</li>
     * </ol>
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "sovereign> Run  git  status"} → {@code "Run git status"}</li>
     *   <li>{@code "hello  sovereign"} → {@code "hello sovereign"}</li>
     *   <li>{@code "$ git   status"} → {@code "git status"}</li>
     * </ul>
     */
    public static String normalizeInput(String raw) {
        if (raw == null) {
            return "";
        }
        // Step 1: outer trim
        String trimmed = raw.trim();
        // Step 2: strip prompt-prefix noise
        Matcher m = PROMPT_PREFIX_PATTERN.matcher(trimmed);
        if (m.find()) {
            trimmed = trimmed.substring(m.end()).trim();
        }
        // Step 3: collapse multiple consecutive whitespace characters to a single space
        trimmed = trimmed.replaceAll("\\s+", " ").trim();
        return trimmed;
    }

    /**
     * Returns {@code true} when the input reads as natural language and must never
     * be forwarded raw to {@code shell_exec}.
     *
     * <p>An input is NL when it is multi-word AND its first token is not a recognized
     * OS executable. Single-token inputs are let through (e.g. {@code "ls"} → shell).</p>
     */
    public static boolean isNaturalLanguageFallback(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String trimmed = input.trim();
        // Single token — let caller decide (SYSTEM or shell)
        if (!trimmed.contains(" ")) {
            return false;
        }
        return !KNOWN_SHELL_TOKEN_PATTERN.matcher(trimmed).find();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core classification
    // ─────────────────────────────────────────────────────────────────────────

    public IntentClassificationResult classify(String input) {
        if (input == null || input.isBlank()) {
            return IntentClassificationResult.of(UserIntentType.CHAT, "", 1.0);
        }

        // Normalize: strip prefix noise + collapse whitespace
        String raw = normalizeInput(input);
        String lower = raw.toLowerCase(Locale.ROOT);

        // ── 1. SYSTEM ──────────────────────────────────────────────────────
        if (SYSTEM_PATTERN.matcher(raw).matches()) {
            Map<String, String> entities = new LinkedHashMap<>();
            if (lower.contains("keys")) {
                entities.put("operation", "KEYS");
            } else {
                entities.put("operation", lower);
            }
            return IntentClassificationResult.of(UserIntentType.SYSTEM, lower, 1.0, entities);
        }

        // ── 2. CHAT — bare greetings ("hello", "hi there", "bye") ─────────
        if (GREETINGS_PATTERN.matcher(raw).matches()) {
            return IntentClassificationResult.of(UserIntentType.CHAT, raw, 0.98);
        }

        // ── 3. MEMORY — compound greeting + name ("hello i am rahul") ─────
        Matcher compoundGreetingMatcher = COMPOUND_GREETING_PATTERN.matcher(raw);
        if (compoundGreetingMatcher.matches()) {
            String name = compoundGreetingMatcher.group(1);
            Map<String, String> entities = new HashMap<>();
            entities.put("operation", "STORE_NAME");
            entities.put("userName", name);
            return IntentClassificationResult.of(UserIntentType.MEMORY, raw, 0.97, entities);
        }

        // ── 3b. MEMORY — plain name store ("I am Darshan") ────────────────
        Matcher storeNameMatcher = STORE_NAME_PATTERN.matcher(raw);
        if (storeNameMatcher.matches()) {
            String name = storeNameMatcher.group(1);
            Map<String, String> entities = new HashMap<>();
            entities.put("operation", "STORE_NAME");
            entities.put("userName", name);
            return IntentClassificationResult.of(UserIntentType.MEMORY, raw, 0.95, entities);
        }

        if (RECALL_NAME_PATTERN.matcher(raw).matches()) {
            Map<String, String> entities = Map.of("operation", "RECALL_NAME");
            return IntentClassificationResult.of(UserIntentType.MEMORY, raw, 0.95, entities);
        }

        Matcher storePrefMatcher = STORE_PREF_PATTERN.matcher(raw);
        if (storePrefMatcher.matches()) {
            String key = storePrefMatcher.group(1).toLowerCase(Locale.ROOT);
            String val = storePrefMatcher.group(2).trim();
            Map<String, String> entities = new HashMap<>();
            entities.put("operation", "STORE_PREFERENCE");
            entities.put("preferenceKey", key);
            entities.put("preferenceValue", val);
            return IntentClassificationResult.of(UserIntentType.MEMORY, raw, 0.95, entities);
        }

        if (RECALL_PREF_PATTERN.matcher(raw).matches()) {
            Map<String, String> entities = Map.of("operation", "RECALL_PREFERENCES");
            return IntentClassificationResult.of(UserIntentType.MEMORY, raw, 0.95, entities);
        }

        // ── 4. PROJECT ─────────────────────────────────────────────────────
        if (PROJECT_PATTERN.matcher(raw).matches() ||
                (PROJECT_PREFIX_PATTERN.matcher(raw).find() && WORKSPACE_KEYWORDS_PATTERN.matcher(raw).find())) {
            return IntentClassificationResult.of(UserIntentType.PROJECT, raw, 0.90);
        }

        // ── 5. CHAT — open-ended questions, banter, world knowledge ───────
        if (CONVERSATIONAL_QUESTION_PATTERN.matcher(raw).find()
                || CONVERSATIONAL_BANTER_PATTERN.matcher(raw).matches()
                || (raw.endsWith("?") && !raw.endsWith("/?") && !raw.endsWith("-?"))
                // greeting-with-trailing-text: "hello sovereign", "hi how are you doing"
                || GREETING_PREFIX_PATTERN.matcher(raw).matches()) {
            return IntentClassificationResult.of(UserIntentType.CHAT, raw, 0.90);
        }

        // ── 6. EXECUTE — only for known shell-token commands ───────────────
        // Strip imperative prefix first: "Run git status" → "git status"
        String normalized = stripImperativePrefix(raw);

        // Guard: if still not a known shell token, it is NL — route to CHAT (live LLM)
        if (isNaturalLanguageFallback(normalized)) {
            return IntentClassificationResult.of(UserIntentType.CHAT, raw, 0.75);
        }

        return IntentClassificationResult.of(UserIntentType.EXECUTE, normalized, 0.85);
    }

    /**
     * Strips conversational imperative prefixes while preserving the actual shell command.
     * <ul>
     *   <li>{@code "Run git status"} → {@code "git status"}</li>
     *   <li>{@code "Please execute mvn test"} → {@code "mvn test"}</li>
     * </ul>
     */
    public static String stripImperativePrefix(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.trim();
        Matcher matcher = IMPERATIVE_PREFIX_PATTERN.matcher(stripped);
        if (matcher.find()) {
            stripped = stripped.substring(matcher.end()).trim();
        }
        return stripped;
    }
}
