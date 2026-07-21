package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.repository.ActivityAuditRepository;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-208 — pins the versioned canonical hash-chain encoding and its backward-compatible
 * verification.
 *
 * <p>The legacy v1 encoding joined nullable, user-controlled fields with a raw {@code '|'} and no
 * length prefix, so a delimiter character could be shifted between adjacent fields without changing
 * the hashed byte string, and {@code chain_seq} was not authenticated. v2 length-prefixes every
 * field, distinguishes null from empty, and authenticates a schema tag plus the sequence number.
 * Existing rows written under v1 must still verify (no rehash/migration).
 */
class ActivityAuditHashCanonicalizationTest {

  private static ActivityAudit row(String id, String eventType, String entityId, Long seq) {
    return ActivityAudit.builder()
        .id(id)
        .eventType(eventType)
        .entityType("PROJECT")
        .entityId(entityId)
        .actorId("actor-1")
        .orgId("org-1")
        .chainSeq(seq)
        .createdAt(Instant.parse("2026-07-21T00:00:00Z"))
        .build();
  }

  @Test
  void v2_closesDelimiterShiftAmbiguityThatV1Missed() {
    // Two logically distinct rows where a '|' moves between two adjacent field values.
    // v1 concatenates entityType|entityId as "A|B|C" either way, so their pre-images collide.
    // createdAt is pinned identical so only the field-boundary difference is under test.
    Instant when = Instant.parse("2026-07-21T00:00:00Z");
    ActivityAudit a =
        ActivityAudit.builder().entityType("A").entityId("B|C").createdAt(when).build();
    ActivityAudit b =
        ActivityAudit.builder().entityType("A|B").entityId("C").createdAt(when).build();

    // Legacy encoding was ambiguous — same hash for two different field assignments.
    assertThat(ActivityAuditService.computeRowHashV1("prev", a))
        .isEqualTo(ActivityAuditService.computeRowHashV1("prev", b));

    // Canonical v2 encoding tells them apart.
    assertThat(ActivityAuditService.computeRowHashV2("prev", a))
        .isNotEqualTo(ActivityAuditService.computeRowHashV2("prev", b));
  }

  @Test
  void v2_authenticatesChainSequence() {
    ActivityAudit seq1 = row("id-1", "CREATE", "e-1", 1L);
    ActivityAudit seq2 = row("id-1", "CREATE", "e-1", 2L);
    // v1 never hashed chainSeq → identical; v2 does.
    assertThat(ActivityAuditService.computeRowHashV1("prev", seq1))
        .isEqualTo(ActivityAuditService.computeRowHashV1("prev", seq2));
    assertThat(ActivityAuditService.computeRowHashV2("prev", seq1))
        .isNotEqualTo(ActivityAuditService.computeRowHashV2("prev", seq2));
  }

  @Test
  void v2_distinguishesNullFromEmpty() {
    Instant when = Instant.parse("2026-07-21T00:00:00Z");
    ActivityAudit nullMeta =
        ActivityAudit.builder().eventType("E").metadata(null).createdAt(when).build();
    ActivityAudit emptyMeta =
        ActivityAudit.builder().eventType("E").metadata("").createdAt(when).build();
    assertThat(ActivityAuditService.computeRowHashV2("prev", nullMeta))
        .isNotEqualTo(ActivityAuditService.computeRowHashV2("prev", emptyMeta));
  }

  @Test
  void computeRowHash_publicEntryPointProducesV2() {
    ActivityAudit r = row("id-1", "CREATE", "e-1", 7L);
    assertThat(ActivityAuditService.computeRowHash("prev", r))
        .isEqualTo(ActivityAuditService.computeRowHashV2("prev", r))
        .hasSize(64);
  }

  @Test
  void verifyChain_acceptsLegacyV1HashedRows() {
    // Simulate a row persisted before v2: its stored row_hash is the v1 digest.
    ActivityAudit legacy = row("legacy-1", "UPDATE", "e-9", 1L);
    legacy.setPrevHash(ActivityAuditService.GENESIS_HASH);
    legacy.setRowHash(ActivityAuditService.computeRowHashV1(legacy.getPrevHash(), legacy));

    ActivityAuditService svc = serviceWith(List.of(legacy));
    var report = svc.verifyChain();
    assertThat(report.clean()).isTrue();
    assertThat(report.firstBreak()).isNull();
  }

  @Test
  void verifyChain_acceptsV2HashedRows() {
    ActivityAudit fresh = row("v2-1", "UPDATE", "e-9", 1L);
    fresh.setPrevHash(ActivityAuditService.GENESIS_HASH);
    fresh.setRowHash(ActivityAuditService.computeRowHash(fresh.getPrevHash(), fresh));

    ActivityAuditService svc = serviceWith(List.of(fresh));
    assertThat(svc.verifyChain().clean()).isTrue();
  }

  @Test
  void verifyChain_flagsTamperedRow() {
    ActivityAudit tampered = row("t-1", "UPDATE", "e-9", 1L);
    tampered.setPrevHash(ActivityAuditService.GENESIS_HASH);
    tampered.setRowHash(ActivityAuditService.computeRowHash(tampered.getPrevHash(), tampered));
    // Mutate a field after the hash was stamped.
    tampered.setEntityId("e-TAMPERED");

    ActivityAuditService svc = serviceWith(List.of(tampered));
    var report = svc.verifyChain();
    assertThat(report.clean()).isFalse();
    assertThat(report.firstBreak()).isNotNull();
    assertThat(report.firstBreak().reason()).contains("row_hash mismatch");
  }

  private static ActivityAuditService serviceWith(List<ActivityAudit> rows) {
    ActivityAuditRepository repo = mock(ActivityAuditRepository.class);
    when(repo.findChainOrdered()).thenReturn(rows);
    return new ActivityAuditService(repo, mock(AppUserRepository.class));
  }
}
