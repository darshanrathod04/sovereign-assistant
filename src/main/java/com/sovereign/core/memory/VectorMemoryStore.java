package com.sovereign.core.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * <b>VectorMemoryStore</b>
 *
 * <p>Local persistent vector memory for Sovereign Assistant.
 * Embeds facts, observations, and key turns into semantic vectors,
 * supporting Top-K similarity search to ground future reasoning unprompted.</p>
 *
 * <p>Stored at {@code ~/.sovereign/memory/vector-memory.json}.</p>
 */
public class VectorMemoryStore {

    private static final Logger LOG = Logger.getLogger(VectorMemoryStore.class.getName());
    private static final ObjectMapper MAPPER = createMapper();

    public static final Path DEFAULT_MEMORY_PATH =
            Path.of(System.getProperty("user.home"), ".sovereign", "memory", "vector-memory.json");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemoryRecord {
        public String id;
        public String text;
        public String category; // "preference", "fact", "conversation", "code"
        public float[] embedding;
        public Instant createdAt;
        public Instant lastAccessedAt;
        public int accessCount;

        public MemoryRecord() {}

        public MemoryRecord(String id, String text, String category, float[] embedding) {
            this.id = id;
            this.text = text;
            this.category = category;
            this.embedding = embedding;
            this.createdAt = Instant.now();
            this.lastAccessedAt = Instant.now();
            this.accessCount = 1;
        }
    }

    public record SearchResult(MemoryRecord record, float score) {}

    private final Path storagePath;
    private final EmbeddingService embeddingService;
    private final List<MemoryRecord> records = new CopyOnWriteArrayList<>();

    public VectorMemoryStore() {
        this(DEFAULT_MEMORY_PATH, new EmbeddingService());
    }

    public VectorMemoryStore(Path storagePath, EmbeddingService embeddingService) {
        this.storagePath = storagePath != null ? storagePath : DEFAULT_MEMORY_PATH;
        this.embeddingService = embeddingService != null ? embeddingService : new EmbeddingService();
        load();
    }

    /**
     * Stores a new memory item or updates an existing one if identical text exists.
     */
    public synchronized MemoryRecord store(String text, String category) {
        if (text == null || text.isBlank()) return null;
        String normalized = text.trim();

        // Check if identical text already stored
        for (MemoryRecord r : records) {
            if (r.text.equalsIgnoreCase(normalized)) {
                r.accessCount++;
                r.lastAccessedAt = Instant.now();
                save();
                return r;
            }
        }

        float[] vector = embeddingService.embed(normalized);
        MemoryRecord record = new MemoryRecord(UUID.randomUUID().toString(), normalized, category, vector);
        records.add(record);
        save();
        return record;
    }

    /**
     * Performs cosine-similarity semantic search across all stored memory records.
     * Returns the top-K highest scoring memories exceeding {@code minSimilarity}.
     */
    public List<SearchResult> search(String query, int topK, float minSimilarity) {
        if (query == null || query.isBlank() || records.isEmpty()) {
            return Collections.emptyList();
        }
        float[] queryVec = embeddingService.embed(query);

        return records.stream()
                .map(r -> {
                    float sim = EmbeddingService.cosineSimilarity(queryVec, r.embedding);
                    // Boost slightly based on frequency
                    float boosted = sim * (1.0f + Math.min(0.2f, r.accessCount * 0.02f));
                    return new SearchResult(r, boosted);
                })
                .filter(res -> res.score() >= minSimilarity)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(Math.max(1, topK))
                .peek(res -> {
                    res.record().lastAccessedAt = Instant.now();
                    res.record().accessCount++;
                })
                .collect(Collectors.toList());
    }

    public int size() {
        return records.size();
    }

    public List<MemoryRecord> getAllRecords() {
        return Collections.unmodifiableList(records);
    }

    public synchronized void clear() {
        records.clear();
        save();
    }

    public synchronized void save() {
        try {
            if (storagePath.getParent() != null && !Files.exists(storagePath.getParent())) {
                Files.createDirectories(storagePath.getParent());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), records);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[VECTOR MEMORY] Failed to persist memory to " + storagePath + ": " + e.getMessage());
        }
    }

    public synchronized void load() {
        if (!Files.exists(storagePath)) return;
        try {
            MemoryRecord[] loaded = MAPPER.readValue(storagePath.toFile(), MemoryRecord[].class);
            records.clear();
            for (MemoryRecord r : loaded) {
                if (r != null) {
                    if (r.embedding == null || r.embedding.length == 0) {
                        r.embedding = embeddingService.embed(r.text);
                    }
                    records.add(r);
                }
            }
            LOG.info("[VECTOR MEMORY] Loaded " + records.size() + " memories from " + storagePath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[VECTOR MEMORY] Failed to read memory from " + storagePath + ": " + e.getMessage());
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}
