package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RepositoryProvisionDispatchServiceTest {

  @Test
  void journalsDispatchBeforeExactRepositoryCreationAndThenSettles() {
    PublicationPreparationStore store = mock(PublicationPreparationStore.class);
    RepositoryProvisionDispatchService service = new RepositoryProvisionDispatchService(store);
    Fixture fixture = fixture();
    UUID provisionId = UUID.randomUUID();
    when(store.admitRepositoryProvision(fixture.preparationId(), fixture.repository(), "GITHUB"))
        .thenReturn(
            new PublicationPreparationStore.RepositoryProvision(
                provisionId,
                fixture.preparationId(),
                fixture.repository().repositoryReceiptId(),
                "GITHUB",
                fixture.repository().repositoryIdentity(),
                "ADMITTED",
                null,
                1));
    AtomicBoolean created = new AtomicBoolean();

    String remote =
        service.provision(
            fixture.admission(),
            fixture.repository(),
            "github",
            () -> {
              created.set(true);
              return "https://github.com/owner/repo.git";
            });

    assertThat(created).isTrue();
    assertThat(remote).isEqualTo("https://github.com/owner/repo.git");
    InOrder order = inOrder(store);
    order
        .verify(store)
        .admitRepositoryProvision(fixture.preparationId(), fixture.repository(), "GITHUB");
    order.verify(store).markRepositoryProvisionDispatched(provisionId);
    order
        .verify(store)
        .settleRepositoryProvision(
            eq(provisionId),
            eq(fixture.lease()),
            eq(true),
            eq("https://github.com/owner/repo.git"),
            eq(fixture.repository().repositoryIdentityDigest()),
            eq(null));
  }

  @Test
  void ambiguousOrMismatchedCreationSettlesIndeterminate() {
    PublicationPreparationStore store = mock(PublicationPreparationStore.class);
    RepositoryProvisionDispatchService service = new RepositoryProvisionDispatchService(store);
    Fixture fixture = fixture();
    UUID provisionId = UUID.randomUUID();
    when(store.admitRepositoryProvision(fixture.preparationId(), fixture.repository(), "GITHUB"))
        .thenReturn(
            new PublicationPreparationStore.RepositoryProvision(
                provisionId,
                fixture.preparationId(),
                fixture.repository().repositoryReceiptId(),
                "GITHUB",
                fixture.repository().repositoryIdentity(),
                "ADMITTED",
                null,
                1));

    assertThatThrownBy(
            () ->
                service.provision(
                    fixture.admission(),
                    fixture.repository(),
                    "GITHUB",
                    () -> "https://github.com/owner/different"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REPOSITORY_PROVISION_REMOTE_IDENTITY_MISMATCH");

    org.mockito.Mockito.verify(store)
        .settleRepositoryProvision(
            provisionId,
            fixture.lease(),
            false,
            null,
            null,
            "REPOSITORY_PROVISION_REMOTE_OUTCOME_UNPROVEN");
  }

  private static Fixture fixture() {
    UUID preparationId = UUID.randomUUID();
    PublicationPreparationStore.PreparedRepository repository =
        new PublicationPreparationStore.PreparedRepository(
            UUID.randomUUID(),
            "GITHUB:owner/repo",
            PublicationPreparationService.sha256("GITHUB:owner/repo"),
            ".",
            "main",
            "1".repeat(40),
            "2".repeat(40),
            "3".repeat(40),
            true,
            true,
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
