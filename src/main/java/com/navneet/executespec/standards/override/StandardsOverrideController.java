package com.navneet.executespec.standards.override;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.standards.OrgStandardRepository;
import com.navneet.executespec.standards.ProjectStandardOverride;
import com.navneet.executespec.standards.ProjectStandardOverrideRepository;
import com.navneet.executespec.standards.StandardsViolation;
import com.navneet.executespec.standards.StandardsViolationRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E3.11 — three override modes per plan §5.8:
 *
 * <ul>
 *   <li>{@link #emergencyOverride} — per-run override of a specific violation. Persistent on the
 *       {@link StandardsViolation} row. PROJECT_ADMIN-gated (controller-side TODO; advisory in v1).
 *   <li>{@link #addProjectException} — persistent NOT_APPLICABLE exception for the whole project.
 *       PROJECT_ADMIN-gated for project-level standards; ORG_ADMIN-gated when the standard is
 *       org-level (advisory in v1).
 *   <li>{@link #removeProjectException} — drops the exception.
 * </ul>
 *
 * <p>Spec exceptions (third mode in plan §5.8) are surfaced by the planner at the plan-approval
 * gate, not via REST — that flow lands with the gate UX.
 *
 * <p>Pattern detection (E3.11 deferred sub-task: "same standard overridden N times in M days
 * surfaces a permanent-exception recommendation") is exposed as {@link OverridePatternDetector} for
 * a future {@code @Scheduled} job to drive in-app notifications.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/standards")
@RequiredArgsConstructor
@Slf4j
public class StandardsOverrideController {

  private static final int MIN_RATIONALE_LENGTH = 30;

  private final ProjectStandardOverrideRepository overrideRepo;
  private final StandardsViolationRepository violationRepo;
  private final OrgStandardRepository orgStandardRepo;
  private final ProjectAccessGuard projectAccessGuard;
  private final org.springframework.beans.factory.ObjectProvider<ActivityEventEmitter>
      activityEmitterProvider;

  /** Emergency per-run override on a specific violation. */
  @PostMapping("/violations/{violationId}/override")
  public ResponseEntity<Void> emergencyOverride(
      @PathVariable String projectId,
      @PathVariable UUID violationId,
      @RequestBody OverrideRequest body,
      Authentication auth,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    requireAuth(auth);
    // Audit 2026-05-12 review item B #14 — was no access guard at all.
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    requireRationale(body);
    StandardsViolation v =
        violationRepo
            .findById(violationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "violation"));
    if (!projectId.equals(v.getProjectId())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "violation belongs to a different project");
    }
    v.setOverrideApplied(true);
    v.setOverrideByUserId(auth.getName());
    v.setOverrideRationale(body.rationale().trim());
    v.setOverrideAt(Instant.now());
    violationRepo.save(v);
    emit(
        parseUuidOrNull(projectId),
        ActivityEventType.STANDARDS_OVERRIDE_APPLIED,
        parseUuidOrNull(auth.getName()),
        "VIOLATION",
        violationId.toString(),
        "Emergency override on " + v.getStandardId(),
        Map.of("standardId", v.getStandardId(), "rationale", body.rationale().trim()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Add a persistent project-level exception (NOT_APPLICABLE or SEVERITY_DOWNGRADE) for an
   * org-level standard.
   */
  @PostMapping("/{standardId}/exception")
  public ResponseEntity<Void> addProjectException(
      @PathVariable String projectId,
      @PathVariable String standardId,
      @RequestBody ExceptionRequest body,
      Authentication auth,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    requireAuth(auth);
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    requireRationale(body);
    ProjectStandardOverride.OverrideType type = parseOverrideType(body.overrideType());
    if (type == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "overrideType must be NOT_APPLICABLE or SEVERITY_DOWNGRADE");
    }
    if (type == ProjectStandardOverride.OverrideType.SEVERITY_DOWNGRADE
        && (body.newSeverity() == null || body.newSeverity().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "SEVERITY_DOWNGRADE requires newSeverity");
    }
    Optional<ProjectStandardOverride> existing =
        overrideRepo.findByProjectIdAndStandardId(projectId, standardId);
    ProjectStandardOverride entity =
        existing.orElseGet(
            () ->
                ProjectStandardOverride.builder()
                    .projectId(projectId)
                    .standardId(standardId)
                    .build());
    entity.setOverrideType(type.name());
    entity.setNewSeverity(body.newSeverity());
    entity.setRationale(body.rationale().trim());
    entity.setCreatedByUserId(auth.getName());
    overrideRepo.save(entity);
    emit(
        parseUuidOrNull(projectId),
        ActivityEventType.STANDARDS_EXCEPTION_ADDED,
        parseUuidOrNull(auth.getName()),
        "STANDARD",
        standardId,
        "Added " + type + " exception on " + standardId,
        Map.of(
            "overrideType",
            type.name(),
            "newSeverity",
            body.newSeverity() == null ? "" : body.newSeverity(),
            "rationale",
            body.rationale().trim()));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{standardId}/exception")
  public ResponseEntity<Void> removeProjectException(
      @PathVariable String projectId,
      @PathVariable String standardId,
      Authentication auth,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    requireAuth(auth);
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    ProjectStandardOverride existing =
        overrideRepo
            .findByProjectIdAndStandardId(projectId, standardId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "exception"));
    overrideRepo.delete(existing);
    emit(
        parseUuidOrNull(projectId),
        ActivityEventType.STANDARDS_EXCEPTION_REMOVED,
        parseUuidOrNull(auth.getName()),
        "STANDARD",
        standardId,
        "Removed exception on " + standardId,
        Map.of("standardId", standardId));
    return ResponseEntity.noContent().build();
  }

  private void requireAuth(Authentication auth) {
    if (auth == null || auth.getName() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
  }

  private void requireRationale(RationaleHolder body) {
    if (body == null
        || body.rationale() == null
        || body.rationale().trim().length() < MIN_RATIONALE_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "rationale required (≥" + MIN_RATIONALE_LENGTH + " chars)");
    }
  }

  private void emit(
      UUID projectId,
      ActivityEventType type,
      UUID actor,
      String targetType,
      String targetId,
      String summary,
      Map<String, Object> payload) {
    ActivityEventEmitter emitter = activityEmitterProvider.getIfAvailable();
    if (emitter != null) {
      emitter.emit(projectId, type, actor, targetType, targetId, summary, payload);
    }
  }

  private static UUID parseUuidOrNull(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static ProjectStandardOverride.OverrideType parseOverrideType(String s) {
    if (s == null) return null;
    try {
      return ProjectStandardOverride.OverrideType.valueOf(s);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /** Common interface for rationale-bearing request bodies. */
  interface RationaleHolder {
    String rationale();
  }

  public record OverrideRequest(String rationale) implements RationaleHolder {}

  public record ExceptionRequest(String overrideType, String newSeverity, String rationale)
      implements RationaleHolder {}
}
