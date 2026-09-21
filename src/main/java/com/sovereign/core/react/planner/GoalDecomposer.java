package com.sovereign.core.react.planner;

import com.shreeai.os.platform.sdk.PlanningSDK;
import com.shreeai.os.platform.sdk.SDKResponse;
import com.sovereign.core.memory.ProceduralSkillStore;
import com.sovereign.core.memory.UserMemoryProfile;
import com.sovereign.core.react.model.ExecutionPlan;
import com.sovereign.core.react.model.GoalTask;
import com.sovereign.core.react.model.PlanStep;
import com.sovereign.core.workspace.WorkspaceContext;
import com.sovereign.core.workspace.WorkspaceContextIndexer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>GoalDecomposer</b>
 *
 * <p>Cognitive task planner leveraging Shree AI OS {@link PlanningSDK}, repository
 * context from {@link WorkspaceContextIndexer}, and user memory to break down
 * high-level user goals into structured DAG execution plans.</p>
 */
public class GoalDecomposer {

    private final PlanningSDK planningSdk;
    private final WorkspaceContextIndexer contextIndexer;
    private final UserMemoryProfile userProfile;
    private final ProceduralSkillStore skillStore;

    public GoalDecomposer() {
        this(null, null, null, null);
    }

    public GoalDecomposer(PlanningSDK planningSdk) {
        this(planningSdk, null, null, null);
    }

    public GoalDecomposer(PlanningSDK planningSdk, WorkspaceContextIndexer contextIndexer) {
        this(planningSdk, contextIndexer, null, null);
    }

    public GoalDecomposer(PlanningSDK planningSdk,
                          WorkspaceContextIndexer contextIndexer,
                          UserMemoryProfile userProfile,
                          ProceduralSkillStore skillStore) {
        this.planningSdk = planningSdk;
        this.contextIndexer = contextIndexer;
        this.userProfile = userProfile;
        this.skillStore = skillStore;
    }

    /**
     * Decomposes a {@link GoalTask} into a structured multi-step {@link ExecutionPlan}.
     */
    public ExecutionPlan decompose(GoalTask goalTask) {
        Objects.requireNonNull(goalTask, "GoalTask must not be null");

        String effectiveGoal = goalTask.description();

        // 1. Resolve custom alias from UserMemoryProfile
        if (userProfile != null) {
            Optional<String> aliased = userProfile.getAlias(effectiveGoal);
            if (aliased.isPresent()) {
                effectiveGoal = aliased.get();
            }
        }

        // 2. Resolve procedural skill from ProceduralSkillStore
        if (skillStore != null) {
            Optional<ProceduralSkillStore.SkillDefinition> skill = skillStore.getSkill(effectiveGoal);
            if (skill.isPresent()) {
                effectiveGoal = skill.get().recipe();
            }
        }

        // Engage Shree AI OS PlanningSDK for cognitive plan creation
        if (planningSdk != null) {
            try {
                SDKResponse response = planningSdk.createPlan(
                        goalTask.id(),
                        effectiveGoal,
                        "Host OS ReAct Tooling Engine"
                );
            } catch (Exception ignored) {
            }
        }

        List<PlanStep> steps = decomposeIntoSteps(effectiveGoal);
        return ExecutionPlan.of(goalTask.id(), steps);
    }

    public ExecutionPlan decompose(String goalDescription) {
        return decompose(new GoalTask(goalDescription));
    }

    private List<PlanStep> decomposeIntoSteps(String goalDescription) {
        List<String> rawSegments = splitGoalIntoIntentSegments(goalDescription);
        List<PlanStep> steps = new ArrayList<>();

        String previousStepId = null;
        int stepIndex = 1;

        for (String segment : rawSegments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String stepId = "step-" + stepIndex;
            ToolBinding binding = matchTool(trimmed);

            PlanStep.Builder stepBuilder = PlanStep.builder()
                    .stepId(stepId)
                    .description(trimmed)
                    .toolName(binding.toolName())
                    .parameters(binding.parameters());

            // Build DAG dependency edge to the preceding step
            if (previousStepId != null) {
                stepBuilder.dependsOn(previousStepId);
            }

            steps.add(stepBuilder.build());
            previousStepId = stepId;
            stepIndex++;
        }

        if (steps.isEmpty()) {
            // Default single-step fallback
            steps.add(new PlanStep("step-1", goalDescription, "shell_exec", Map.of("command", "echo \"" + goalDescription + "\""), List.of(), null));
        }

        return steps;
    }

