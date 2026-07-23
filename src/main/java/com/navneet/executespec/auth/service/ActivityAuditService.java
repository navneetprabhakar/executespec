package com.navneet.executespec.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.navneet.executespec.api.PageBounds;
import com.navneet.executespec.auth.dto.AuditLogEntry;
import com.navneet.executespec.auth.dto.AuditLogPage;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.repository.ActivityAuditRepository;
import com.navneet.executespec.auth.repository.ActivityAuditSpec;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityAuditService {

  /** M-019 (V147) — genesis prev_hash for the very first chained row. */
  static final String GENESIS_HASH = "0".repeat(64);

  /** RR-209 — rows the streaming chain verifier holds in heap at once. */
  private static final int CHAIN_VERIFY_PAGE_SIZE = 1000;

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
  @Transactional
  public synchronized void record(ActivityAudit entry) {
    if (entry.getId() == null) {
      entry.setId(UUID.randomUUID().toString());
    }
    if (entry.getCreatedAt() == null) {
      entry.setCreatedAt(Instant.now());
    }

    // V292: serialize chain appends across JVMs before reading the tail. The transaction-scoped
    // advisory lock releases automatically at commit/rollback and does not require a lock row.
    em.createNativeQuery(
            "SELECT pg_advisory_xact_lock(hashtext('executespec'),"
                + " hashtext('activity_audit_chain'))")
        .getSingleResult();

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
   * RR-208 — schema tag for the canonical, length-prefixed row-hash encoding. Authenticated as the
   * first field so a v1 pre-image can never collide with a v2 pre-image.
   */
  static final String HASH_SCHEMA_V2 = "activity-audit-hash/v2";

  /**
   * M-019 / RR-208 — canonical SHA-256 of (prev_hash || immutable row content). Mutating any
   * persisted field after write will invalidate the chain.
   *
   * <p>New rows are hashed with the {@link #computeRowHashV2 v2} canonical encoding. Verification
   * ({@link #verifyChain}) still recognizes rows written under the legacy {@link #computeRowHashV1
   * v1} encoding, so existing audit rows keep verifying without a rehash/migration.
   */
  public static String computeRowHash(String prevHash, ActivityAudit entry) {
    return computeRowHashV2(prevHash, entry);
  }

  /**
   * RR-208 — canonical, structurally-unambiguous SHA-256 over (prev_hash || immutable row content).
   *
   * <p>The legacy v1 encoding joined nullable, user-controlled fields with a raw {@code '|'} and no
   * length prefix or escaping, so a delimiter character could be shifted between two adjacent field
   * values without changing the hashed byte string (tamper-evidence gap); it also omitted {@code
   * chain_seq}. v2 length-prefixes every field ({@code len:value}), encodes {@code null} distinctly
   * from empty ({@code -1:} vs {@code 0:}), and authenticates a schema tag AND the chain sequence.
   * Because each field is read by exact length rather than by delimiter-scan, no injected {@code
   * '|'} / {@code ':'} can move a field boundary.
   */
  static String computeRowHashV2(String prevHash, ActivityAudit entry) {
    StringBuilder sb = new StringBuilder(640);
    appendLengthPrefixed(sb, HASH_SCHEMA_V2);
    appendLengthPrefixed(sb, prevHash);
    appendLengthPrefixed(sb, entry.getId());
    appendLengthPrefixed(sb, entry.getEventType());
    appendLengthPrefixed(sb, entry.getEntityType());
    appendLengthPrefixed(sb, entry.getEntityId());
    appendLengthPrefixed(sb, entry.getActorId());
    appendLengthPrefixed(sb, entry.getOrgId());
    appendLengthPrefixed(sb, entry.getOldValue());
    appendLengthPrefixed(sb, entry.getNewValue());
    appendLengthPrefixed(sb, entry.getMetadata());
    appendLengthPrefixed(sb, entry.getCreatedAt() == null ? null : entry.getCreatedAt().toString());
    appendLengthPrefixed(sb, entry.getChainSeq() == null ? null : entry.getChainSeq().toString());
    return sha256Hex(sb.toString());
  }

  /**
   * RR-208 — legacy (pre-canonicalization) row-hash encoding. Retained ONLY so audit rows written
   * before v2 continue to verify under {@link #verifyChain}; never used to hash new rows.
   */
  static String computeRowHashV1(String prevHash, ActivityAudit entry) {
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

  /**
   * RR-208 — length-prefixed, null-distinguishing field encoder. {@code null} is {@code -1:} (an
   * impossible length) and cannot collide with any real value's {@code len:value} framing.
   */
  private static void appendLengthPrefixed(StringBuilder sb, String v) {
    if (v == null) {
      sb.append("-1:");
    } else {
      sb.append(v.length()).append(':').append(v);
    }
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
    // RR-209: was a single findChainOrdered() that loaded the entire ledger into one list. The scan
    // is inherently sequential (each row's expectedPrev is the previous row's rowHash), so it is
    // now
    // a checkpointed keyset walk — page by chain_seq carrying expectedPrev/afterSeq across pages —
    // which bounds heap to one page while producing the identical clean/break verdict.
    long total = repo.countChained();
    int totalChained = (int) total;
    long checked = 0;
    String expectedPrev = GENESIS_HASH;
    long afterSeq = Long.MIN_VALUE;
    var pageable = org.springframework.data.domain.PageRequest.of(0, CHAIN_VERIFY_PAGE_SIZE);

    while (true) {
      List<ActivityAudit> rows = repo.findChainOrderedAfter(afterSeq, pageable);
      if (rows.isEmpty()) break;

      for (ActivityAudit row : rows) {
        checked++;
        // Prev-hash linkage check
        if (!expectedPrev.equals(row.getPrevHash())) {
          return new ChainVerifyReport(
              totalChained,
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
        // Row-hash recompute check. RR-208: new rows are hashed with the v2 canonical encoding;
        // rows written before v2 are accepted when they verify under the legacy v1 encoding, so the
        // canonicalization change does not break tamper-verification of existing audit history.
        String recomputed = computeRowHashV2(row.getPrevHash(), row);
        boolean rowHashOk = recomputed.equals(row.getRowHash());
        if (!rowHashOk) {
          rowHashOk = computeRowHashV1(row.getPrevHash(), row).equals(row.getRowHash());
        }
        if (!rowHashOk) {
          return new ChainVerifyReport(
              totalChained,
              checked,
              false,
              new ChainBreak(
                  row.getId(),
                  row.getChainSeq(),
                  "row_hash mismatch — fields mutated after write; "
                      + "expected "
                      + recomputed
                      + " (v2) but stored "
                      + row.getRowHash()));
        }
        expectedPrev = row.getRowHash();
        if (row.getChainSeq() != null) {
          afterSeq = row.getChainSeq();
        }
      }

      if (rows.size() < CHAIN_VERIFY_PAGE_SIZE) break;
    }
    return new ChainVerifyReport(totalChained, checked, true, null);
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

    var rawPage = repo.findAll(spec, PageBounds.request(page, size, 100));

    // RR-209: was one userRepo.findById per row (N+1, up to the page size). Resolve every distinct
    // actor's email in a single batch query, then map from the in-memory index.
    List<String> actorIds =
        rawPage.getContent().stream()
            .map(ActivityAudit::getActorId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    Map<String, String> emailByActor = new HashMap<>();
    if (!actorIds.isEmpty()) {
      userRepo.findAllById(actorIds).forEach(u -> emailByActor.put(u.getId(), u.getEmail()));
    }

    return AuditLogPage.from(rawPage.map(a -> toEntry(a, emailByActor)));
  }

  private AuditLogEntry toEntry(ActivityAudit a, Map<String, String> emailByActor) {
    String actorEmail = a.getActorId() != null ? emailByActor.get(a.getActorId()) : null;
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
