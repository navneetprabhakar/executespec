package com.navneet.executespec.auth.service;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * RR-009 / RR-010 (2026-07-21, P1) — SUPER_ADMIN user status/tier/role mutations, extracted out of
 * {@code SuperAdminController} so the controller stays under the god-class ceiling and every path
 * validates its input, enforces the platform invariants, and appends its audit row in the SAME
 * transaction as the domain write.
 *
 * <p>Each method:
 *
 * <ul>
 *   <li>parses the raw request value through {@link AdminMutationValues} (400 on an out-of-domain
 *       value) instead of persisting an arbitrary string;
 *   <li>refetches the user under a pessimistic row lock and writes from current DB state (no
 *       stale-snapshot overwrite);
 *   <li>refuses to demote or suspend the last active SUPER_ADMIN, and refuses an admin's
 *       self-suspension / self-demotion — a lockout is not a recoverable mistake;
 *   <li>serializes audit metadata with Jackson (RR-009) rather than string concatenation, so a
 *       value can never inject unbalanced quotes or control characters into the audit chain.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminUserMutationService {

  private final AppUserRepository userRepo;
  private final ActivityAuditService auditService;
  private final PaymentAuditService paymentAuditService;
  private final ObjectMapper objectMapper;

  /** Outcome of a status change so the caller can fire side effects (e.g. suspension email). */
  public record StatusChange(AppUser user, String oldStatus, String newStatus) {
    public boolean newlySuspended() {
      return "SUSPENDED".equals(newStatus) && !"SUSPENDED".equals(oldStatus);
    }
  }

  /** Outcome of a tier change so the caller can fire the tier-upgrade email. */
  public record TierChange(AppUser user, String oldTier, String newTier) {
    public boolean changed() {
      return !java.util.Objects.equals(oldTier, newTier);
    }
  }

  @Transactional
  public StatusChange updateStatus(String actorId, String userId, String rawStatus) {
    String newStatus = AdminMutationValues.requireUserStatus(rawStatus);
    AppUser user = lock(userId);
    String oldStatus = user.getStatus();

    boolean becomingUnusable = !"ACTIVE".equals(newStatus);
    if (becomingUnusable) {
      if (userId.equals(actorId)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "You cannot suspend or deactivate your own account.");
      }
      if ("SUPER_ADMIN".equals(user.getGlobalRole())
          && "ACTIVE".equals(oldStatus)
          && userRepo.countActiveSuperAdmins() <= 1) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Cannot suspend the last active SUPER_ADMIN.");
      }
    }

    user.setStatus(newStatus);
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    audit(
        actorId,
        "USER_STATUS_CHANGED",
        userId,
        Map.of("status", oldStatus == null ? "" : oldStatus),
        Map.of("status", newStatus));
    return new StatusChange(user, oldStatus, newStatus);
  }

  @Transactional
  public TierChange updateTier(String actorId, String userId, String rawTier) {
    String newTier = AdminMutationValues.requireTier(rawTier);
    AppUser user = lock(userId);
    String oldTier = user.getTier();
    user.setTier(newTier);
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    paymentAuditService.record("USER", userId, "PLAN_UPGRADED", oldTier, newTier, actorId, null);
    audit(
        actorId,
        "USER_TIER_CHANGED",
        userId,
        Map.of("tier", oldTier == null ? "" : oldTier),
        Map.of("tier", newTier));
    return new TierChange(user, oldTier, newTier);
  }

  @Transactional
  public AppUser updateRole(String actorId, String userId, String rawRole) {
    String newRole = AdminMutationValues.requireGlobalRole(rawRole);
    AppUser user = lock(userId);
    String oldRole = user.getGlobalRole();

    boolean demotingFromSuperAdmin =
        "SUPER_ADMIN".equals(oldRole) && !"SUPER_ADMIN".equals(newRole);
    if (demotingFromSuperAdmin) {
      if (userId.equals(actorId)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "You cannot remove your own SUPER_ADMIN role.");
      }
      if ("ACTIVE".equals(user.getStatus()) && userRepo.countActiveSuperAdmins() <= 1) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Cannot demote the last active SUPER_ADMIN.");
      }
    }

    user.setGlobalRole(newRole);
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    audit(
        actorId,
        "USER_ROLE_CHANGED",
        userId,
        Map.of("role", oldRole == null ? "" : oldRole),
        Map.of("role", newRole));
    return user;
  }

  private AppUser lock(String userId) {
    return userRepo
        .findByIdForUpdate(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
  }

  private void audit(
      String actorId,
      String eventType,
      String userId,
      Map<String, Object> oldValue,
      Map<String, Object> newValue) {
    auditService.record(
        ActivityAudit.builder()
            .eventType(eventType)
            .entityType("USER")
            .entityId(userId)
            .actorId(actorId)
            .oldValue(json(oldValue))
            .newValue(json(newValue))
            .build());
  }

  private String json(Map<String, Object> fields) {
    try {
      return objectMapper.writeValueAsString(fields);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return "{}";
    }
  }
}
