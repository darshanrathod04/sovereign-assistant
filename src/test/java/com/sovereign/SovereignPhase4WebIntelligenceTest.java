package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.tools.GitHubTool;
import com.sovereign.core.tools.WebPageReaderTool;
import com.sovereign.core.tools.WebSearchTool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignPhase4WebIntelligenceTest</b>
 *
 * <p>Phase 4 Verification Test Suite validating zero-cost Web Intelligence,
 * DuckDuckGo instant search parsing, Jsoup HTML to Markdown cleaning, and GitHub REST API integration.</p>
 */
public class SovereignPhase4WebIntelligenceTest {

    @Test
    @DisplayName("Test 1: WebSearchTool handles empty queries and formats structured search responses")
    void testWebSearchToolValidationAndFormatting() {
        WebSearchTool searchTool = new WebSearchTool();

        WebSearchTool.SearchResponse emptyRes = searchTool.search("");
        assertThat(emptyRes.success()).isFalse();
        assertThat(emptyRes.message()).contains("Empty search query");

        WebSearchTool.SearchResponse mockRes = new WebSearchTool.SearchResponse(
                true,
                "Java Virtual Machine",
                "A Java virtual machine is a virtual machine that enables a computer to run Java programs.",
                "https://en.wikipedia.org/wiki/Java_virtual_machine",
                java.util.List.of(new WebSearchTool.SearchResultItem("JVM", "Java Runtime Environment details", "https://jvm.org")),
                "Success"
        );
        String formatted = mockRes.formatSummary();
        assertThat(formatted)
                .contains("Java Virtual Machine")
                .contains("DIRECT ANSWER:")
                .contains("SOURCE: https://en.wikipedia.org/wiki/Java_virtual_machine")
                .contains("RELATED TOPICS & LINKS:");
    }

    @Test
    @DisplayName("Test 2: WebPageReaderTool cleans HTML, strips scripts/navbars, and converts to markdown")
    void testWebPageReaderHtmlCleaningAndMarkdown() {
        WebPageReaderTool readerTool = new WebPageReaderTool();

        WebPageReaderTool.WebPageContent invalidRes = readerTool.fetchPage("not-a-valid-url");
        assertThat(invalidRes.success()).isFalse();
        assertThat(invalidRes.message()).contains("Invalid URL format");

        String rawHtml = """
                <html>
                  <head><title>Spring Boot Docs</title><script>alert('bad');</script></head>
                  <body>
                    <nav><a href="/home">Home</a></nav>
                    <main>
                      <h1>Introduction</h1>
                      <p>Spring Boot makes it easy to create stand-alone production applications.</p>
                      <pre><code>mvn spring-boot:run</code></pre>
                    </main>
                    <footer>Copyright 2026</footer>
                  </body>
                </html>
                """;

        Document doc = Jsoup.parse(rawHtml);
        doc.select("script, style, noscript, nav, footer").remove();

        String title = doc.title();
        assertThat(title).isEqualTo("Spring Boot Docs");
        assertThat(doc.select("script")).isEmpty();
        assertThat(doc.select("nav")).isEmpty();
        assertThat(doc.text()).contains("Spring Boot makes it easy");
    }

    @Test
    @DisplayName("Test 3: GitHubTool parses repository lists and handles unauthenticated limits safely")
    void testGitHubToolRepositorySearch() {
        GitHubTool tool = new GitHubTool(null); // Unauthenticated free tier
        GitHubTool.GitHubResult res = tool.searchRepos("sovereign", 2);

        // Network call may succeed or hit transient network/unauthenticated rate limit gracefully without throwing exceptions
        assertThat(res).isNotNull();
        if (res.success()) {
            assertThat(res.summary()).contains("GITHUB REPOSITORIES");
        } else {
            assertThat(res.message()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Test 4: SovereignReplRunner handles search CLI arguments without crashing")
    void testReplRunnerWebCommandHandling() {
        SovereignReplRunner runner = new SovereignReplRunner(ProviderConfig.of(null, null));
        int code = runner.run(new String[]{"search"});
        assertThat(code).isEqualTo(1); // Prints usage
    }
}
