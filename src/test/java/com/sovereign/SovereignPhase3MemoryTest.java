package com.sovereign;

import com.sovereign.core.client.SovereignClient;
import com.sovereign.core.memory.EpisodicSessionLedger;
import com.sovereign.core.memory.ProceduralSkillStore;
import com.sovereign.core.memory.UserMemoryProfile;
import com.sovereign.core.react.model.ExecutionPlan;
import com.sovereign.core.react.model.GoalTask;
import com.sovereign.core.react.planner.GoalDecomposer;
import com.sovereign.core.workspace.WorkspaceContext;
import com.sovereign.core.workspace.WorkspaceContextIndexer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignPhase3MemoryTest</b>
 *
 * <p>Phase 3 Verification Test Suite validating Hierarchical Memory,
 * Workspace Intelligence, Project Context Indexing, and Context-Aware Goal Disambiguation.</p>
 */
public class SovereignPhase3MemoryTest {

    @Test
    @DisplayName("Test 1: UserMemoryProfile persists and recalls user preferences and custom aliases")
    void testUserMemoryProfilePersistenceAndRecall(@TempDir Path tempDir) throws IOException {
        UserMemoryProfile profile = new UserMemoryProfile();
        profile.setPreferredShell("bash");
        profile.setPreferredEditor("nvim");
        profile.addFavoriteProject("D:/projects/sovereign");
        profile.setAlias("clean-build", "mvn clean compile");
        profile.setAlias("test-all", "mvn test");

        Path savePath = tempDir.resolve("profile.json");
        profile.saveToFile(savePath);
        assertThat(savePath).exists();

        UserMemoryProfile loaded = UserMemoryProfile.loadFromFile(savePath);
        assertThat(loaded.getPreferredShell()).isEqualTo("bash");
        assertThat(loaded.getPreferredEditor()).isEqualTo("nvim");
        assertThat(loaded.getFavoriteProjects()).contains("D:/projects/sovereign");
        assertThat(loaded.getAlias("clean-build")).contains("mvn clean compile");
        assertThat(loaded.getAlias("test-all")).contains("mvn test");
    }

    @Test
    @DisplayName("Test 2: EpisodicSessionLedger stores goals in MemorySDK and retrieves execution history")
    void testEpisodicSessionLedgerWithMemorySdk() {
        try (SovereignClient client = SovereignClient.create()) {
            EpisodicSessionLedger ledger = new EpisodicSessionLedger(client.getMemorySdk());

            GoalTask goal1 = new GoalTask("goal-alpha", "Compile and verify build");
            ledger.recordGoal(goal1, true, 2, 0, "Build succeeded", 120);

            GoalTask goal2 = new GoalTask("goal-beta", "Run system diagnostic checks");
            ledger.recordGoal(goal2, true, 1, 0, "Diagnostic completed", 85);

            List<EpisodicSessionLedger.EpisodicEntry> recent = ledger.getRecentGoals(5);
            assertThat(recent).hasSize(2);
            assertThat(recent.get(0).goalId()).isEqualTo("goal-beta");
            assertThat(recent.get(1).goalId()).isEqualTo("goal-alpha");

            Optional<EpisodicSessionLedger.EpisodicEntry> recalled = ledger.recallGoal("goal-alpha");
            assertThat(recalled).isPresent();
            assertThat(recalled.get().description()).isEqualTo("Compile and verify build");
            assertThat(recalled.get().success()).isTrue();
        }
    }

    @Test
    @DisplayName("Test 3: WorkspaceContextIndexer accurately detects Maven project metadata and source paths")
    void testWorkspaceContextIndexerDetectsMavenMetadata(@TempDir Path tempRepo) throws IOException {
        String pomContent = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample</groupId>
                    <artifactId>sample-service</artifactId>
                    <version>2.4.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(tempRepo.resolve("pom.xml"), pomContent);
        Files.createDirectories(tempRepo.resolve("src/main/java"));
        Files.createDirectories(tempRepo.resolve("src/test/java"));

        WorkspaceContextIndexer indexer = new WorkspaceContextIndexer(tempRepo);
        WorkspaceContext context = indexer.getContext();

        assertThat(context).isNotNull();
        assertThat(context.projectName()).isEqualTo("sample-service");
        assertThat(context.projectVersion()).isEqualTo("2.4.0");
        assertThat(context.buildTool()).isEqualTo("Maven");
        assertThat(context.detectedFramework()).isEqualTo("Spring Boot");
        assertThat(context.isMaven()).isTrue();
        assertThat(context.getTestCommand()).isEqualTo("mvn test");
        assertThat(context.getBuildCommand()).isEqualTo("mvn compile");
        assertThat(context.sourceDirectories()).contains("src/main/java", "src/test/java");
    }

    @Test
    @DisplayName("Test 4: GoalDecomposer leverages workspace context to resolve ambiguous goals")
    void testGoalDecomposerLeveragesWorkspaceContext(@TempDir Path tempRepo) throws IOException {
        Files.writeString(tempRepo.resolve("pom.xml"), "<project><artifactId>maven-app</artifactId></project>");
        WorkspaceContextIndexer mavenIndexer = new WorkspaceContextIndexer(tempRepo);

        UserMemoryProfile profile = new UserMemoryProfile();
        profile.setAlias("custom-check", "echo \"custom alias executed\"");

        ProceduralSkillStore skillStore = new ProceduralSkillStore();
        skillStore.registerSkill("clean-sync", "git fetch && git merge");

        GoalDecomposer decomposer = new GoalDecomposer(null, mavenIndexer, profile, skillStore);

        // Ambiguous goal resolved to mvn test based on Maven workspace
        ExecutionPlan testPlan = decomposer.decompose("run tests");
        assertThat(testPlan.steps()).hasSize(1);
        assertThat(testPlan.steps().get(0).toolName()).isEqualTo("shell_exec");
        assertThat(testPlan.steps().get(0).parameters().get("command")).isEqualTo("mvn test");

        // Ambiguous build resolved to mvn compile
        ExecutionPlan buildPlan = decomposer.decompose("build project");
        assertThat(buildPlan.steps().get(0).parameters().get("command")).isEqualTo("mvn compile");

        // Custom alias resolution from UserMemoryProfile
        ExecutionPlan aliasPlan = decomposer.decompose("custom-check");
        assertThat(aliasPlan.steps().get(0).parameters().get("command")).isEqualTo("echo \"custom alias executed\"");

        // Procedural skill resolution from ProceduralSkillStore
        ExecutionPlan skillPlan = decomposer.decompose("clean-sync");
        assertThat(skillPlan.steps().get(0).parameters().get("command")).isEqualTo("git fetch && git merge");
    }
}
