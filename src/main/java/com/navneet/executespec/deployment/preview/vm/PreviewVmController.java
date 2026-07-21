package com.navneet.executespec.deployment.preview.vm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * VM-preview lifecycle and diagnostics.
 *
 * <p>RR-1036: this surface used to fall through to "any authenticated user". Every route now
 * resolves authority explicitly.
 *
 * <ul>
 *   <li>Customer actions (teardown, extend) resolve the preview's owning run and require run WRITE
 *       authority on it, so one tenant cannot stop or prolong another tenant's preview.
 *   <li>Fleet diagnostics (active list, stats, per-VM stats) are operator surfaces that expose
 *       internal VM ids, IPs, ports and raw SSH/command error text. They are SUPER_ADMIN-only and
 *       the active list is projected into a redacted DTO rather than returning the raw entity.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/previews")
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "preview.mode", havingValue = "vm")
public class PreviewVmController {

  private final PreviewDeploymentService previewService;
  private final PreviewRegistryRepository registryRepo;
  private final PreviewMetricRepository metricsRepo;
  private final RunAccessGuard accessGuard;

  /**
   * Customer-visible preview summary. Deliberately omits vmId, vmIp, port and errorMessage: those
   * describe platform topology and carry raw command output.
   */
  public record PreviewSummary(
      String previewId,
      String executionId,
      String url,
      String status,
      Boolean healthy,
      Instant createdAt,
      Instant expiresAt) {
    static PreviewSummary from(PreviewRegistry r) {
      return new PreviewSummary(
          r.getId(),
          r.getExecutionId(),
          r.getUrl(),
          r.getStatus(),
          r.getHealthy(),
          r.getCreatedAt(),
          r.getExpiresAt());
    }
  }

  @DeleteMapping("/{previewId}")
  public ResponseEntity<Void> teardown(
      @PathVariable String previewId, @AuthenticationPrincipal AppUserDetails userDetails) {
    requireOwningRunWriteAccess(previewId, userDetails);
    previewService.teardown(previewId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{previewId}/extend")
  public ResponseEntity<Void> extend(
      @PathVariable String previewId,
      @RequestParam(defaultValue = "15") int minutes,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    requireOwningRunWriteAccess(previewId, userDetails);
    previewService.extend(previewId, minutes);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/active")
  public ResponseEntity<List<PreviewSummary>> listActive(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.requireSuperAdmin(userDetails);
    return ResponseEntity.ok(
        registryRepo.findByStatusIn(List.of("active", "deploying", "unhealthy")).stream()
            .map(PreviewSummary::from)
            .toList());
  }

  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> stats(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.requireSuperAdmin(userDetails);
    long active = registryRepo.countByStatus("active");
    long deploying = registryRepo.countByStatus("deploying");
    long unhealthy = registryRepo.countByStatus("unhealthy");
    long totalDeployed = registryRepo.count();
    long totalTornDown = registryRepo.countByStatus("torn_down");
    long totalFailed = registryRepo.countByStatus("failed");
    long totalExpired = metricsRepo.countByEventType("gc_expire");

    Instant todayStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
    long deploysToday = metricsRepo.countByEventTypeAndCreatedAtAfter("deploy", todayStart);
    long failuresToday =
        metricsRepo.countByEventTypeAndCreatedAtAfter("deploy_failure", todayStart);

    Double avgDeployTime = registryRepo.avgDeployDurationLastN(50);

    List<PreviewMetric> recentErrors =
        metricsRepo.findByEventTypeInOrderByCreatedAtDesc(
            List.of("deploy_failure", "deploy_timeout", "health_fail"), PageRequest.of(0, 20));

    return ResponseEntity.ok(
        Map.of(
            "current",
                Map.of(
                    "active", active,
                    "deploying", deploying,
                    "unhealthy", unhealthy),
            "lifetime",
                Map.of(
                    "total_deployed", totalDeployed,
                    "total_torn_down", totalTornDown,
                    "total_failed", totalFailed,
                    "total_expired", totalExpired),
            "today",
                Map.of(
                    "deploys", deploysToday,
                    "failures", failuresToday),
            "performance", Map.of("avg_deploy_seconds", avgDeployTime != null ? avgDeployTime : 0),
            "recent_errors", recentErrors));
  }

  @GetMapping("/stats/vm")
  public ResponseEntity<List<Map<String, Object>>> vmStats(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.requireSuperAdmin(userDetails);
    List<Object[]> vmCounts = registryRepo.countActiveByVm();
    List<Map<String, Object>> result =
        vmCounts.stream()
            .map(
                row ->
                    Map.<String, Object>of(
                        "vm_id", row[0],
                        "active_count", row[1]))
            .collect(Collectors.toList());
    return ResponseEntity.ok(result);
  }

  /**
   * Resolves the preview's owning run and requires WRITE authority on it. A caller who cannot see
   * the run gets the same 404 as a caller naming a preview that does not exist, so the endpoint is
   * not an existence oracle over other tenants' preview ids.
   */
  private void requireOwningRunWriteAccess(String previewId, AppUserDetails userDetails) {
    accessGuard.requireAuthenticated(userDetails);
    PreviewRegistry registry =
        registryRepo
            .findById(previewId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found"));
    String runId = registry.getExecutionId();
    if (runId == null || runId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
    }
    accessGuard.requireRunWriteAccess(runId, userDetails);
  }

  @ExceptionHandler(PreviewDeploymentException.class)
  public ResponseEntity<Map<String, String>> handleError(PreviewDeploymentException e) {
    // RR-1036: raw SSH/command failure text is operator-only. Log it, return a stable category.
    log.error("Preview VM error: {}", e.getMessage());
    return ResponseEntity.status(500)
        .body(Map.of("error", "preview_error", "message", "Preview operation failed."));
  }
}
