package com.navneet.executespec.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.navneet.executespec.auth.dto.AuditLogEntry;
import com.navneet.executespec.auth.dto.AuditLogPage;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.repository.ActivityAuditRepository;
import com.navneet.executespec.auth.repository.ActivityAuditSpec;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityAuditService {

  /** M-019 (V147) — genesis prev_hash for the very first chained row. */
  static final String GENESIS_HASH = "0".repeat(64);

  private final ActivityAuditRepository repo;
  private final AppUserRepository userRepo;

  @PersistenceContext private EntityManager em;

  /**
   * Record an audit event. Assigns a UUID if the entry has no id, then stamps the M-019 chain
   * triple (prev_hash / row_hash / chain_seq).
   *
   * <p>Synchronized at the JVM level — the chain only works if writers see a consistent tail at
   * write-time. Multi-node deploys would need a Postgres advisory lock; for now we have one app
   * instance.
   */
  public synchronized void record(ActivityAudit entry) {
    if (entry.getId() == null) {
      entry.setId(UUID.randomUUID().toString());
    }
    if (entry.getCreatedAt() == null) {
      entry.setCreatedAt(Instant.now());
    }

    // Stamp chain triple. Read tail → compute hash → assign next seq.
    String prevHash = repo.findChainTail().map(ActivityAudit::getRowHash).orElse(GENESIS_HASH);
    Long nextSeq =
        (Long) em.createNativeQuery("SELECT nextval('activity_audit_chain_seq')").getSingleResult();
    entry.setPrevHash(prevHash);
    entry.setChainSeq(nextSeq);
    entry.setRowHash(computeRowHash(prevHash, entry));

    repo.save(entry);
    log.info(
        "Audit: {} {} {} (actor={}, seq={})",
        entry.getEventType(),
        entry.getEntityType(),
        entry.getEntityId(),
        entry.getActorId(),
        entry.getChainSeq());
  }

  /**
   * M-019 — canonical SHA-256 of (prev_hash || immutable row content). Mutating any persisted field
   * after write will invalidate the chain.
   */
  public static String computeRowHash(String prevHash, ActivityAudit entry) {
    StringBuilder sb =
        new StringBuilder(512)
            .append(prevHash == null ? "" : prevHash)
            .append('|')
            .append(nullSafe(entry.getId()))
            .append('|')
            .append(nullSafe(entry.getEventType()))
            .append('|')
            .append(nullSafe(entry.getEntityType()))
            .append('|')
            .append(nullSafe(entry.getEntityId()))
            .append('|')
            .append(nullSafe(entry.getActorId()))
            .append('|')
            .append(nullSafe(entry.getOrgId()))
            .append('|')
            .append(nullSafe(entry.getOldValue()))
            .append('|')
            .append(nullSafe(entry.getNewValue()))
            .append('|')
            .append(nullSafe(entry.getMetadata()))
            .append('|')
            .append(entry.getCreatedAt() == null ? "" : entry.getCreatedAt().toString());
    return sha256Hex(sb.toString());
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
    }
  }

  /**
   * M-019 — walks every chained row in seq order. Returns null on a clean chain; returns the first
   * {@link ChainBreak} otherwise so SUPER_ADMIN can investigate.
   *
   * <p>Linear; intended to run periodically (e.g. daily cron) or on demand from {@code GET
   * /api/admin/audit-chain/verify}. For 200K-row audit tables this completes in seconds. If we ever
   * scale beyond that, switch to a Merkle-tree summary.
   */
  public ChainVerifyReport verifyChain() {
    var rows = repo.findChainOrdered();
    long checked = 0;
    String expectedPrev = GENESIS_HASH;
    for (ActivityAudit row : rows) {
      checked++;
      // Prev-hash linkage check
      if (!expectedPrev.equals(row.getPrevHash())) {
        return new ChainVerifyReport(
            rows.size(),
            checked,
            false,
            new ChainBreak(
                row.getId(),
                row.getChainSeq(),
                "prev_hash mismatch — expected "
                    + expectedPrev
                    + " but row carries "
                    + row.getPrevHash()));
      }
      // Row-hash recompute check
      String recomputed = computeRowHash(row.getPrevHash(), row);
      if (!recomputed.equals(row.getRowHash())) {
        return new ChainVerifyReport(
            rows.size(),
            checked,
            false,
            new ChainBreak(
                row.getId(),
                row.getChainSeq(),
                "row_hash mismatch — fields mutated after write; "
                    + "expected "
                    + recomputed
                    + " but stored "
                    + row.getRowHash()));
      }
      expectedPrev = row.getRowHash();
    }
    return new ChainVerifyReport(rows.size(), checked, true, null);
  }

  public record ChainBreak(String rowId, Long chainSeq, String reason) {}

  public record ChainVerifyReport(
      int totalChained, long verified, boolean clean, ChainBreak firstBreak) {}

  /** Query audit logs with filters. Returns paginated results with actor email resolved. */
  public AuditLogPage query(
      String orgId,
      String entityType,
      String eventType,
      String actorId,
      String entityId,
      Instant from,
      Instant to,
      int page,
      int size) {

    Specification<ActivityAudit> spec =
        ActivityAuditSpec.buildFilter(orgId, entityType, eventType, actorId, entityId, from, to);

    return AuditLogPage.from(
        repo.findAll(spec, PageRequest.of(page, Math.min(size, 100))).map(this::toEntry));
  }

  private AuditLogEntry toEntry(ActivityAudit a) {
    String actorEmail = null;
    if (a.getActorId() != null) {
      actorEmail = userRepo.findById(a.getActorId()).map(u -> u.getEmail()).orElse(null);
    }
    return new AuditLogEntry(
        a.getId(),
        a.getEventType(),
        a.getEntityType(),
        a.getEntityId(),
        a.getOrgId(),
        a.getActorId(),
        actorEmail,
        a.getOldValue(),
        a.getNewValue(),
        a.getMetadata(),
        a.getCreatedAt());
  }
}
