package com.sovereign.core.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>CorrectionLedger</b>
 *
 * <p>Self-learning memory ledger that captures user corrections, persistent instructions,
 * and coding guidelines. Persisted to {@code ~/.sovereign/rules.json} at zero cost,
 * and dynamically injected into Sovereign's JARVIS system prompt.</p>
 */
public class CorrectionLedger {

    private static final Logger LOG = Logger.getLogger(CorrectionLedger.class.getName());
    private static final Pattern CORRECTION_PATTERN = Pattern.compile(
            "(?:don'?t|do not|never|always|prefer|remember to)\\s+(.+)", Pattern.CASE_INSENSITIVE);

    public record LearnedRule(
            String id,
            String rule,
            String triggerPhrase,
            Instant learnedAt
    ) {}

    private final Path storagePath;
    private final List<LearnedRule> rules = new ArrayList<>();

    public CorrectionLedger() {
        this(resolveDefaultStoragePath());
    }

    public CorrectionLedger(Path storagePath) {
        this.storagePath = storagePath != null ? storagePath : resolveDefaultStoragePath();
        load();
    }

    public static Path resolveDefaultStoragePath() {
        String prop = System.getProperty("sovereign.rules.path");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop.trim());
        }
        String userHome = System.getProperty("user.home", ".");
        return Paths.get(userHome, ".sovereign", "rules.json");
    }

    public synchronized void addRule(String rule, String triggerPhrase) {
        if (rule == null || rule.isBlank()) return;
        String trimmed = rule.trim();
        // Prevent exact duplicates
        for (LearnedRule existing : rules) {
            if (existing.rule().equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        rules.add(new LearnedRule(UUID.randomUUID().toString(), trimmed, triggerPhrase, Instant.now()));
        save();
        LOG.info("[SELF-LEARNING] Learned new rule: " + trimmed);
    }

    /**
     * Attempts to automatically extract a behavioral or coding rule from natural user input.
     * Returns true if a rule was extracted and recorded.
     */
    public synchronized boolean learnFromCorrection(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        Matcher matcher = CORRECTION_PATTERN.matcher(userInput.trim());
        if (matcher.find()) {
            String rule = userInput.trim();
            addRule(rule, userInput.trim());
            return true;
        }
        return false;
    }

    public synchronized List<LearnedRule> getRules() {
        return Collections.unmodifiableList(new ArrayList<>(rules));
    }

    public synchronized int size() {
        return rules.size();
    }

    public synchronized void clear() {
        rules.clear();
        save();
    }

    /**
     * Formats all active learned rules for injection into the JARVIS system prompt.
     */
    public synchronized String formatRulesForPrompt() {
        if (rules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nUser-defined rules and behavioral preferences (Strictly follow):\n");
        for (LearnedRule r : rules) {
            sb.append("- ").append(r.rule()).append("\n");
        }
        return sb.toString();
    }

    public synchronized void load() {
        rules.clear();
        if (!Files.exists(storagePath)) return;
        try {
            String json = Files.readString(storagePath);
            parseRulesJson(json);
        } catch (Exception e) {
            LOG.warning("[SELF-LEARNING] Failed to load rules: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            Files.writeString(storagePath, toJson());
        } catch (IOException e) {
            LOG.warning("[SELF-LEARNING] Failed to save rules: " + e.getMessage());
        }
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < rules.size(); i++) {
            LearnedRule r = rules.get(i);
            sb.append("  {\n")
              .append("    \"id\": \"").append(r.id()).append("\",\n")
              .append("    \"rule\": \"").append(escape(r.rule())).append("\",\n")
              .append("    \"triggerPhrase\": \"").append(escape(r.triggerPhrase() != null ? r.triggerPhrase() : "")).append("\",\n")
              .append("    \"learnedAt\": \"").append(r.learnedAt().toString()).append("\"\n")
              .append("  }").append(i < rules.size() - 1 ? "," : "").append("\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private void parseRulesJson(String json) {
        if (json == null || json.isBlank()) return;
        Pattern objPattern = Pattern.compile("\\{[^}]*\\}");
        Matcher objMatcher = objPattern.matcher(json);
        while (objMatcher.find()) {
            String block = objMatcher.group();
            String id = extractField(block, "id");
            String rule = extractField(block, "rule");
            String trigger = extractField(block, "triggerPhrase");
            String timeStr = extractField(block, "learnedAt");
            if (rule != null && !rule.isBlank()) {
                Instant time = Instant.now();
                if (timeStr != null) {
                    try { time = Instant.parse(timeStr); } catch (Exception ignored) {}
                }
                rules.add(new LearnedRule(id != null ? id : UUID.randomUUID().toString(), rule, trigger, time));
            }
        }
    }

    private static String extractField(String block, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(block);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
