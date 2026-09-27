package com.sovereign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sovereign.core.client.GeminiChatProvider;
import com.sovereign.core.client.OllamaProvider;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.memory.ConversationContextWindow;
import com.sovereign.core.memory.ConversationTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic, network-free verification for the zero-cost Phase 1 providers. */
public class SovereignPhase1ConversationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Gemini request uses true multi-turn contents and avoids duplicating the current turn")
    void geminiRequestSerializesStructuredHistoryOnce() throws Exception {
        GeminiChatProvider provider = new GeminiChatProvider("test-key-not-a-real-secret", "test-model");
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("Remember that my project uses Maven."),
                ConversationTurn.assistant("I will remember that."),
                ConversationTurn.user("What does it use?")
        );

        String request = provider.buildRequestJson("You are Sovereign.", "What does it use?", history);
        JsonNode root = MAPPER.readTree(request);

        assertThat(root.path("system_instruction").path("parts").get(0).path("text").asText())
                .isEqualTo("You are Sovereign.");
        assertThat(root.path("contents")).hasSize(3);
        assertThat(root.path("contents").get(0).path("role").asText()).isEqualTo("user");
        assertThat(root.path("contents").get(1).path("role").asText()).isEqualTo("model");
        assertThat(root.path("contents").get(2).path("parts").get(0).path("text").asText())
                .isEqualTo("What does it use?");
        assertThat(MAPPER.readTree(request).toString().split("What does it use\\?", -1)).hasSize(2);
        assertThat(request).doesNotContain("test-key-not-a-real-secret");
    }

    @Test
    @DisplayName("Ollama request preserves messages and avoids duplicating the current turn")
    void ollamaRequestSerializesMessagesOnce() throws Exception {
        OllamaProvider provider = new OllamaProvider("test-local-model");
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("First question"),
                ConversationTurn.assistant("First answer"),
                ConversationTurn.user("Follow-up")
        );

        String request = provider.buildChatRequest("You are Sovereign.", "Follow-up", history);
        JsonNode root = MAPPER.readTree(request);

        assertThat(root.path("model").asText()).isEqualTo("test-local-model");
        assertThat(root.path("messages")).hasSize(4);
        assertThat(root.path("messages").get(3).path("role").asText()).isEqualTo("user");
        assertThat(root.path("messages").get(3).path("content").asText()).isEqualTo("Follow-up");
        assertThat(MAPPER.readTree(request).toString().split("Follow-up", -1)).hasSize(2);
    }

    @Test
    @DisplayName("Conversation window rolls at twenty turns and maps assistant turns to Gemini model")
    void contextWindowRollsAndMapsGeminiRoles() {
        ConversationContextWindow window = new ConversationContextWindow(20);
        // Add 22 pairs (44 turns total). Window of size 20 keeps the last 10 pairs (turns 25 to 44).
        for (int i = 1; i <= 22; i++) {
            window.addUser("Question " + i);
            window.addAssistant("Answer " + i);
        }

        assertThat(window.getTurns()).hasSize(20);
        // Turn 25 is user "Question 13"
        assertThat(window.getTurns().get(0).content()).isEqualTo("Question 13");
        // Turn 44 is assistant "Answer 22"
        assertThat(window.getTurns().get(19).content()).isEqualTo("Answer 22");
        assertThat(ConversationContextWindow.toGeminiContentsJson(window.getTurns()))
                .contains("\"role\":\"model\"")
                .contains("Answer 22");
    }

    @Test
    @DisplayName("Zero-cost provider fallback: OllamaProvider and GeminiChatProvider default to free models")
    void zeroCostProvidersDefaultToFreeModels() {
        GeminiChatProvider gemini = new GeminiChatProvider("test-gemini-key");
        assertThat(gemini.isConfigured()).isTrue();
        assertThat(gemini.getModel()).isEqualTo("gemini-2.5-flash");

        OllamaProvider ollama = new OllamaProvider();
        assertThat(ollama.getModel()).isEqualTo("phi4-mini");
    }
}
