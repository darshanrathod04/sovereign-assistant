package com.sovereign.core.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>SessionStore</b>
 *
 * <p>Persists conversation history across JVM restarts by serialising
 * {@link ConversationTurn} records to a JSON file in {@code ~/.sovereign/sessions/}.
 * On startup, the most recent session is automatically loaded so Sovereign
 * remembers what was discussed in the previous run.</p>
 *
 * <h3>Session file layout</h3>
 * <pre>~/.sovereign/sessions/session-&lt;UUID&gt;.json</pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   SessionStore store = SessionStore.loadOrCreate();
 *   store.append(ConversationTurn.user("hello"));
 *   store.append(ConversationTurn.assistant("Hi! How can I help?"));
 *   store.save();
 * }</pre>
 */
public class SessionStore {

    private static final Logger LOG = Logger.getLogger(SessionStore.class.getName());
    private static final ObjectMapper MAPPER = createMapper();

    public static final Path SESSIONS_DIR =
            Path.of(System.getProperty("user.home"), ".sovereign", "sessions");

    /** Maximum number of turns to keep in a persisted session file. */
    private static final int MAX_PERSISTED_TURNS = 100;

    // ─── Serializable DTO ────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionData {
        public String sessionId;
        public Instant createdAt;
        public Instant updatedAt;
        public List<TurnDto> turns = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TurnDto {
        public String role;
        public String content;
        public Instant timestamp;
    }

    // ─── Fields ──────────────────────────────────────────────────────────────

    private final SessionData data;
    private final Path filePath;

    private SessionStore(SessionData data, Path filePath) {
        this.data = data;
        this.filePath = filePath;
    }

    // ─── Factory ─────────────────────────────────────────────────────────────

    /**
     * Loads the most recent session from disk, or creates a fresh one if none exists.
     * Never throws — falls back to a new in-memory session on any I/O error.
     */
    public static SessionStore loadOrCreate() {
        try {
            Files.createDirectories(SESSIONS_DIR);
            Path latest = findLatestSessionFile();
            if (latest != null) {
                SessionData data = MAPPER.readValue(latest.toFile(), SessionData.class);
                LOG.info("[SESSION] Resumed session " + data.sessionId
                        + " (" + data.turns.size() + " turns)");
                return new SessionStore(data, latest);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[SESSION] Could not load session: " + e.getMessage());
        }
        return createFresh();
    }

    /** Creates a fresh session with a new UUID (does not write to disk yet). */
    public static SessionStore createFresh() {
        SessionData data = new SessionData();
        data.sessionId = UUID.randomUUID().toString();
        data.createdAt = Instant.now();
        data.updatedAt = Instant.now();
        Path filePath = SESSIONS_DIR.resolve("session-" + data.sessionId + ".json");
        return new SessionStore(data, filePath);
    }

    // ─── Mutation ────────────────────────────────────────────────────────────

    /** Appends a turn to the session and marks it as dirty. */
    public synchronized void append(ConversationTurn turn) {
        if (turn == null) return;
        TurnDto dto = new TurnDto();
        dto.role = turn.role();
        dto.content = turn.content();
        dto.timestamp = turn.timestamp();
        data.turns.add(dto);
        data.updatedAt = Instant.now();

        // Trim to max to avoid unbounded growth
        while (data.turns.size() > MAX_PERSISTED_TURNS) {
            data.turns.remove(0);
        }
    }

    /**
     * Saves the current session to disk. Silent on error — session persistence
     * must never crash the main application.
     */
    public synchronized void save() {
        try {
            Files.createDirectories(SESSIONS_DIR);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), data);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[SESSION] Could not save session: " + e.getMessage());
        }
    }

    // ─── Read ────────────────────────────────────────────────────────────────

    /**
     * Returns all stored turns as {@link ConversationTurn} records, ready for
     * injection into a {@link ConversationContextWindow}.
     */
    public synchronized List<ConversationTurn> loadTurns() {
        List<ConversationTurn> result = new ArrayList<>();
        for (TurnDto dto : data.turns) {
            if (dto.role != null && dto.content != null) {
                result.add(new ConversationTurn(dto.role, dto.content,
                        dto.timestamp != null ? dto.timestamp : Instant.now()));
            }
        }
        return result;
    }

    public String getSessionId() { return data.sessionId; }
    public Instant getCreatedAt() { return data.createdAt; }
    public int getTurnCount() { return data.turns.size(); }
    public Path getFilePath() { return filePath; }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private static Path findLatestSessionFile() throws IOException {
        if (!Files.exists(SESSIONS_DIR)) return null;
        return Files.list(SESSIONS_DIR)
                .filter(p -> p.getFileName().toString().startsWith("session-")
                        && p.getFileName().toString().endsWith(".json"))
                .max((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(a)
                                .compareTo(Files.getLastModifiedTime(b));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .orElse(null);
    }

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}
