package com.sovereign.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>GitHubTool</b>
 *
 * <p>Zero-cost GitHub REST API tool.
 * Enables repository searching, issue inspection, and remote code reading.
 * Operates without authentication for public open-source repos, or uses a free personal
 * access token (via {@code GITHUB_TOKEN}) when configured.</p>
 */
public class GitHubTool {

    private static final Logger LOG = Logger.getLogger(GitHubTool.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String GITHUB_API_BASE = "https://api.github.com";

    private final String token;

    public GitHubTool() {
        this(resolveToken());
    }

    public GitHubTool(String token) {
        this.token = (token != null && !token.isBlank()) ? token.trim() : null;
    }

    public record RepoInfo(String fullName, String description, int stars, String htmlUrl, String language) {}
    public record IssueInfo(int number, String title, String state, String author, String htmlUrl) {}

    public record GitHubResult(boolean success, String summary, String message) {}

    public GitHubResult searchRepos(String query, int limit) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String urlStr = GITHUB_API_BASE + "/search/repositories?q=" + encoded + "&per_page=" + Math.min(10, limit);
            JsonNode root = callGitHub(urlStr);
            if (root == null) {
                return new GitHubResult(false, "", "Failed to connect to GitHub API.");
            }

            JsonNode items = root.path("items");
            StringBuilder sb = new StringBuilder("=== GITHUB REPOSITORIES FOR: \"" + query + "\" ===\n");
            if (items.isArray() && items.size() > 0) {
                for (int i = 0; i < items.size(); i++) {
                    JsonNode r = items.get(i);
                    sb.append(i + 1).append(". ")
                            .append(r.path("full_name").asText())
                            .append(" [⭐ ").append(r.path("stargazers_count").asInt()).append("]")
                            .append(" (").append(r.path("language").asText("N/A")).append(")\n")
                            .append("   ").append(r.path("description").asText("No description"))
                            .append("\n   URL: ").append(r.path("html_url").asText()).append("\n\n");
                }
                return new GitHubResult(true, sb.toString().trim(), "Found " + items.size() + " repositories.");
            }
            return new GitHubResult(true, "No matching repositories found for: " + query, "Zero results.");

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[GITHUB] Search failed: " + e.getMessage());
            return new GitHubResult(false, "", "GitHub search error: " + e.getMessage());
        }
    }

    public GitHubResult getIssues(String owner, String repo, int limit) {
        try {
            String urlStr = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/issues?state=open&per_page=" + Math.min(10, limit);
            JsonNode root = callGitHub(urlStr);
            if (root == null || !root.isArray()) {
                return new GitHubResult(false, "", "Failed to fetch issues for " + owner + "/" + repo);
            }

            StringBuilder sb = new StringBuilder("=== OPEN ISSUES FOR: " + owner + "/" + repo + " ===\n");
            if (root.size() == 0) {
                sb.append("No open issues found.");
            } else {
                for (int i = 0; i < root.size(); i++) {
                    JsonNode issue = root.get(i);
                    sb.append("#").append(issue.path("number").asInt())
                            .append(" - ").append(issue.path("title").asText())
                            .append(" (@").append(issue.path("user").path("login").asText()).append(")\n")
                            .append("   URL: ").append(issue.path("html_url").asText()).append("\n");
                }
            }
            return new GitHubResult(true, sb.toString().trim(), "Retrieved " + root.size() + " issues.");

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[GITHUB] Issues query failed: " + e.getMessage());
            return new GitHubResult(false, "", "GitHub issues error: " + e.getMessage());
        }
    }

    private JsonNode callGitHub(String urlStr) {
        try {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "SovereignAssistant/1.0");
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);

            int status = conn.getResponseCode();
            if (status == 200) {
                try (InputStream is = conn.getInputStream()) {
                    return MAPPER.readTree(is);
                }
            } else {
                LOG.warning("[GITHUB] HTTP " + status + " from " + urlStr);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[GITHUB] Request error: " + e.getMessage());
        }
        return null;
    }

    private static String resolveToken() {
        String t = System.getenv("GITHUB_TOKEN");
        if (t == null || t.isBlank()) t = System.getProperty("GITHUB_TOKEN");
        if (t == null || t.isBlank()) t = System.getProperty("gh.token");
        return t;
    }
}
