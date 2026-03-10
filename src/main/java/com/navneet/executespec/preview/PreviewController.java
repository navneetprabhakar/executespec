package com.navneet.executespec.preview;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.StateStore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves the frontend preview for a completed run as static files.
 *
 * <p>Endpoint: {@code GET /api/runs/{runId}/preview/**}
 *
 * <p>Security:
 * <ul>
 *   <li>Path traversal prevented via {@code normalize() + startsWith()} (same pattern as WorkspaceFileService).</li>
 *   <li>Only serves runs where {@code hasFrontend=true}.</li>
 * </ul>
 *
 * <p>SPA fallback: if the requested path does not exist, falls back to {@code index.html}
 * so client-side routers (React Router, Vue Router, etc.) work correctly.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class PreviewController {

    private final AgentProperties agentProps;
    private final StateStore stateStore;

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
        Map.entry(".html",  "text/html; charset=utf-8"),
        Map.entry(".css",   "text/css"),
        Map.entry(".js",    "application/javascript"),
        Map.entry(".mjs",   "application/javascript"),
        Map.entry(".json",  "application/json"),
        Map.entry(".png",   "image/png"),
        Map.entry(".jpg",   "image/jpeg"),
        Map.entry(".jpeg",  "image/jpeg"),
        Map.entry(".gif",   "image/gif"),
        Map.entry(".svg",   "image/svg+xml"),
        Map.entry(".ico",   "image/x-icon"),
        Map.entry(".woff",  "font/woff"),
        Map.entry(".woff2", "font/woff2"),
        Map.entry(".ttf",   "font/ttf"),
        Map.entry(".map",   "application/json")
    );

    @GetMapping("/api/runs/{runId}/preview/**")
    public ResponseEntity<byte[]> serve(
            @PathVariable String runId,
            HttpServletRequest request) throws IOException {

        var run = stateStore.findRun(runId).orElse(null);
        if (run == null || !run.isHasFrontend()) {
            return ResponseEntity.notFound().build();
        }

        // Resolve serve root
        String serveDir  = run.getPreviewServeDir() != null ? run.getPreviewServeDir() : "";
        Path   wsRoot    = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
        Path   serveRoot = serveDir.isBlank() ? wsRoot : wsRoot.resolve(serveDir).normalize();

        // Extract path segment after /api/runs/{runId}/preview
        String prefix   = "/api/runs/" + runId + "/preview";
        String pathInfo = request.getRequestURI().substring(prefix.length());
        if (pathInfo.isBlank() || pathInfo.equals("/")) pathInfo = "/index.html";
        if (pathInfo.startsWith("/")) pathInfo = pathInfo.substring(1);

        // Path traversal guard
        Path target = serveRoot.resolve(pathInfo).normalize();
        if (!target.startsWith(serveRoot)) {
            log.warn("PreviewController: path traversal attempt for run {} path '{}'", runId, pathInfo);
            return ResponseEntity.status(403).build();
        }

        // SPA fallback: serve index.html for missing paths (client-side routing)
        if (!Files.exists(target) || Files.isDirectory(target)) {
            target = serveRoot.resolve("index.html").normalize();
            if (!Files.exists(target)) {
                return ResponseEntity.notFound().build();
            }
        }

        byte[] bytes = Files.readAllBytes(target);
        String mime  = detectMime(target.getFileName().toString());

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mime))
            .body(bytes);
    }

    private String detectMime(String filename) {
        for (var entry : MIME_TYPES.entrySet()) {
            if (filename.endsWith(entry.getKey())) return entry.getValue();
        }
        return "application/octet-stream";
    }
}
