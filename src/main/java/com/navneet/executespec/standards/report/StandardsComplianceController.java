package com.navneet.executespec.standards.report;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.standards.EffectiveStandardsResolver;
import com.navneet.executespec.standards.OrgStandardRepository;
import com.navneet.executespec.standards.ProjectStandardOverride;
import com.navneet.executespec.standards.ProjectStandardOverrideRepository;
import com.navneet.executespec.standards.StandardsViolationRepository;
import com.navneet.executespec.standards.model.Standard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E3.16 — read-only project standards compliance report.
 *
 * <p>{@code GET /api/projects/{projectId}/standards/report?orgId=...} returns the effective
 * standards for the project (org + project-level merged) + recent violations + recent overrides.
 * Drives the future Standards sub-tab inside the dashboard's Knowledge group (no IA mutation needed
 * — same shape as TechDebtPanel).
 *
 * <p>Project-level standards.yaml content is loaded by the caller and passed in; the endpoint
 * doesn't fetch from {@code .executespec/} here (keeps this slice DB-only — folder-fetch wiring
 * lands in the subsequent slice that integrates with the standards-discovery pipeline).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/standards")
@RequiredArgsConstructor
@Slf4j
public class StandardsComplianceController {

  private final EffectiveStandardsResolver resolver;
  private final OrgStandardRepository orgStandardRepo;
  private final ProjectStandardOverrideRepository overrideRepo;
  private final StandardsViolationRepository violationRepo;

  @GetMapping("/report")
  public ResponseEntity<ComplianceReport> report(
      @PathVariable String projectId,
      @RequestParam String orgId,
      @RequestParam(required = false, defaultValue = "30") int sinceDays,
      Authentication auth) {
    if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth");

    // Effective standards (project-level YAML not loaded here in v1)
    List<EffectiveStandardsResolver.Effective> effective = resolver.resolve(orgId, projectId, null);
    List<StandardEntry> entries = new ArrayList<>();
    for (EffectiveStandardsResolver.Effective e : effective) {
      Standard s = e.standard();
      entries.add(
          new StandardEntry(
              s.id(),
              s.severity().name(),
              e.source().name(),
              e.overridden(),
              e.originalSeverity().name(),
              e.orgLevelLocked(),
              s.enforcement().type(),
              s.description()));
    }

    // Recent violations
    Instant since = Instant.now().minus(sinceDays, ChronoUnit.DAYS);
    List<ViolationEntry> recent =
        violationRepo.findRecentForProject(projectId, since).stream()
            .map(
                v ->
                    new ViolationEntry(
                        v.getId().toString(),
                        v.getStandardId(),
                        v.getEnforcementType(),
                        v.getSeverity(),
                        v.getViolationSummary(),
                        v.getDetectedAt().toString(),
                        v.isOverrideApplied()))
            .toList();

    // Recent overrides (persistent project exceptions)
    List<OverrideEntry> overrides =
        overrideRepo.findByProjectId(projectId).stream().map(this::toOverrideEntry).toList();

    return ResponseEntity.ok(
        new ComplianceReport(projectId, orgId, sinceDays, entries, recent, overrides));
  }

  private OverrideEntry toOverrideEntry(ProjectStandardOverride o) {
    return new OverrideEntry(
        o.getId().toString(),
        o.getStandardId(),
        o.getOverrideType(),
        o.getNewSeverity(),
        o.getRationale(),
        o.getCreatedByUserId(),
        o.getCreatedAt() == null ? null : o.getCreatedAt().toString());
  }

  public record ComplianceReport(
      String projectId,
      String orgId,
      int sinceDays,
      List<StandardEntry> standards,
      List<ViolationEntry> recentViolations,
      List<OverrideEntry> persistentOverrides) {}

  public record StandardEntry(
      String id,
      String severity,
      String source,
      boolean overridden,
      String originalSeverity,
      boolean orgLevelLocked,
      String enforcementType,
      String description) {}

  public record ViolationEntry(
      String id,
      String standardId,
      String enforcementType,
      String severity,
      String summary,
      String detectedAt,
      boolean overrideApplied) {}

  public record OverrideEntry(
      String id,
      String standardId,
      String overrideType,
      String newSeverity,
      String rationale,
      String createdByUserId,
      String createdAt) {}
}
