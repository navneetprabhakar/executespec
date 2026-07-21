package com.navneet.executespec.sharing.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.codereview.repository.CodeReviewScanRepository;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.entity.ProjectShareLinkAudit;
import com.navneet.executespec.sharing.repository.ProjectShareLinkRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * V175 — orchestrator for public read-only project share links.
 *
 * <p>A link is keyed by a high-entropy random token (32 bytes URL-safe). Anyone with the token can
 * read the targeted surface; revocation + expiry are admin-controlled. Tier-gated by active- link
 * count: FREE 3, PRO 25, TEAM/MAX/ENTERPRISE unlimited.
 *
 * <p>The actual surface rendering happens in dedicated public controllers — this service only
 * manages link lifecycle (create / revoke / lookup-by-token / view-counter).
 */
@Service
@RequiredArgsConstructor
public class ProjectShareLinkService {

  private static final SecureRandom RNG = new SecureRandom();
  public static final int DEFAULT_EXPIRY_DAYS = 7;
  public static final int MAX_EXPIRY_DAYS = 90;
  private static final int MAX_LABEL_CHARS = 120;

  private final ProjectShareLinkRepository linkRepo;
  private final TierConfigRepository tierConfigRepo;
  private final ProjectRepository projectRepo;
  private final PipelineRunRepository runRepo;
  private final CodeReviewScanRepository scanRepo;
  private final PrReviewRepository prReviewRepo;
  private final ProjectShareLinkAuditService auditService;

