package com.sovereign;

import com.sovereign.cli.SovereignReplRunner;
import com.sovereign.core.config.ProviderConfig;
import com.sovereign.core.web.SovereignWebDashboard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SovereignPhase8WebDashboardTest</b>
 *
 * <p>Unit and integration tests for Phase 8: Web Dashboard (embedded HTTP server,
 * single-page HTML/CSS/JS co-pilot UI, status API, and chat endpoints).</p>
 */
public class SovereignPhase8WebDashboardTest {

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 7705;
        }
    }

    @Test
    @DisplayName("Test 1: SovereignWebDashboard starts, serves HTML UI, and stops cleanly")
    void testDashboardLifecycleAndHtmlEndpoint() throws Exception {
        int port = findFreePort();
        ProviderConfig offlineConfig = ProviderConfig.of(null, null);
        SovereignReplRunner runner = new SovereignReplRunner(offlineConfig);
        SovereignWebDashboard dashboard = new SovereignWebDashboard(runner, port);

        assertThat(dashboard.isRunning()).isFalse();
        dashboard.start();
        assertThat(dashboard.isRunning()).isTrue();
        assertThat(dashboard.getUrl()).isEqualTo("http://localhost:" + port);

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dashboard.getUrl() + "/"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("SOVEREIGN");
            assertThat(response.body()).contains("ZERO-COST");
            assertThat(response.body()).contains("JARVIS");
        } finally {
            dashboard.stop();
            assertThat(dashboard.isRunning()).isFalse();
        }
    }

    @Test
    @DisplayName("Test 2: Status API returns JSON with free tier quota and zero cost metrics")
    void testStatusApi() throws Exception {
        int port = findFreePort();
        ProviderConfig offlineConfig = ProviderConfig.of(null, null);
        SovereignReplRunner runner = new SovereignReplRunner(offlineConfig);
        SovereignWebDashboard dashboard = new SovereignWebDashboard(runner, port);

        dashboard.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dashboard.getUrl() + "/api/status"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\": \"ONLINE\"");
            assertThat(response.body()).contains("\"totalCost\": \"$0.00\"");
            assertThat(response.body()).contains("remainingRequestsThisMinute");
        } finally {
            dashboard.stop();
        }
    }

    @Test
    @DisplayName("Test 3: Chat API accepts message and returns JSON response")
    void testChatApi() throws Exception {
        int port = findFreePort();
        ProviderConfig offlineConfig = ProviderConfig.of(null, null);
        SovereignReplRunner runner = new SovereignReplRunner(offlineConfig);
        SovereignWebDashboard dashboard = new SovereignWebDashboard(runner, port);

        dashboard.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dashboard.getUrl() + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\": \"Hello Sovereign\"}"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"reply\":");
        } finally {
            dashboard.stop();
        }
    }

    @Test
    @DisplayName("Test 4: SovereignReplRunner exposes WebDashboard instance")
    void testReplRunnerDashboardWiring() {
        ProviderConfig offlineConfig = ProviderConfig.of(null, null);
        SovereignReplRunner runner = new SovereignReplRunner(offlineConfig);

        assertThat(runner.getWebDashboard()).isNotNull();
        assertThat(runner.getWebDashboard().getPort()).isEqualTo(SovereignWebDashboard.DEFAULT_PORT);
    }
}
