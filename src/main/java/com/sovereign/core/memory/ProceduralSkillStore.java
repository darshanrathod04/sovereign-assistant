package com.sovereign.core.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>ProceduralSkillStore</b>
 *
 * <p>Stores parameterized execution workflows and learned procedural recipes
 * (e.g., maven_clean_build, git_sync_flow, custom learned skills).</p>
 */
public class ProceduralSkillStore {

    public record SkillDefinition(String name, String recipe, String description) {}

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    public ProceduralSkillStore() {
        // Pre-register standard procedural recipes
        registerSkill("maven_clean_build", "mvn clean compile", "Standard Maven clean and compilation workflow");
        registerSkill("git_sync_flow", "git pull && git status", "Git synchronization check flow");
        registerSkill("maven_test", "mvn test", "Run Maven test suite");
        registerSkill("gradle_test", "gradle test", "Run Gradle test suite");
        registerSkill("npm_test", "npm test", "Run NPM JavaScript test suite");
    }

    public static ProceduralSkillStore createDefault() {
        return new ProceduralSkillStore();
    }

    public void registerSkill(String name, String recipe) {
        registerSkill(name, recipe, "User-defined procedural skill");
    }

    public void registerSkill(String name, String recipe, String description) {
        if (name != null && !name.isBlank() && recipe != null && !recipe.isBlank()) {
            skills.put(name.trim().toLowerCase(), new SkillDefinition(name.trim(), recipe.trim(), description));
        }
    }

    public Optional<SkillDefinition> getSkill(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(name.trim().toLowerCase()));
    }

    public boolean hasSkill(String name) {
        if (name == null) {
            return false;
        }
        return skills.containsKey(name.trim().toLowerCase());
    }

    public void removeSkill(String name) {
        if (name != null) {
            skills.remove(name.trim().toLowerCase());
        }
    }

    public Map<String, SkillDefinition> getAllSkills() {
        return Collections.unmodifiableMap(skills);
    }
}
