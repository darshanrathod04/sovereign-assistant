package com.sovereign.core.intent;

import java.util.HashMap;
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
 */
public class IntentRouter {

    private static final Pattern GREETINGS_PATTERN = Pattern.compile(
            "^\\s*(?:hello|hi|hey|greetings|good\\s+(?:morning|afternoon|evening|day)|howdy|sup|what's\\s+up|how\\s+are\\s+you|who\\s+are\\s+you|thank\\s+you|thanks|bye|goodbye)[!?,.\\s]*(?:how\\s+are\\s+you|what's\\s+up|who\\s+are\\s+you|there|all)?[!?,.\\s]*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SYSTEM_PATTERN = Pattern.compile(
            "^\\s*(status|uptime|health|config|exit|quit|help|processes)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // Memory Patterns
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

    // Project Query Patterns
    private static final Pattern PROJECT_PREFIX_PATTERN = Pattern.compile(
            "^(?i)(?:please\\s+|can\\s+you\\s+)?(?:analyze|inspect|explain|summarize|find)\\b"
    );

    private static final Pattern WORKSPACE_KEYWORDS_PATTERN = Pattern.compile(
            "(?i)\\b(?:workspace|project|repo|repository|codebase|source|architecture|java|maven|gradle|pom|structure|dependencies|dirs|directories|tree)\\b"
    );

    private static final Pattern PROJECT_PATTERN = Pattern.compile(
            "(?i)^.*(?:workspace\\s+summary|project\\s+structure|show\\s+(?:project\\s+)?dependencies|show\\s+source\\s+dirs).*$"
    );

    // Leading imperative prefix cleaner
    private static final Pattern IMPERATIVE_PREFIX_PATTERN = Pattern.compile(
            "^(?i)(?:please\\s+run|can\\s+you\\s+run|please\\s+execute|run|execute|check|show|open|build|test|please)\\s+"
    );

    public IntentClassificationResult classify(String input) {
        if (input == null || input.isBlank()) {
            return IntentClassificationResult.of(UserIntentType.CHAT, "", 1.0);
        }

        String raw = input.trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        // 1. SYSTEM
        if (SYSTEM_PATTERN.matcher(raw).matches()) {
            return IntentClassificationResult.of(UserIntentType.SYSTEM, lower, 1.0);
        }

        // 2. CHAT
        if (GREETINGS_PATTERN.matcher(raw).matches()) {
            return IntentClassificationResult.of(UserIntentType.CHAT, raw, 0.98);
        }

        // 3. MEMORY
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

        // 4. PROJECT
        if (PROJECT_PATTERN.matcher(raw).matches() ||
                (PROJECT_PREFIX_PATTERN.matcher(raw).find() && WORKSPACE_KEYWORDS_PATTERN.matcher(raw).find())) {
            return IntentClassificationResult.of(UserIntentType.PROJECT, raw, 0.90);
        }

        // 5. EXECUTE (Default for concrete system operations)
        String normalized = stripImperativePrefix(raw);
        return IntentClassificationResult.of(UserIntentType.EXECUTE, normalized, 0.85);
    }

    /**
     * Strips conversational imperative prefixes like "Run ", "Execute ", "Please run "
     * while preserving actual shell commands (e.g. "git status").
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
