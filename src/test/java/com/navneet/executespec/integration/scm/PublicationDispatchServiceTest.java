package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.tool.GitToolResult;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PublicationDispatchServiceTest {

  @Test
  void journalsAdmissionAndDispatchBeforeExactRemoteSideEffectThenSettles() {
    PublicationPreparationStore store = mock(PublicationPreparationStore.class);
    PublicationDispatchService service = new PublicationDispatchService(store);
    Fixture fixture = fixture();
    UUID attemptId = UUID.randomUUID();
    when(store.admitPublicationAttempt(
            eq(fixture.preparationId()), eq(fixture.repository()), anyString(), eq(null)))
        .thenReturn(
            new PublicationPreparationStore.PublicationAttempt(
                attemptId,
                fixture.preparationId(),
                fixture.repository().repositoryReceiptId(),
                fixture.repository().publicationBranch(),
                fixture.repository().commitId(),
                "ADMITTED",
                "publication:test",
                1));
    AtomicBoolean sideEffectRan = new AtomicBoolean();

    GitToolResult result =
        service.push(
            fixture.admission(),
            fixture.repository(),
            "https://github.com/owner/repo",
            null,
            () -> {
              sideEffectRan.set(true);
              return new GitToolResult.Ok("PUSHED_PREPARED:" + fixture.repository().commitId());
            });

    assertThat(sideEffectRan).isTrue();
    assertThat(result.isOk()).isTrue();
    InOrder order = inOrder(store);
    order
        .verify(store)
        .admitPublicationAttempt(
            eq(fixture.preparationId()), eq(fixture.repository()), anyString(), eq(null));
    order.verify(store).markPublicationDispatched(attemptId);
    order
        .verify(store)
        .settlePublication(eq(attemptId), eq(fixture.lease()), eq(true), anyString(), eq(null));
  }

  @Test
  void exceptionAfterDispatchSettlesIndeterminateAndNeverClaimsPublished() {
    PublicationPreparationStore store = mock(PublicationPreparationStore.class);
    PublicationDispatchService service = new PublicationDispatchService(store);
    Fixture fixture = fixture();
    UUID attemptId = UUID.randomUUID();
    when(store.admitPublicationAttempt(any(), any(), anyString(), eq(null)))
        .thenReturn(
            new PublicationPreparationStore.PublicationAttempt(
                attemptId,
                fixture.preparationId(),
                fixture.repository().repositoryReceiptId(),
                fixture.repository().publicationBranch(),
                fixture.repository().commitId(),
                "ADMITTED",
                "publication:test",
                1));

    assertThatThrownBy(
            () ->
                service.push(
                    fixture.admission(),
                    fixture.repository(),
                    "https://github.com/owner/repo",
                    null,
                    () -> {
                      throw new IllegalStateException("connection reset after write");
                    }))
        .isInstanceOf(IllegalStateException.class);

    org.mockito.Mockito.verify(store)
        .settlePublication(
            eq(attemptId),
            eq(fixture.lease()),
            eq(false),
            anyString(),
            eq("PUBLICATION_REMOTE_OUTCOME_UNPROVEN"));
  }

  private static Fixture fixture() {
    UUID preparationId = UUID.randomUUID();
    PublicationPreparationStore.PreparedRepository repository =
        new PublicationPreparationStore.PreparedRepository(
            UUID.randomUUID(),
            "GITHUB:owner/repo",
            "a".repeat(64),
            ".",
            "executespec/run",
            "1".repeat(40),
            "2".repeat(40),
            "3".repeat(40),
            true,
            false,
            "prepared commit",
            Instant.parse("2026-08-12T12:00:00Z"));
    PublicationPreparationService.RequiredPreparation required =
        new PublicationPreparationService.RequiredPreparation(
            new PublicationPreparationStore.PreparedPublication(
                preparationId, "run-1", "b".repeat(64), "report-1", "c".repeat(64), Instant.now()),
            List.of(repository));
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn("run-1");
    when(lease.ownerToken()).thenReturn(UUID.randomUUID());
    when(lease.authority()).thenReturn(RunWorkspaceAuthorityService.Authority.PUBLICATION);
    PublicationAdmissionService.Admission admission =
        new PublicationAdmissionService.Admission(
            "b".repeat(64), "PUBLICATION_ELIGIBLE", lease, required);
    return new Fixture(preparationId, repository, admission, lease);
  }

  private record Fixture(
      UUID preparationId,
      PublicationPreparationStore.PreparedRepository repository,
      PublicationAdmissionService.Admission admission,
      RunWorkspaceAuthorityService.Lease lease) {}
}
