package com.navneet.executespec.sharing.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.repository.ProjectShareLinkRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class ProjectShareLinkService {

  private static final SecureRandom RNG = new SecureRandom();

  private final ProjectShareLinkRepository linkRepo;
  private final TierConfigRepository tierConfigRepo;

  // ── Tier gating ──────────────────────────────────────────────────────────

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

  // ── Public entry ─────────────────────────────────────────────────────────

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
    assertCanCreate(creator, projectId);
    ProjectShareLink link =
        ProjectShareLink.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .token(generateToken())
            .surface(surface)
            .scopeId(scopeId)
            .label(label)
            .createdByUserId(creator.getId())
            .expiresAt(expiresAt)
            .createdAt(Instant.now())
            .build();
    return linkRepo.save(link);
  }

  @Transactional
  public void revoke(UUID linkId, AppUser actor) {
    ProjectShareLink link =
        linkRepo
            .findById(linkId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found"));
    if (link.getRevokedAt() != null) {
      return; // idempotent
    }
    link.setRevokedAt(Instant.now());
    if (actor != null) link.setRevokedByUserId(actor.getId());
    linkRepo.save(link);
  }

  /**
   * Resolve a link by token + check it's still active. Returns 410 Gone for revoked or expired —
   * matches the public-share-report convention from {@code ValidationReportController}.
   */
  public ProjectShareLink resolveActive(String token) {
    ProjectShareLink link =
        linkRepo
            .findByToken(token)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found"));
    if (!link.isActive()) {
      throw new ResponseStatusException(HttpStatus.GONE, "Share link has been revoked or expired");
    }
    return link;
  }

  /**
   * Bump the {@code views} counter + {@code last_viewed_at} on a successful read. Best-effort — the
   * counter is for the dashboard, not for billing, so a missed update is fine.
   */
  @Transactional
  public void recordView(ProjectShareLink link) {
    try {
      link.setViews((link.getViews() == null ? 0 : link.getViews()) + 1);
      link.setLastViewedAt(Instant.now());
      linkRepo.save(link);
    } catch (Exception e) {
      log.warn("share link view counter bump failed for {}: {}", link.getId(), e.getMessage());
    }
  }

  // ── Read paths ──────────────────────────────────────────────────────────

  public List<ProjectShareLink> listForProject(String projectId) {
    return linkRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
  }

  public long activeCountForProject(String projectId) {
    return linkRepo.countActiveByProject(projectId, Instant.now());
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  static boolean surfaceRequiresScope(ProjectShareLink.Surface surface) {
    return switch (surface) {
      case RUN_DETAIL, CODE_REVIEW, PR_REVIEW, VALIDATION_REPORT -> true;
      case PROJECT_OVERVIEW, ARCHITECTURE_DOC -> false;
    };
  }

  /** 32-byte URL-safe random token (~43 chars after base64). */
  private static String generateToken() {
    byte[] buf = new byte[32];
    RNG.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }
}
