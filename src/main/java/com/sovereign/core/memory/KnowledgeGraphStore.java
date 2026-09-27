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
 * <b>KnowledgeGraphStore</b>
 *
 * <p>Stores structured relational facts (Subject-Predicate-Object triples) about
 * the user, environment, projects, and domain concepts.</p>
 *
 * <p>Persisted to {@code ~/.sovereign/memory/knowledge-graph.json}.</p>
 */
public class KnowledgeGraphStore {

    private static final Logger LOG = Logger.getLogger(KnowledgeGraphStore.class.getName());
    private static final ObjectMapper MAPPER = createMapper();

    public static final Path DEFAULT_GRAPH_PATH =
            Path.of(System.getProperty("user.home"), ".sovereign", "memory", "knowledge-graph.json");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FactTriple {
        public String id;
        public String subject;
        public String predicate;
        public String object;
        public double confidence;
        public Instant createdAt;

        public FactTriple() {}

        public FactTriple(String subject, String predicate, String object, double confidence) {
            this.id = UUID.randomUUID().toString();
            this.subject = subject != null ? subject.trim() : "";
            this.predicate = predicate != null ? predicate.trim() : "";
            this.object = object != null ? object.trim() : "";
            this.confidence = confidence;
            this.createdAt = Instant.now();
        }

        @Override
        public String toString() {
            return "(" + subject + " -> " + predicate + " -> " + object + ")";
        }
    }

    private final Path storagePath;
    private final List<FactTriple> facts = new CopyOnWriteArrayList<>();

    public KnowledgeGraphStore() {
        this(DEFAULT_GRAPH_PATH);
    }

    public KnowledgeGraphStore(Path storagePath) {
        this.storagePath = storagePath != null ? storagePath : DEFAULT_GRAPH_PATH;
        load();
    }

    /**
     * Inserts or updates a fact triple.
     */
    public synchronized FactTriple addFact(String subject, String predicate, String object, double confidence) {
        if (subject == null || predicate == null || object == null) return null;
        String s = subject.trim();
        String p = predicate.trim();
        String o = object.trim();

        // Update if subject and predicate already exist
        for (FactTriple f : facts) {
            if (f.subject.equalsIgnoreCase(s) && f.predicate.equalsIgnoreCase(p)) {
                f.object = o;
                f.confidence = Math.max(f.confidence, confidence);
                f.createdAt = Instant.now();
                save();
                return f;
            }
        }

        FactTriple triple = new FactTriple(s, p, o, confidence);
        facts.add(triple);
        save();
        return triple;
    }

    public List<FactTriple> getFactsForSubject(String subject) {
        if (subject == null) return Collections.emptyList();
        String s = subject.trim().toLowerCase();
        return facts.stream()
                .filter(f -> f.subject.toLowerCase().contains(s))
                .collect(Collectors.toList());
    }

    public List<FactTriple> findFactsMatching(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        String query = text.toLowerCase();
        return facts.stream()
                .filter(f -> f.subject.toLowerCase().contains(query)
                        || f.predicate.toLowerCase().contains(query)
                        || f.object.toLowerCase().contains(query))
                .collect(Collectors.toList());
    }

    public List<FactTriple> getAllFacts() {
        return Collections.unmodifiableList(facts);
    }

    public int size() {
        return facts.size();
    }

    public synchronized void clear() {
        facts.clear();
        save();
    }

    public synchronized void save() {
        try {
            if (storagePath.getParent() != null && !Files.exists(storagePath.getParent())) {
                Files.createDirectories(storagePath.getParent());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), facts);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[KNOWLEDGE GRAPH] Failed to save facts: " + e.getMessage());
        }
    }

    public synchronized void load() {
        if (!Files.exists(storagePath)) return;
        try {
            FactTriple[] loaded = MAPPER.readValue(storagePath.toFile(), FactTriple[].class);
            facts.clear();
            facts.addAll(Arrays.asList(loaded));
            LOG.info("[KNOWLEDGE GRAPH] Loaded " + facts.size() + " facts from " + storagePath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[KNOWLEDGE GRAPH] Failed to load facts: " + e.getMessage());
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}
