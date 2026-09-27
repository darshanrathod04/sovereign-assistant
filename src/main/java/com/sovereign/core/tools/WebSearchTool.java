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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>WebSearchTool</b>
 *
 * <p>Zero-cost, keyless web search using the DuckDuckGo Instant Answer API.
 * Retrieves instant answers, encyclopedic summaries, definition text, and related topic links
 * with zero subscriptions, zero paid API keys, and no rate limits.</p>
 */
public class WebSearchTool {

    private static final Logger LOG = Logger.getLogger(WebSearchTool.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String DDG_API_TEMPLATE =
            "https://api.duckduckgo.com/?q=%s&format=json&no_html=1&skip_disambig=0";

    public record SearchResultItem(String title, String snippet, String url) {}

    public record SearchResponse(
            boolean success,
            String query,
            String answer,
            String sourceUrl,
            List<SearchResultItem> relatedResults,
            String message
    ) {
        public String formatSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== DUCKDUCKGO WEB SEARCH: \"").append(query).append("\" ===\n");
            if (answer != null && !answer.isBlank()) {
                sb.append("DIRECT ANSWER:\n").append(answer).append("\n\n");
            }
            if (sourceUrl != null && !sourceUrl.isBlank()) {
                sb.append("SOURCE: ").append(sourceUrl).append("\n\n");
            }
            if (relatedResults != null && !relatedResults.isEmpty()) {
                sb.append("RELATED TOPICS & LINKS:\n");
                for (int i = 0; i < Math.min(5, relatedResults.size()); i++) {
                    SearchResultItem item = relatedResults.get(i);
                    sb.append(i + 1).append(". ").append(item.snippet());
                    if (item.url() != null && !item.url().isBlank()) {
                        sb.append(" (").append(item.url()).append(")");
                    }
                    sb.append("\n");
                }
            }
            if ((answer == null || answer.isBlank()) && (relatedResults == null || relatedResults.isEmpty())) {
                sb.append("No instant abstract found. Try refining your search query.\n");
            }
            return sb.toString().trim();
        }
    }

    /**
     * Executes a zero-cost web search query against DuckDuckGo.
     */
    public SearchResponse search(String query) {
        if (query == null || query.isBlank()) {
            return new SearchResponse(false, "", null, null, List.of(), "Empty search query provided.");
        }

        try {
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            String urlStr = String.format(DDG_API_TEMPLATE, encoded);
            URL url = URI.create(urlStr).toURL();

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "SovereignAssistant/1.0 (+https://github.com/sovereign)");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);

            int status = conn.getResponseCode();
            if (status != 200) {
                return new SearchResponse(false, query, null, null, List.of(),
                        "DuckDuckGo search returned HTTP status " + status);
            }

            try (InputStream is = conn.getInputStream()) {
                JsonNode root = MAPPER.readTree(is);
                String abstractText = root.path("AbstractText").asText("");
                String sourceUrl = root.path("AbstractURL").asText("");

                List<SearchResultItem> related = new ArrayList<>();
                JsonNode relatedTopics = root.path("RelatedTopics");
                if (relatedTopics.isArray()) {
                    for (JsonNode topic : relatedTopics) {
                        if (topic.has("Text")) {
                            String text = topic.path("Text").asText();
                            String firstUrl = topic.path("FirstURL").asText("");
                            related.add(new SearchResultItem(topic.path("Result").asText(""), text, firstUrl));
                        } else if (topic.has("Topics") && topic.path("Topics").isArray()) {
                            // Sub-topics group
                            for (JsonNode sub : topic.path("Topics")) {
                                String text = sub.path("Text").asText();
                                String firstUrl = sub.path("FirstURL").asText("");
                                related.add(new SearchResultItem(sub.path("Result").asText(""), text, firstUrl));
                            }
                        }
                    }
                }

                String answer = !abstractText.isBlank() ? abstractText : root.path("Answer").asText("");

                return new SearchResponse(true, query, answer, sourceUrl, related, "Search completed successfully.");
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[WEB SEARCH] Search query failed: " + e.getMessage());
            return new SearchResponse(false, query, null, null, List.of(),
                    "Web search failed: " + e.getMessage());
        }
    }
}
