package com.navneet.executespec.api.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.deployment.preview.*;
import com.navneet.executespec.state.StateStore;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class PreviewController {

  private final PreviewService previewService;
  private final RunAccessGuard guard;
  private final StateStore stateStore;
  private final TierConfigRepository tierConfigRepo;

  public record PreviewStatusDto(
      String runId,
      String status,
      String url,
      String projectType,
      String databaseType,
      String errorMessage,
      String backendUrl,
      String frontendUrl,
      String expiresAt,
      long ttlRemainingSeconds,
      int previewStartCount,
      int maxPreviewStarts,
      String buildOutput,
      String dockerfile) {}

  public record PreviewLogsDto(String runId, List<String> lines) {}

  public record PreviewAnalysisDto(String databaseType, List<DetectedEnvVarDto> requiredEnvVars) {}

  public record DetectedEnvVarDto(
      String name, String category, String source, String defaultValue) {}

  public record PreviewStartRequest(Map<String, String> envVars) {}

  public record ApiDocsProbeResult(boolean found, String path, String contentType) {}

  /**
   * Analyze workspace for database and environment variable requirements. Should be called before
   * starting preview to determine if user input is needed.
   */
  @GetMapping("/{runId}/preview/analyze")
  public ResponseEntity<PreviewAnalysisDto> analyze(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    log.info("GET /api/runs/{}/preview/analyze", runId);

    var analysis = previewService.analyze(runId);
    List<DetectedEnvVarDto> envVarDtos =
        analysis.requiredEnvVars().stream()
            .map(
                v ->
                    new DetectedEnvVarDto(
                        v.name(), v.category().name(), v.source(), v.defaultValue()))
            .toList();

    return ResponseEntity.ok(
        new PreviewAnalysisDto(
            analysis.databaseType() != null ? analysis.databaseType().name() : null, envVarDtos));
  }

  @GetMapping("/{runId}/preview/status")
  public ResponseEntity<PreviewStatusDto> getStatus(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    return ResponseEntity.ok(toDto(runId, previewService.getStatus(runId)));
  }

  @PostMapping("/{runId}/preview/start")
  public ResponseEntity<PreviewStatusDto> start(
      @PathVariable String runId,
      @RequestBody(required = false) PreviewStartRequest request,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/preview/start", runId);

    try {
      Map<String, String> envVars =
          (request != null && request.envVars() != null) ? request.envVars() : Map.of();
      PreviewSession session = previewService.start(runId, userDetails.getUser(), envVars);
      return ResponseEntity.ok(toDto(runId, session));
    } catch (PreviewException e) {
      log.warn("Preview start failed for run {}: {}", runId, e.getMessage());
      return ResponseEntity.ok(
          new PreviewStatusDto(
              runId,
              "FAILED",
              null,
              null,
              null,
              e.getMessage(),
              null,
              null,
              null,
              0,
              0,
              0,
              null,
              null));
    }
  }

  @PostMapping("/{runId}/preview/rebuild")
  public ResponseEntity<PreviewStatusDto> rebuild(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/preview/rebuild", runId);

    try {
      PreviewSession session = previewService.rebuild(runId, userDetails.getUser());
      return ResponseEntity.ok(toDto(runId, session));
    } catch (PreviewException e) {
      log.warn("Preview rebuild failed for run {}: {}", runId, e.getMessage());
      return ResponseEntity.ok(
          new PreviewStatusDto(
              runId,
              "FAILED",
              null,
              null,
              null,
              e.getMessage(),
              null,
              null,
              null,
              0,
              0,
              0,
              null,
              null));
    }
  }

  @DeleteMapping("/{runId}/preview/stop")
  public ResponseEntity<Void> stop(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("DELETE /api/runs/{}/preview/stop", runId);
    previewService.stop(runId);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/{runId}/preview/logs")
  public ResponseEntity<PreviewLogsDto> getLogs(
      @PathVariable String runId,
      @RequestParam(defaultValue = "0") int lines,
      @RequestParam(defaultValue = "all") String filter,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    String raw = previewService.getLogs(runId, lines, filter);
    List<String> logLines = raw.isBlank() ? List.of() : Arrays.asList(raw.split("\n"));
    return ResponseEntity.ok(new PreviewLogsDto(runId, logLines));
  }

  /**
   * Probe the running preview container for common API documentation endpoints. This server-side
   * proxy avoids CORS issues when the frontend checks for OpenAPI / Swagger UI pages on the preview
   * container.
   */
  @GetMapping("/{runId}/preview/probe-api-docs")
  public ResponseEntity<ApiDocsProbeResult> probeApiDocs(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);

    PreviewSession session = previewService.getStatus(runId);
    if (session == null || session.status() != PreviewStatus.RUNNING) {
      return ResponseEntity.ok(new ApiDocsProbeResult(false, null, null));
    }

    // Determine base URL: Cloud Run uses the service URL, Docker uses localhost:port
    String baseUrl;
    if (session.cloudRunServiceName() != null && session.url() != null) {
      baseUrl = session.url(); // Cloud Run URL (e.g., https://es-preview-xxx.run.app)
    } else if (session.hostPort() > 0) {
      baseUrl = "http://localhost:" + session.hostPort();
    } else {
      return ResponseEntity.ok(new ApiDocsProbeResult(false, null, null));
    }

    String[] probePaths = {
      "/v3/api-docs", // SpringDoc OpenAPI 3
      "/api-docs", // Swagger/SpringDoc default
      "/swagger.json", // Swagger 2
      "/openapi.json", // OpenAPI 3 common
      "/docs", // FastAPI default
      "/swagger-ui.html", // Spring Boot classic
      "/swagger-ui/index.html", // SpringDoc Swagger UI
    };

    try (var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build()) {

      for (String path : probePaths) {
        try {
          var request =
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl + path))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build();
          var response = client.send(request, HttpResponse.BodyHandlers.discarding());
          if (response.statusCode() >= 200 && response.statusCode() < 400) {
            String ct = response.headers().firstValue("content-type").orElse("");
            String type = ct.contains("json") ? "json" : ct.contains("html") ? "html" : "other";
            log.info("API docs found for run {} at path {} ({})", runId, path, type);
            return ResponseEntity.ok(new ApiDocsProbeResult(true, path, type));
          }
        } catch (Exception e) {
          // Probe failed for this path — try next
        }
      }
    }

    return ResponseEntity.ok(new ApiDocsProbeResult(false, null, null));
  }

  public record ExecRequest(String cmd, List<String> args) {}

  public record ExecResponse(String stdout, String stderr, int exitCode) {}

  /**
   * Execute an allowlisted command inside the running preview container. Only available in Docker
   * preview mode (not Cloud Run or VM).
   *
   * <p>The command is run non-interactively via {@code docker compose exec -T}. A server-side
   * allowlist prevents arbitrary code execution.
   */
  @PostMapping("/{runId}/preview/exec")
  public ResponseEntity<ExecResponse> execInContainer(
      @PathVariable String runId,
      @RequestBody ExecRequest request,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunWriteAccess(runId, userDetails);
    log.info("POST /api/runs/{}/preview/exec cmd={}", runId, request.cmd());

    try {
      List<String> args = request.args() != null ? request.args() : List.of();
      ExecResult result = previewService.execInContainer(runId, request.cmd(), args);
      return ResponseEntity.ok(
          new ExecResponse(result.stdout(), result.stderr(), result.exitCode()));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(new ExecResponse("", e.getMessage(), -1));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(503).body(new ExecResponse("", e.getMessage(), -1));
    }
  }

  private PreviewStatusDto toDto(String runId, PreviewSession session) {
    var run = stateStore.findRun(runId).orElse(null);
    int startCount = run != null ? run.getPreviewStartCount() : 0;
    String tier =
        run != null && run.getOrgId() != null ? "PRO" : "FREE"; // simplified tier detection
    // Look up actual user tier from the creating user if available
    int maxStarts = tierConfigRepo.findById(tier).map(TierConfig::getMaxPreviewStarts).orElse(3);

    if (session == null) {
      return new PreviewStatusDto(
          runId,
          "IDLE",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          0,
          startCount,
          maxStarts,
          null,
          null);
    }

    // Only include build diagnostics on failure (avoid bloating normal status polls)
    boolean isFailed =
        session.status() == PreviewStatus.FAILED || session.status() == PreviewStatus.TIMED_OUT;

    return new PreviewStatusDto(
        session.runId(),
        session.status().name(),
        session.url(),
        session.projectType() != null ? session.projectType().name() : null,
        session.databaseType() != null ? session.databaseType().name() : null,
        session.errorMessage(),
        null,
        null,
        session.expiresAt() != null ? session.expiresAt().toString() : null,
        session.ttlRemainingSeconds(),
        startCount,
        maxStarts,
        isFailed ? session.buildOutput() : null,
        isFailed ? session.dockerfile() : null);
  }
}
