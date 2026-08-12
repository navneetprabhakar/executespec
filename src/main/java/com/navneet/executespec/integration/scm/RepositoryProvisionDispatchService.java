package com.navneet.executespec.integration.scm;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Journals one exact repository-creation side effect before it reaches the SCM provider. */
@Service
@RequiredArgsConstructor
public class RepositoryProvisionDispatchService {

  private final PublicationPreparationStore store;

  public String provision(
      PublicationAdmissionService.Admission admission,
      PublicationPreparationStore.PreparedRepository repository,
      String provider,
      Supplier<String> remoteProvision) {
    if (admission == null || admission.prepared() == null) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_ADMISSION_MISSING");
    }
    if (!repository.provisionRequired()) {
      throw new IllegalStateException("REPOSITORY_PROVISION_NOT_ADMITTED");
    }
    admission.assertOwned();
    UUID preparationId = admission.prepared().preparation().preparationId();
    if (admission.prepared().repositories().stream()
        .noneMatch(
            candidate ->
                candidate.repositoryReceiptId().equals(repository.repositoryReceiptId()))) {
      throw new IllegalStateException("PUBLICATION_REPOSITORY_RECEIPT_NOT_ADMITTED");
    }
    String normalizedProvider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
    if (!repository.repositoryIdentity().startsWith(normalizedProvider + ":")) {
      throw new IllegalStateException("REPOSITORY_PROVISION_PROVIDER_MISMATCH");
    }

    PublicationPreparationStore.RepositoryProvision provision =
        store.admitRepositoryProvision(preparationId, repository, normalizedProvider);
    if ("PROVISIONED".equals(provision.state())) {
      assertRemoteIdentity(repository, provision.remoteUrl());
      return provision.remoteUrl();
    }
    if (!"ADMITTED".equals(provision.state())) {
      throw new IllegalStateException(
          "REPOSITORY_PROVISION_OUTCOME_UNRESOLVED:" + provision.state());
    }

    admission.assertOwned();
    store.markRepositoryProvisionDispatched(provision.provisionId());
    try {
      admission.assertOwned();
      String remoteUrl = remoteProvision.get();
      String canonical = assertRemoteIdentity(repository, remoteUrl);
      store.settleRepositoryProvision(
          provision.provisionId(),
          admission.leaseForSettlement(),
          true,
          remoteUrl,
          repository.repositoryIdentityDigest(),
          null);
      return remoteUrl;
    } catch (RuntimeException failure) {
      try {
        store.settleRepositoryProvision(
            provision.provisionId(),
            admission.leaseForSettlement(),
            false,
            null,
            null,
            "REPOSITORY_PROVISION_REMOTE_OUTCOME_UNPROVEN");
      } catch (RuntimeException settlementFailure) {
        failure.addSuppressed(settlementFailure);
      }
      throw failure;
    }
  }

  private static String assertRemoteIdentity(
      PublicationPreparationStore.PreparedRepository repository, String remoteUrl) {
    String canonical =
        PublicationTargetResolver.canonicalRemoteIdentity(
            repository.repositoryIdentity(), remoteUrl);
    if (!repository.repositoryIdentity().equalsIgnoreCase(canonical)) {
      throw new IllegalStateException("REPOSITORY_PROVISION_REMOTE_IDENTITY_MISMATCH");
    }
    return canonical;
  }
}
