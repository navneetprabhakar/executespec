package com.navneet.executespec.testgen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Probes a running preview's common API endpoints to determine which routes
 * are actually live. Results are injected into the test worker prompt so that
 * generated tests target only reachable endpoints and use correct status codes.
 */
@Component
@Slf4j
public class ApiProber {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_PROBES = 12;

    /** Common probe paths checked against every preview. */
    private static final List<String> COMMON_PATHS = List.of(
            "/api/health",
            "/health",
            "/actuator/health",
            "/api",
            "/api/v1",
            "/"
    );

    /**
     * Probe a list of known API endpoint paths against the preview base URL.
     * Returns a markdown summary of which endpoints responded and with what status.
     *
     * @param baseUrl     Preview base URL (e.g. http://preview-abc.run.app)
     * @param knownPaths  API paths extracted from the workspace (may be empty)
     * @return markdown bullet list describing probe results; empty string if probe fails
     */
    public String probe(String baseUrl, List<String> knownPaths) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            List<String> pathsToProbe = new ArrayList<>(COMMON_PATHS);
            if (knownPaths != null) {
                for (String p : knownPaths) {
                    if (!pathsToProbe.contains(p)) {
                        pathsToProbe.add(p);
                    }
                    if (pathsToProbe.size() >= MAX_PROBES) break;
                }
            }

            StringBuilder sb = new StringBuilder();
            int liveCount = 0;
            for (String path : pathsToProbe) {
                String url = baseUrl.replaceAll("/+$", "") + path;
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(TIMEOUT)
                            .GET()
                            .build();
                    HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                    int status = resp.statusCode();
                    String label = status < 400 ? "LIVE" : (status == 404 ? "NOT_FOUND" : "ERROR");
                    sb.append("- ").append(path).append(" → ").append(status).append(" (").append(label).append(")\n");
                    if (status < 400) liveCount++;
                } catch (Exception e) {
                    sb.append("- ").append(path).append(" → UNREACHABLE\n");
                }
            }

            if (sb.isEmpty()) return "";
            return "Live endpoint probe at " + baseUrl + " (" + liveCount + "/" + pathsToProbe.size() + " reachable):\n" + sb;
        } catch (Exception e) {
            log.debug("[ApiProber] Probe failed for {}: {}", baseUrl, e.getMessage());
            return "";
        }
    }
}
