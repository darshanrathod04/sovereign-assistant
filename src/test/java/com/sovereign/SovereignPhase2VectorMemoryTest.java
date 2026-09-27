package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.memory.EmbeddingService;
import com.sovereign.core.memory.KnowledgeGraphStore;
import com.sovereign.core.memory.VectorMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignPhase2VectorMemoryTest</b>
 *
 * <p>Phase 2 Verification Test Suite validating zero-cost local semantic vector memory,
 * cosine similarity search, offline embedding fallback, and relational knowledge graph triples.</p>
 */
public class SovereignPhase2VectorMemoryTest {

    @Test
    @DisplayName("Test 1: EmbeddingService generates normalized 768-dim offline vectors and cosine similarity")
    void testEmbeddingServiceOfflineAndSimilarity() {
        EmbeddingService service = new EmbeddingService(null); // Force offline mode

        float[] vecJava = service.embed("Java programming language with garbage collection");
        float[] vecKotlin = service.embed("Kotlin modern programming language on JVM");
        float[] vecCooking = service.embed("Italian pasta recipe with tomato and cheese");

        assertThat(vecJava).hasSize(EmbeddingService.EMBEDDING_DIM);
        assertThat(vecKotlin).hasSize(EmbeddingService.EMBEDDING_DIM);
        assertThat(vecCooking).hasSize(EmbeddingService.EMBEDDING_DIM);

        float simJavaKotlin = EmbeddingService.cosineSimilarity(vecJava, vecKotlin);
        float simJavaCooking = EmbeddingService.cosineSimilarity(vecJava, vecCooking);

        // Java and Kotlin should share much higher semantic overlap than Java and Pasta cooking
        assertThat(simJavaKotlin).isGreaterThan(simJavaCooking);
        assertThat(simJavaKotlin).isGreaterThan(0.2f);
    }

    @Test
    @DisplayName("Test 2: VectorMemoryStore stores, searches, and ranks memories semantically")
    void testVectorMemoryStoreSearchAndPersistence(@TempDir Path tempDir) {
        Path storePath = tempDir.resolve("test-vectors.json");
        VectorMemoryStore store = new VectorMemoryStore(storePath, new EmbeddingService(null));

        store.store("Darshan prefers developing with VS Code and Maven", "preference");
        store.store("The Sovereign Assistant runs locally on Windows OS", "system");
        store.store("Spring Boot microservice architecture with REST endpoints", "architecture");

        assertThat(store.size()).isEqualTo(3);
        assertThat(storePath).exists();

        // Search for IDE preference
        List<VectorMemoryStore.SearchResult> results = store.search("What editor or IDE does Darshan prefer?", 2, 0.15f);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).record().text).contains("VS Code");

        // Verify reload from disk
        VectorMemoryStore reloaded = new VectorMemoryStore(storePath, new EmbeddingService(null));
        assertThat(reloaded.size()).isEqualTo(3);
        List<VectorMemoryStore.SearchResult> reloadedResults = reloaded.search("Windows OS Assistant", 1, 0.10f);
        assertThat(reloadedResults).isNotEmpty();
        assertThat(reloadedResults.get(0).record().text).contains("Windows OS");
    }

    @Test
    @DisplayName("Test 3: KnowledgeGraphStore records triples, retrieves by subject and matches text")
    void testKnowledgeGraphStoreRelationalFacts(@TempDir Path tempDir) {
        Path graphPath = tempDir.resolve("test-knowledge.json");
        KnowledgeGraphStore graph = new KnowledgeGraphStore(graphPath);

        graph.addFact("Darshan", "created", "Sovereign Assistant", 1.0);
        graph.addFact("Sovereign", "runs_on", "Windows", 0.95);
        graph.addFact("Sovereign", "build_tool", "Maven", 1.0);

        assertThat(graph.size()).isEqualTo(3);
        assertThat(graphPath).exists();

        List<KnowledgeGraphStore.FactTriple> darshanFacts = graph.getFactsForSubject("Darshan");
        assertThat(darshanFacts).hasSize(1);
        assertThat(darshanFacts.get(0).predicate).isEqualTo("created");

        List<KnowledgeGraphStore.FactTriple> matches = graph.findFactsMatching("Maven");
        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).subject).isEqualTo("Sovereign");

        // Verify persistence reload
        KnowledgeGraphStore reloaded = new KnowledgeGraphStore(graphPath);
        assertThat(reloaded.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("Test 4: SovereignReplRunner builds grounded context containing semantic recall and facts")
    void testReplRunnerGroundedContextWithMemory() {
        SovereignReplRunner runner = new SovereignReplRunner(ProviderConfig.of(null, null));

        String grounded = runner.buildChatGroundingContext("editor preference");
        assertThat(grounded)
                .contains("Preferred Editor:")
                .contains("Active Workspace:");
    }
}
