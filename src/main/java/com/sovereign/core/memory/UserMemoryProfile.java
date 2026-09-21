package com.sovereign.core.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>UserMemoryProfile</b>
 *
 * <p>Stores and persists long-term user preferences, favorite projects, user identity,
 * and custom aliases across Sovereign Assistant sessions.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserMemoryProfile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String userName;
    private String preferredShell;
    private String preferredEditor;
    private final List<String> favoriteProjects = new CopyOnWriteArrayList<>();
    private final Map<String, String> customAliases = new ConcurrentHashMap<>();
    private final Map<String, String> properties = new ConcurrentHashMap<>();

    public static final Path DEFAULT_PROFILE_PATH = Path.of(System.getProperty("user.home"), ".sovereign", "user-profile.json");

    public UserMemoryProfile() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        this.preferredShell = isWindows ? "powershell" : "bash";
        this.preferredEditor = "code";
    }

    public static UserMemoryProfile createDefault() {
        try {
            if (Files.exists(DEFAULT_PROFILE_PATH)) {
                return loadFromFile(DEFAULT_PROFILE_PATH);
            }
        } catch (Exception ignored) {
        }
        return new UserMemoryProfile();
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
        if (userName != null) {
            properties.put("userName", userName);
        } else {
            properties.remove("userName");
        }
    }

    public String getPreferredShell() {
        return preferredShell;
    }

    public void setPreferredShell(String preferredShell) {
        this.preferredShell = preferredShell;
        if (preferredShell != null) {
            properties.put("preferredShell", preferredShell);
        }
    }

    public String getPreferredEditor() {
        return preferredEditor;
    }

    public void setPreferredEditor(String preferredEditor) {
        this.preferredEditor = preferredEditor;
        if (preferredEditor != null) {
            properties.put("userEditor", preferredEditor);
        }
    }

    public String getUserEditor() {
        return preferredEditor;
    }

    public void setUserEditor(String userEditor) {
        setPreferredEditor(userEditor);
    }

    public void setProperty(String key, String value) {
        if (key != null && value != null) {
            properties.put(key, value);
            if ("userName".equalsIgnoreCase(key)) {
                this.userName = value;
            } else if ("userEditor".equalsIgnoreCase(key) || "preferredEditor".equalsIgnoreCase(key) || "editor".equalsIgnoreCase(key)) {
                this.preferredEditor = value;
            } else if ("preferredShell".equalsIgnoreCase(key) || "shell".equalsIgnoreCase(key)) {
                this.preferredShell = value;
            }
        }
    }

    public String getProperty(String key) {
        if (key == null) return null;
        return properties.get(key);
    }

    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public List<String> getFavoriteProjects() {
        return Collections.unmodifiableList(favoriteProjects);
    }

    public void addFavoriteProject(String projectPath) {
        if (projectPath != null && !projectPath.isBlank() && !favoriteProjects.contains(projectPath)) {
            favoriteProjects.add(projectPath);
        }
    }

    public void removeFavoriteProject(String projectPath) {
        favoriteProjects.remove(projectPath);
    }

    public Map<String, String> getCustomAliases() {
        return Collections.unmodifiableMap(customAliases);
    }

    public void setAlias(String alias, String commandOrGoal) {
        if (alias != null && !alias.isBlank() && commandOrGoal != null && !commandOrGoal.isBlank()) {
            customAliases.put(alias.trim().toLowerCase(), commandOrGoal.trim());
        }
    }

    public Optional<String> getAlias(String alias) {
        if (alias == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(customAliases.get(alias.trim().toLowerCase()));
    }

    public void removeAlias(String alias) {
        if (alias != null) {
            customAliases.remove(alias.trim().toLowerCase());
        }
    }

    public void saveToFile(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), this);
    }

    public static UserMemoryProfile loadFromFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new UserMemoryProfile();
        }
        return MAPPER.readValue(path.toFile(), UserMemoryProfile.class);
    }
}
