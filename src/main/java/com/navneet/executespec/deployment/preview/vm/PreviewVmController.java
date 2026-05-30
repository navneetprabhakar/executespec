package com.navneet.executespec.deployment.preview.vm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/previews")
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "preview.mode", havingValue = "vm")
public class PreviewVmController {

  private final PreviewDeploymentService previewService;
  private final PreviewRegistryRepository registryRepo;
  private final PreviewMetricRepository metricsRepo;

  @DeleteMapping("/{previewId}")
  public ResponseEntity<Void> teardown(@PathVariable String previewId) {
    previewService.teardown(previewId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{previewId}/extend")
  public ResponseEntity<Void> extend(
      @PathVariable String previewId, @RequestParam(defaultValue = "15") int minutes) {
    previewService.extend(previewId, minutes);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/active")
  public ResponseEntity<List<PreviewRegistry>> listActive() {
    return ResponseEntity.ok(
        registryRepo.findByStatusIn(List.of("active", "deploying", "unhealthy")));
  }

  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> stats() {
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
  public ResponseEntity<List<Map<String, Object>>> vmStats() {
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

  @ExceptionHandler(PreviewDeploymentException.class)
  public ResponseEntity<Map<String, String>> handleError(PreviewDeploymentException e) {
    log.error("Preview VM error: {}", e.getMessage());
    return ResponseEntity.status(500)
        .body(Map.of("error", "preview_error", "message", e.getMessage()));
  }
}
