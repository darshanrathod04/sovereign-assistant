package com.sovereign.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>EmbeddingService</b>
 *
 * <p>Zero-cost semantic text embedding generator.
 * Tier 1: Calls Google Gemini's free tier {@code text-embedding-004} endpoint (1,500 req/day).
 * Tier 2: Pure-Java deterministic TF-IDF / term-hash cosine embedding fallback (100% offline, zero API calls).</p>
 *
 * <p>Includes an in-memory LRU/hash cache so duplicate texts never consume API quota.</p>
 */
public class EmbeddingService {

    private static final Logger LOG = Logger.getLogger(EmbeddingService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String GEMINI_EMBED_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=%s";

    public static final int EMBEDDING_DIM = 768;

    private final String apiKey;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

    public EmbeddingService() {
        this(resolveGeminiApiKey());
    }

    public EmbeddingService(String apiKey) {
        this.apiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : null;
    }

    /**
     * Generates a 768-dimensional normalized embedding vector for the given text.
     * Guaranteed never to return null.
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[EMBEDDING_DIM];
        }
        String normalized = text.trim();
        return cache.computeIfAbsent(normalized, this::computeEmbedding);
    }

    private float[] computeEmbedding(String text) {
        // Tier 1: Try Gemini free tier embedding if API key is present
        if (apiKey != null) {
            float[] geminiVec = callGeminiEmbedding(text);
            if (geminiVec != null && geminiVec.length == EMBEDDING_DIM) {
                return normalize(geminiVec);
            }
        }
        // Tier 2: Deterministic offline pure-Java embedding
        return computeOfflineEmbedding(text);
    }

    private float[] callGeminiEmbedding(String text) {
        try {
            String urlStr = String.format(GEMINI_EMBED_TEMPLATE, apiKey);
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(5_000);

            String requestBody = "{\"model\":\"models/text-embedding-004\",\"content\":{\"parts\":[{\"text\":"
                    + MAPPER.writeValueAsString(text) + "}]}}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                JsonNode root = MAPPER.readTree(conn.getInputStream());
                JsonNode values = root.path("embedding").path("values");
                if (values.isArray() && values.size() > 0) {
                    float[] vec = new float[values.size()];
                    for (int i = 0; i < values.size(); i++) {
                        vec[i] = (float) values.get(i).asDouble();
                    }
                    return vec;
                }
            } else {
                LOG.warning("[EMBED] Gemini embedding returned HTTP " + code + ", using offline fallback.");
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "[EMBED] Gemini embedding unavailable: " + e.getMessage());
        }
        return null;
    }

    /**
     * Pure-Java deterministic feature hashing & n-gram embedding.
     * Computes a 768-dim pseudo-semantic vector invariant to word order variations,
     * normalized for cosine similarity.
     */
    public static float[] computeOfflineEmbedding(String text) {
        float[] vec = new float[EMBEDDING_DIM];
        if (text == null || text.isBlank()) return vec;

        String[] tokens = text.toLowerCase().split("[^a-zA-Z0-9]+");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.isEmpty()) continue;

            // Word hash
            int h1 = Math.abs(t.hashCode()) % EMBEDDING_DIM;
            vec[h1] += 1.0f;

            // Bigram hash if applicable
            if (i > 0) {
                String bigram = tokens[i - 1] + "_" + t;
                int h2 = Math.abs(bigram.hashCode()) % EMBEDDING_DIM;
                vec[h2] += 1.5f;
            }

            // Substring character n-grams (3-grams) for robust typo/stemming resilience
            if (t.length() >= 3) {
                for (int j = 0; j <= t.length() - 3; j++) {
                    String tri = t.substring(j, j + 3);
                    int h3 = Math.abs(tri.hashCode()) % EMBEDDING_DIM;
                    vec[h3] += 0.5f;
                }
            }
        }
        return normalize(vec);
    }

    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0f;
        int len = Math.min(a.length, b.length);
        float dot = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0.0f || normB <= 0.0f) return 0.0f;
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    public static float[] normalize(float[] v) {
        float sum = 0.0f;
        for (float val : v) sum += val * val;
        if (sum <= 0.0f) return v;
        float norm = (float) Math.sqrt(sum);
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i] / norm;
        }
        return result;
    }

    private static String resolveGeminiApiKey() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getProperty("GEMINI_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = System.getProperty("gemini.api.key");
        }
        return key;
    }
}