  /** Active link count must be below the per-tier cap. {@code -1} on the cap means unlimited. */
  void assertCanCreate(AppUser caller, String projectId) {
    if (caller == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    String tier = caller.getTier() == null ? "FREE" : caller.getTier();
    Optional<TierConfig> cfg = tierConfigRepo.findById(tier);
    int cap = cfg.map(TierConfig::getShareLinkActiveCap).orElse(0);
    if (cap < 0) return; // unlimited
    long active = linkRepo.countActiveByProject(projectId, Instant.now());
    if (active >= cap) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS,
          "Active share-link limit reached for tier "
              + tier
              + " ("
              + active
              + "/"
              + cap
              + "). Revoke an existing link or upgrade.");
    }
  }

  @Transactional
  public ProjectShareLink createLink(
      String projectId,
      ProjectShareLink.Surface surface,
      String scopeId,
      String label,
      Instant expiresAt,
      AppUser creator) {
    if (projectId == null || surface == null || creator == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "projectId, surface, creator are required");
    }
    if (surfaceRequiresScope(surface) && (scopeId == null || scopeId.isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "scopeId is required for surface " + surface);
    }
    Instant now = Instant.now();
    validateExpiry(expiresAt, now);
    String normalizedLabel = normalizeLabel(label);
    // Serialize count-and-create admission on the project row. Without this lock, concurrent
    // nodes can all observe cap-1 and each create a link, exceeding the tier's active-link cap.
    projectRepo
        .findByIdForQuotaUpdate(projectId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    assertCanCreate(creator, projectId);
    // IDOR defense (create side): the scope entity referenced by scopeId MUST belong to the same
    // project the caller is authorized to write. Without this a project admin could mint a public,
    // unauthenticated link whose scopeId points at another org's run / scan / PR review.
    assertScopeBelongsToProject(surface, scopeId, projectId);
    String plaintextToken = generateToken();
    ProjectShareLink link =
        ProjectShareLink.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .token(plaintextToken)
            .tokenHash(hashToken(plaintextToken))
            .tokenPrefix(plaintextToken.substring(0, 8))
            .surface(surface)
            .scopeId(scopeId)
            .label(normalizedLabel)
            .createdByUserId(creator.getId())
            .expiresAt(expiresAt)
            .createdAt(now)
            .build();
    // Flush the owning row before the audit insert; the audit FK is intentionally RESTRICT and
    // Hibernate is otherwise free to reorder unrelated entity inserts at transaction flush.
    ProjectShareLink saved = linkRepo.saveAndFlush(link);
    // F3 (2026-07-18): the entity carries a pre-assigned UUID id, so Spring Data treats it as
    // non-new and saveAndFlush performs a JPA merge that returns a copy WITHOUT the @Transient
    // one-time plaintext token. Re-attach it to the returned instance so the create response can
    // deliver the shareable bearer exactly once — otherwise every share link is unusable.
    saved.setToken(plaintextToken);
    auditService.record(
        saved,
        "CREATE",
        "CREATED",
        creator.getId(),
        ProjectShareLinkAuditService.AccessContext.none());
    return saved;
  }

  @Transactional
  public void revoke(String projectId, UUID linkId, AppUser actor) {
    ProjectShareLink link =
        linkRepo
            .findById(linkId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found"));
    if (!link.getProjectId().equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found");
    }
    if (link.getRevokedAt() != null) {
      return; // idempotent
    }
    link.setRevokedAt(Instant.now());
    if (actor != null) link.setRevokedByUserId(actor.getId());
    linkRepo.save(link);
    auditService.record(
        link,
        "REVOKE",
        "REVOKED",
        actor == null ? null : actor.getId(),
        ProjectShareLinkAuditService.AccessContext.none());
  }

  /**
   * Resolve a link by token + check it's still active. Returns 410 Gone for revoked or expired —
   * matches the public-share-report convention from {@code ValidationReportController}.
   */
  public ProjectShareLink resolveActive(String token) {
    return resolveActive(token, ProjectShareLinkAuditService.AccessContext.none());
  }

  public ProjectShareLink resolveActive(
      String token, ProjectShareLinkAuditService.AccessContext accessContext) {
    if (!isTokenShapeValid(token)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found");
    }
    ProjectShareLink link =
        linkRepo
            .findByTokenHash(hashToken(token))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found"));
    if (!link.isActive()) {
      auditService.record(
          link, "DENIED", link.getRevokedAt() != null ? "REVOKED" : "EXPIRED", null, accessContext);
      throw new ResponseStatusException(HttpStatus.GONE, "Share link has been revoked or expired");
    }
    return link;
  }

  /**
   * Append an immutable access fact and atomically bump the aggregate dashboard counter. Audit
   * persistence is mandatory; a failed write does not silently expose the public payload.
   */
  @Transactional
  public void recordView(ProjectShareLink link) {
    recordView(link, ProjectShareLinkAuditService.AccessContext.none());
  }

  @Transactional
  public void recordView(
      ProjectShareLink link, ProjectShareLinkAuditService.AccessContext accessContext) {
    auditService.record(link, "ACCESS", "ALLOWED", null, accessContext);
    int updated = linkRepo.recordView(link.getId(), Instant.now());
    if (updated != 1) {
      throw new IllegalStateException("Share-link view audit could not update the owning link");
    }
  }

  @Transactional
  public void recordDeniedSurface(
      ProjectShareLink link, ProjectShareLinkAuditService.AccessContext accessContext) {
    auditService.record(link, "DENIED", "WRONG_SURFACE", null, accessContext);
  }

  @Transactional
  public void recordMetadataAccess(
      ProjectShareLink link, ProjectShareLinkAuditService.AccessContext accessContext) {
    auditService.record(link, "ACCESS", "METADATA_ALLOWED", null, accessContext);
  }

  public List<ProjectShareLink> listForProject(String projectId) {
    return linkRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
  }

  public long activeCountForProject(String projectId) {
    return linkRepo.countActiveByProject(projectId, Instant.now());
  }

  public List<ProjectShareLinkAudit> auditForLink(String projectId, UUID linkId) {
    ProjectShareLink link =
        linkRepo
            .findById(linkId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found"));
    if (!projectId.equals(link.getProjectId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found");
    }
    return auditService.latest(linkId);
  }

  static boolean surfaceRequiresScope(ProjectShareLink.Surface surface) {
    return switch (surface) {
      case RUN_DETAIL, CODE_REVIEW, PR_REVIEW, VALIDATION_REPORT -> true;
      case PROJECT_OVERVIEW, ARCHITECTURE_DOC -> false;
    };
  }

  /**
   * Cross-org IDOR guard for link creation: resolve the scope entity referenced by {@code scopeId}
   * and assert its {@code projectId} matches the project the caller is authorized for. Surfaces
   * without a scope ({@code PROJECT_OVERVIEW}, {@code ARCHITECTURE_DOC}) are already project-scoped
   * and need no check. Throws {@code 403 FORBIDDEN} when the scope belongs to a different project
   * (or {@code 404 NOT_FOUND} when the scope id doesn't resolve at all).
   *
   * <p>Null-safe: a null {@code projectId} on either side never passes the match.
   */
  void assertScopeBelongsToProject(
      ProjectShareLink.Surface surface, String scopeId, String projectId) {
    switch (surface) {
      case PROJECT_OVERVIEW, ARCHITECTURE_DOC -> {
        // Project-scoped surfaces — no sub-id to validate.
      }
      case RUN_DETAIL, VALIDATION_REPORT -> {
        // scopeId = runId for both surfaces; the validation report has no projectId of its own and
        // is reached via its run, so the run's projectId is the authority for both.
        PipelineRun run =
            runRepo
                .findById(scopeId)
                .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        requireSameProject(run.getProjectId(), projectId);
      }
      case CODE_REVIEW -> {
        CodeReviewScan scan =
            scanRepo
                .findById(parseUuid(scopeId))
                .orElseThrow(
                    () ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Code review not found"));
        requireSameProject(scan.getProjectId(), projectId);
      }
      case PR_REVIEW -> {
        PrReview review =
            prReviewRepo
                .findById(parseUuid(scopeId))
                .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PR review not found"));
        requireSameProject(review.getProjectId(), projectId);
      }
    }
  }

  /** Null-safe string compare; mismatched (or null) project ids are rejected as 403. */
  private static void requireSameProject(String entityProjectId, String linkProjectId) {
    if (entityProjectId == null
        || linkProjectId == null
        || !entityProjectId.equals(linkProjectId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "scope does not belong to project");
    }
  }

  private static UUID parseUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid scope id");
    }
  }

  /** 32-byte URL-safe random token (~43 chars after base64). */
  private static String generateToken() {
    byte[] buf = new byte[32];
    RNG.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  static String hashToken(String token) {
    return DigestUtils.sha256Hex(token);
  }

  private static boolean isTokenShapeValid(String token) {
    return token != null && token.length() == 43 && token.matches("[A-Za-z0-9_-]{43}");
  }

  private static void validateExpiry(Instant expiresAt, Instant now) {
    if (expiresAt == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "share links require an expiry");
    }
    if (!expiresAt.isAfter(now)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "share-link expiry must be future");
    }
    if (expiresAt.isAfter(now.plusSeconds(MAX_EXPIRY_DAYS * 24L * 3600L + 60))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "share-link expiry cannot exceed " + MAX_EXPIRY_DAYS + " days");
    }
  }

  private static String normalizeLabel(String label) {
    if (label == null || label.isBlank()) return null;
    String normalized = label.trim();
    if (normalized.length() > MAX_LABEL_CHARS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "share-link label cannot exceed " + MAX_LABEL_CHARS + " chars");
    }
    return normalized;
  }
}
