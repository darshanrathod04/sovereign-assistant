package com.sovereign.core.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <b>ConversationContextWindow</b>
 *
 * <p>Maintains a bounded rolling window of conversation turns for a single session.
 * Every user query and assistant reply is appended here so the LLM receives the
 * full dialogue history on every call — enabling true multi-turn, coherent conversation.</p>
 *
 * <p>When the window exceeds {@code maxTurns}, the oldest turns are evicted (FIFO).
 * The default window size is 20 turns (~10 exchanges), configurable via
 * the {@code sovereign.context.window} JVM property.</p>
 *
 * <h3>Usage in ReasoningSDK</h3>
 * <pre>{@code
 *   window.addUser("What is Java?");
 *   String reply = reasoning.analyze("What is Java?", window.toContextString());
 *   window.addAssistant(reply);
 * }</pre>
 */
public class ConversationContextWindow {

    /** Default rolling window size (turns, not exchanges). */
    public static final int DEFAULT_MAX_TURNS = resolveMaxTurns();

    private final int maxTurns;
    private final List<ConversationTurn> turns;

    public ConversationContextWindow() {
        this(DEFAULT_MAX_TURNS);
    }

    public ConversationContextWindow(int maxTurns) {
        this.maxTurns = Math.max(2, maxTurns); // minimum 1 exchange
        this.turns = new ArrayList<>();
    }

    // ─── Mutation ────────────────────────────────────────────────────────────

    /** Records a user message in the context window. */
    public synchronized void addUser(String content) {
        if (content != null && !content.isBlank()) {
            evictIfFull();
            turns.add(ConversationTurn.user(content));
        }
    }

    /** Records an assistant reply in the context window. */
    public synchronized void addAssistant(String content) {
        if (content != null && !content.isBlank()) {
            evictIfFull();
            turns.add(ConversationTurn.assistant(content));
        }
    }

    /** Clears all turns (e.g. on session reset). */
    public synchronized void clear() {
        turns.clear();
    }

    // ─── Read ────────────────────────────────────────────────────────────────

    /** Returns an unmodifiable snapshot of the current turns. */
    public synchronized List<ConversationTurn> getTurns() {
        return Collections.unmodifiableList(new ArrayList<>(turns));
    }

    /** Returns the number of turns currently in the window. */
    public synchronized int size() {
        return turns.size();
    }

    /** Returns {@code true} if no turns have been recorded yet. */
    public synchronized boolean isEmpty() {
        return turns.isEmpty();
    }

    /**
     * Renders the conversation history as a plain-text context block suitable
     * for injection into an LLM prompt's {@code [CONTEXT]} section.
     *
     * <p>Example output:</p>
     * <pre>
     * [Previous Conversation]
     * user: what is java?
     * assistant: Java is a compiled, object-oriented…
     * user: how does garbage collection work?
     * </pre>
     */
    public synchronized String toContextString() {
        if (turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[Previous Conversation]\n");
        for (ConversationTurn t : turns) {
            sb.append(t.role()).append(": ").append(t.content()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Builds a Gemini-style {@code contents[]} JSON array string from the turn history.
     * This is injected directly into the Gemini generateContent REST payload so the
     * model receives structured role-based multi-turn context.
     *
     * <p>Format:</p>
     * <pre>
     * [{"role":"user","parts":[{"text":"..."}]},
     *  {"role":"model","parts":[{"text":"..."}]},
     *  ...]
     * </pre>
     */
    public synchronized String toGeminiContentsJson() {
        if (turns.isEmpty()) return "[]";
        return turns.stream()
                .map(t -> {
                    // Gemini uses "model" not "assistant"
                    String geminiRole = "assistant".equals(t.role()) ? "model" : "user";
                    String escaped = escapeJson(t.content());
                    return "{\"role\":\"" + geminiRole + "\",\"parts\":[{\"text\":\"" + escaped + "\"}]}";
                })
                .collect(Collectors.joining(",\n", "[\n", "\n]"));
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    private void evictIfFull() {
        while (turns.size() >= maxTurns) {
            turns.remove(0);
        }
    }

    private static int resolveMaxTurns() {
        try {
            String prop = System.getProperty("sovereign.context.window");
            if (prop != null && !prop.isBlank()) {
                return Integer.parseInt(prop.trim());
            }
        } catch (NumberFormatException ignored) {}
        return 20;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public String toString() {
        return "ConversationContextWindow{turns=" + turns.size() + "/" + maxTurns + "}";
    }
}
