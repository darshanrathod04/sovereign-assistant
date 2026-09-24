package com.sovereign.core.memory;

import java.time.Instant;

/**
 * <b>ConversationTurn</b>
 *
 * <p>Immutable record representing a single exchange turn in a multi-turn conversation.
 * Used by {@link ConversationContextWindow} to maintain a rolling history of the
 * session's dialogue so the LLM receives full context on every call.</p>
 *
 * <ul>
 *   <li>{@code role}      — {@code "user"} or {@code "assistant"}</li>
 *   <li>{@code content}   — the raw text of the message</li>
 *   <li>{@code timestamp} — wall-clock time of the message (ISO-8601)</li>
 * </ul>
 */
public record ConversationTurn(String role, String content, Instant timestamp) {

    /** Creates a user turn timestamped now. */
    public static ConversationTurn user(String content) {
        return new ConversationTurn("user", content, Instant.now());
    }

    /** Creates an assistant turn timestamped now. */
    public static ConversationTurn assistant(String content) {
        return new ConversationTurn("assistant", content, Instant.now());
    }

    /** Returns a compact single-line representation for debug logging. */
    @Override
    public String toString() {
        String preview = content != null && content.length() > 80
                ? content.substring(0, 77) + "..." : content;
        return "[" + role + " @ " + timestamp + "] " + preview;
    }
}
