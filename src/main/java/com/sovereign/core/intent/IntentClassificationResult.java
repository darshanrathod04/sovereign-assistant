package com.sovereign.core.intent;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * <b>IntentClassificationResult</b>
 *
 * <p>Structured result produced by {@link IntentRouter} containing classified intent,
 * normalized query, confidence score, and extracted entities.</p>
 */
public record IntentClassificationResult(
        UserIntentType intentType,
        String normalizedQuery,
        double confidence,
        Map<String, String> entities
) {
    public IntentClassificationResult {
        Objects.requireNonNull(intentType, "intentType must not be null");
        normalizedQuery = normalizedQuery != null ? normalizedQuery.trim() : "";
        entities = entities != null ? Collections.unmodifiableMap(entities) : Collections.emptyMap();
    }

    public static IntentClassificationResult of(UserIntentType intentType, String normalizedQuery, double confidence) {
        return new IntentClassificationResult(intentType, normalizedQuery, confidence, Collections.emptyMap());
    }

    public static IntentClassificationResult of(UserIntentType intentType, String normalizedQuery, double confidence, Map<String, String> entities) {
        return new IntentClassificationResult(intentType, normalizedQuery, confidence, entities);
    }
}
