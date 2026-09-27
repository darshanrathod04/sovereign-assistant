package com.sovereign.core.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>WebPageReaderTool</b>
 *
 * <p>Zero-cost web scraper and page reader powered by Jsoup.
 * Fetches HTML from URLs, strips intrusive navigation, advertising, scripts, and styling,
 * and extracts clean text or markdown for LLM comprehension and documentation research.</p>
 */
public class WebPageReaderTool {

    private static final Logger LOG = Logger.getLogger(WebPageReaderTool.class.getName());

    public record WebPageContent(
            boolean success,
            String url,
            String title,
            String textContent,
            int length,
            String message
    ) {
        public String formatSummary(int maxChars) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== WEBPAGE: ").append(title != null ? title : url).append(" ===\n");
            sb.append("URL: ").append(url).append("\n\n");
            if (textContent != null) {
                String preview = textContent.length() > maxChars
                        ? textContent.substring(0, maxChars) + "\n...[truncated " + (textContent.length() - maxChars) + " chars]"
                        : textContent;
                sb.append(preview).append("\n");
            }
            return sb.toString().trim();
        }
    }

    /**
     * Fetches and parses a web page, returning readable article and documentation text.
     */
    public WebPageContent fetchPage(String url) {
        if (url == null || url.isBlank() || !url.startsWith("http")) {
            return new WebPageContent(false, url, "", "", 0, "Invalid URL format. URL must start with http:// or https://");
        }

        try {
            Document doc = Jsoup.connect(url.trim())
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) SovereignAssistant/1.0")
                    .timeout(15_000)
                    .followRedirects(true)
                    .get();

            // Strip non-content elements
            doc.select("script, style, noscript, svg, nav, footer, header, form, iframe, aside").remove();

            String title = doc.title();

            // Extract main content prioritizing article or main tags if present
            Element mainContent = doc.selectFirst("main, article, div[role=main], #content, .content, .post");
            Element target = mainContent != null ? mainContent : doc.body();

            String cleanText = (target != null) ? cleanTextContent(target) : "";

            return new WebPageContent(true, url, title, cleanText, cleanText.length(), "Page fetched and parsed successfully.");

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[WEB READER] Failed to fetch URL " + url + ": " + e.getMessage());
            return new WebPageContent(false, url, "", "", 0, "Failed to read webpage: " + e.getMessage());
        }
    }

    private static String cleanTextContent(Element element) {
        StringBuilder sb = new StringBuilder();
        for (Element child : element.select("h1, h2, h3, h4, p, pre, code, li, tr")) {
            String tag = child.tagName();
            String text = child.text().trim();
            if (text.isEmpty()) continue;

            if (tag.equals("h1")) sb.append("# ").append(text).append("\n\n");
            else if (tag.equals("h2")) sb.append("## ").append(text).append("\n\n");
            else if (tag.equals("h3")) sb.append("### ").append(text).append("\n\n");
            else if (tag.equals("li")) sb.append("- ").append(text).append("\n");
            else if (tag.equals("pre") || tag.equals("code")) sb.append("```\n").append(text).append("\n```\n\n");
            else sb.append(text).append("\n\n");
        }
        return sb.toString().trim();
    }
}
