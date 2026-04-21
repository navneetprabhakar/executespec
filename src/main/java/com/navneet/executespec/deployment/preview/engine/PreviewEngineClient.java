package com.navneet.executespec.deployment.preview.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.deployment.preview.PreviewProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin typed client for preview-engine's REST surface. Owns JSON
 * (de)serialisation + the X-Preview-Api-Key header.
 */
@Component
public class PreviewEngineClient {

    private static final Logger log = LoggerFactory.getLogger(PreviewEngineClient.class);

    private final PreviewProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public PreviewEngineClient(PreviewProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Outcome from the preview-engine side. Mirrors preview-engine's
     * {@code PreviewSummary} shape.
     */
    public record EngineResponse(String id, String status, String statusReason,
                                 String previewUrl, Integer hostPort) {}

    /**
     * POST /api/previews/from-image. Hands off a pre-built + pushed image
     * to preview-engine which pulls, runs, health-probes, and registers it.
     *
     * @throws PreviewEngineException on any non-2xx or transport error
     */
    public EngineResponse registerFromImage(String imageRef,
                                            String runId,
                                            String combo,
                                            String ownerUserId,
                                            String ownerTier,
                                            int internalPort,
                                            String healthPath,
                                            int ttlSeconds,
                                            Map<String, String> envVars) {
        Map<String, Object> body = new HashMap<>();
        body.put("imageRef", imageRef);
        body.put("runId", runId);
        body.put("combo", combo);
        body.put("ownerUserId", ownerUserId);
        body.put("ownerTier", ownerTier);
        body.put("internalPort", internalPort);
        body.put("healthPath", healthPath == null ? "/" : healthPath);
        body.put("ttlSeconds", ttlSeconds);
        body.put("envVars", envVars == null ? Map.of() : envVars);

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new PreviewEngineException("failed to serialise request: " + e.getMessage(), e);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getEngine().getBaseUrl() + "/api/previews/from-image"))
                .timeout(Duration.ofSeconds(props.getEngine().getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("X-Preview-Api-Key", props.getEngine().getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new PreviewEngineException("preview-engine unreachable: " + e.getMessage(), e);
        }

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new PreviewEngineException("preview-engine " + resp.statusCode() + ": " + resp.body(), null);
        }
        try {
            Map<String, Object> parsed = mapper.readValue(resp.body(), Map.class);
            return new EngineResponse(
                    asString(parsed.get("id")),
                    asString(parsed.get("status")),
                    asString(parsed.get("statusReason")),
                    asString(parsed.get("previewUrl")),
                    asInt(parsed.get("hostPort"))
            );
        } catch (Exception e) {
            throw new PreviewEngineException("failed to parse preview-engine response: " + e.getMessage(), e);
        }
    }

    /** DELETE /api/previews/{id}. Idempotent on the engine side. */
    public boolean unregister(String previewId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getEngine().getBaseUrl() + "/api/previews/" + previewId))
                .timeout(Duration.ofSeconds(30))
                .header("X-Preview-Api-Key", props.getEngine().getApiKey())
                .DELETE()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.warn("preview-engine DELETE {} failed: {}", previewId, e.getMessage());
            return false;
        }
    }

    public boolean isEnabled() {
        return props.getEngine().isRouteVia();
    }

    public String registryUrl() {
        return props.getEngine().getRegistryUrl();
    }

    public static class PreviewEngineException extends RuntimeException {
        public PreviewEngineException(String msg, Throwable cause) { super(msg, cause); }
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }
    private static Integer asInt(Object o)   {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}
