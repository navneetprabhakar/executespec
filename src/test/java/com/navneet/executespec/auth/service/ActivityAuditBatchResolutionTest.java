package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.dto.AuditLogPage;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.ActivityAuditRepository;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/** RR-209 — audit-page actor resolution is batched, and chain verification is paged. */
class ActivityAuditBatchResolutionTest {

  @Test
  @SuppressWarnings("unchecked")
  void queryResolvesActorEmailsInOneBatchNotPerRow() {
    ActivityAuditRepository repo = mock(ActivityAuditRepository.class);
    AppUserRepository userRepo = mock(AppUserRepository.class);
    ActivityAuditService service = new ActivityAuditService(repo, userRepo);

    ActivityAudit r1 = ActivityAudit.builder().id("a1").actorId("u1").eventType("E").build();
    ActivityAudit r2 = ActivityAudit.builder().id("a2").actorId("u2").eventType("E").build();
    ActivityAudit r3 = ActivityAudit.builder().id("a3").actorId("u1").eventType("E").build();
    ActivityAudit r4 = ActivityAudit.builder().id("a4").actorId(null).eventType("E").build();
    when(repo.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(r1, r2, r3, r4), PageRequest.of(0, 20), 4));
    when(userRepo.findAllById(any()))
        .thenReturn(
            List.of(
                AppUser.builder().id("u1").email("u1@x.com").build(),
                AppUser.builder().id("u2").email("u2@x.com").build()));

    AuditLogPage page = service.query(null, null, null, null, null, null, null, 0, 20);

    // Was one findById per row (N+1); now a single distinct-actor batch, zero per-row lookups.
    verify(userRepo, times(1)).findAllById(any());
    verify(userRepo, never()).findById(anyString());
    assertThat(page.content()).hasSize(4);
    assertThat(page.content().get(0).actorEmail()).isEqualTo("u1@x.com");
    assertThat(page.content().get(1).actorEmail()).isEqualTo("u2@x.com");
    assertThat(page.content().get(2).actorEmail()).isEqualTo("u1@x.com");
    assertThat(page.content().get(3).actorEmail()).isNull();
  }

  @Test
  void verifyChainPagesTheLedgerInsteadOfLoadingItWhole() {
    ActivityAuditRepository repo = mock(ActivityAuditRepository.class);
    AppUserRepository userRepo = mock(AppUserRepository.class);
    ActivityAuditService service = new ActivityAuditService(repo, userRepo);

    // Valid chain: genesis -> row1 -> row2. rowHash is not part of the digest, so compute it from a
    // base row then rebuild with the hash attached.
    ActivityAudit row1Base =
        ActivityAudit.builder()
            .id("r1")
            .eventType("E")
            .entityType("T")
            .chainSeq(1L)
            .prevHash(ActivityAuditService.GENESIS_HASH)
            .build();
    String rh1 = ActivityAuditService.computeRowHashV2(ActivityAuditService.GENESIS_HASH, row1Base);
    row1Base.setRowHash(rh1);

    ActivityAudit row2Base =
        ActivityAudit.builder()
            .id("r2")
            .eventType("E")
            .entityType("T")
            .chainSeq(2L)
            .prevHash(rh1)
            .build();
    String rh2 = ActivityAuditService.computeRowHashV2(rh1, row2Base);
    row2Base.setRowHash(rh2);

    when(repo.countChained()).thenReturn(2L);
    when(repo.findChainOrderedAfter(anyLong(), any(Pageable.class)))
        .thenReturn(List.of(row1Base, row2Base));

    ActivityAuditService.ChainVerifyReport report = service.verifyChain();

    assertThat(report.clean()).isTrue();
    assertThat(report.totalChained()).isEqualTo(2);
    assertThat(report.verified()).isEqualTo(2L);
    // Uses the paged keyset walk, never the unbounded findChainOrdered().
    verify(repo).findChainOrderedAfter(anyLong(), any(Pageable.class));
    verify(repo, never()).findChainOrdered();
  }
}
