package com.navneet.executespec.standards.override;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.standards.StandardsViolation;
import com.navneet.executespec.standards.StandardsViolationRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E3.11 — pattern detector for "same standard overridden N times in M days" per plan §5.8. Surfaces
 * a recommendation to PROJECT_ADMIN to convert the recurring override into a permanent exception.
 *
 * <p>This service is designed to be called by a future {@code @Scheduled} job (daily). Pure read —
 * no side effects. The job that wraps it fires the in-app notification (deferred — current scope is
 * only the detection logic).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OverridePatternDetector {

  private final StandardsViolationRepository violationRepo;

  @Value("${standards.override.pattern.threshold:3}")
  private int threshold;

  @Value("${standards.override.pattern.window-days:14}")
  private int windowDays;

  /**
   * Detect override patterns for a project. Returns one {@link Recommendation} per (project,
   * standard) pair where the override count exceeds the configured threshold inside the configured
   * rolling window.
   *
   * @param projectId scope to a single project
   * @param standardIds candidate standards to check (caller passes every standard in scope; service
   *     filters)
   */
  public List<Recommendation> detect(String projectId, List<String> standardIds) {
    Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
    Map<String, Integer> counts = new HashMap<>();
    for (String sid : standardIds) {
      List<StandardsViolation> overrides =
          violationRepo.findRecentOverridesForStandard(projectId, sid, since);
      if (overrides.size() >= threshold) {
        counts.put(sid, overrides.size());
      }
    }
    List<Recommendation> out = new java.util.ArrayList<>();
    counts.forEach(
        (sid, n) -> out.add(new Recommendation(projectId, sid, n, windowDays, threshold)));
    return out;
  }

  /**
   * @param projectId project the recommendation is for
   * @param standardId the recurring standard
   * @param overrideCount actual count in the window
   * @param windowDays window the count was measured in
   * @param threshold threshold that was crossed
   */
  public record Recommendation(
      String projectId, String standardId, int overrideCount, int windowDays, int threshold) {}
}