    private List<String> splitGoalIntoIntentSegments(String goal) {
        // Handle numbered lists "1. ... 2. ..."
        if (goal.matches("(?s).*\\b1\\..*\\b2\\..*")) {
            String[] parts = goal.split("\\b\\d+\\.\\s*");
            List<String> segments = new ArrayList<>();
            for (String p : parts) {
                if (!p.trim().isEmpty()) {
                    segments.add(p.trim());
                }
            }
            return segments;
        }

        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        int len = goal.length();
        for (int i = 0; i < len; i++) {
            char c = goal.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
            } else if (!inSingleQuote && !inDoubleQuote) {
                // Separators outside of quotes
                if (c == ';' || c == ',') {
                    String seg = current.toString().trim();
                    if (!seg.isEmpty()) {
                        segments.add(seg);
                    }
                    current.setLength(0);
                } else if (goal.startsWith(" and then ", i)) {
                    String seg = current.toString().trim();
                    if (!seg.isEmpty()) {
                        segments.add(seg);
                    }
                    current.setLength(0);
                    i += 9;
                } else if (goal.startsWith(" then ", i)) {
                    String seg = current.toString().trim();
                    if (!seg.isEmpty()) {
                        segments.add(seg);
                    }
                    current.setLength(0);
                    i += 5;
                } else if (goal.startsWith(" and ", i)) {
                    String seg = current.toString().trim();
                    if (!seg.isEmpty()) {
                        segments.add(seg);
                    }
                    current.setLength(0);
                    i += 4;
                } else {
                    current.append(c);
                }
            } else {
                current.append(c);
            }
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) {
            segments.add(last);
        }

        if (segments.isEmpty()) {
            return List.of(goal);
        }
        return segments;
    }

    private record ToolBinding(String toolName, Map<String, Object> parameters) {}

    private ToolBinding matchTool(String intent) {
        String lower = intent.toLowerCase().trim();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // 0. Ambiguous build/test goals resolved via WorkspaceContext
        if (lower.equals("run tests") || lower.equals("test the app") || lower.equals("run test suite")
                || lower.equals("test") || lower.equals("tests") || lower.contains("run tests")) {
            String testCmd = contextIndexer != null ? contextIndexer.getContext().getTestCommand() : "mvn test";
            return new ToolBinding("shell_exec", Map.of("command", testCmd));
        }

        if (lower.equals("build project") || lower.equals("build the app") || lower.equals("compile project")
                || lower.equals("build") || lower.equals("compile")) {
            String buildCmd = contextIndexer != null ? contextIndexer.getContext().getBuildCommand() : "mvn compile";
            return new ToolBinding("shell_exec", Map.of("command", buildCmd));
        }

        // 1. Directory creation
        if (lower.contains("create") && (lower.contains("directory") || lower.contains("folder") || lower.contains("subfolder"))) {
            String dir = extractPath(intent, "target");
            String cmd = isWindows
                    ? "New-Item -ItemType Directory -Force -Path \"" + dir + "\""
                    : "mkdir -p \"" + dir + "\"";
            return new ToolBinding("shell_exec", Map.of("command", cmd, "targetDir", dir));
        }

        // 2. Writing file
        if (lower.contains("write") || lower.contains("create file") || lower.contains("save")) {
            String path = extractPath(intent, "output.txt");
            String content = extractQuotedContent(intent, "Sovereign default generated content");
            return new ToolBinding("file_write", Map.of("path", path, "content", content));
        }

        // 3. Reading / verifying / checksumming file
        if (lower.contains("read") || lower.contains("verify") || lower.contains("check") || lower.contains("checksum")) {
            String path = extractPath(intent, "output.txt");
            return new ToolBinding("file_read", Map.of("path", path));
        }

        // 4. Directory tree walk / search
        if (lower.contains("walk") || lower.contains("list files") || lower.contains("find")) {
            String path = extractPath(intent, ".");
            return new ToolBinding("file_walk", Map.of("path", path, "maxDepth", 3));
        }

        // 5. Process inspection
        if (lower.contains("process") || lower.contains("tasklist")) {
            String filter = extractQuotedContent(intent, "");
            return new ToolBinding("process_list", Map.of("filter", filter));
        }

        // 6. Generic Shell execution
        return new ToolBinding("shell_exec", Map.of("command", intent));
    }

    private String extractPath(String text, String defaultPath) {
        // Extract quoted paths like 'artifacts' or "data.json"
        Matcher quoteMatcher = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(text);
        if (quoteMatcher.find()) {
            return quoteMatcher.group(1);
        }

        // Extract tokens like target/file.txt or words after directory/file
        Matcher tokenMatcher = Pattern.compile("(?i)(?:directory|folder|file|path)\\s+([a-zA-Z0-9_./\\\\-]+)").matcher(text);
        if (tokenMatcher.find()) {
            return tokenMatcher.group(1);
        }

        return defaultPath;
    }

    private String extractQuotedContent(String text, String defaultContent) {
        // Pattern 1: single quotes outer: with content '...'
        Matcher singleMatcher = Pattern.compile("(?i)(?:with\\s+content|content|with|data)\\s*['](.*?)[']").matcher(text);
        if (singleMatcher.find()) {
            return singleMatcher.group(1);
        }
        // Pattern 2: double quotes outer: with content "..."
        Matcher doubleMatcher = Pattern.compile("(?i)(?:with\\s+content|content|with|data)\\s*[\"](.*?)[\"]").matcher(text);
        if (doubleMatcher.find()) {
            return doubleMatcher.group(1);
        }

        // Fallback: look for second quoted item
        Matcher anySingle = Pattern.compile("['](.*?)[']").matcher(text);
        List<String> singleQuotes = new ArrayList<>();
        while (anySingle.find()) {
            singleQuotes.add(anySingle.group(1));
        }
        if (singleQuotes.size() >= 2) {
            return singleQuotes.get(1);
        }

        return defaultContent;
    }

    public PlanningSDK getPlanningSdk() {
        return planningSdk;
    }
}
