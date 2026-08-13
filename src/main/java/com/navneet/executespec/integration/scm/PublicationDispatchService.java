package com.navneet.executespec.integration.scm;

import java.util.UUID;
import java.util.function.Supplier;

import com.navneet.executespec.tool.GitToolResult;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Journals and fences the exact prepared commit immediately around one remote Git push. */
@Service
@RequiredArgsConstructor
public class PublicationDispatchService {

  private final PublicationPreparationStore store;

  public GitToolResult push(
      PublicationAdmissionService.Admission admission,
      PublicationPreparationStore.PreparedRepository repository,
      String remoteIdentity,
      String expectedRemoteSha,
      Supplier<GitToolResult> remotePush) {
    if (admission == null || admission.prepared() == null) {
      throw new IllegalStateException("PUBLICATION_PREPARATION_ADMISSION_MISSING");
    }
    admission.assertOwned();
    UUID preparationId = admission.prepared().preparation().preparationId();
    if (admission.prepared().repositories().stream()
        .noneMatch(r -> r.repositoryReceiptId().equals(repository.repositoryReceiptId()))) {
      throw new IllegalStateException("PUBLICATION_REPOSITORY_RECEIPT_NOT_ADMITTED");
    }
    String canonicalRemote =
        PublicationTargetResolver.canonicalRemoteIdentity(
            repository.repositoryIdentity(), remoteIdentity);
    if (!repository.repositoryIdentity().equalsIgnoreCase(canonicalRemote)) {
      throw new IllegalStateException("PUBLICATION_REMOTE_IDENTITY_MISMATCH");
    }
    String remoteDigest = PublicationPreparationService.sha256(canonicalRemote.toLowerCase());
    PublicationPreparationStore.PublicationAttempt attempt =
        store.admitPublicationAttempt(
            preparationId, repository, remoteDigest, normalizedSha(expectedRemoteSha));
    if ("PUBLISHED".equals(attempt.state())) {
      return new GitToolResult.Ok("PUSHED_PREPARED_REPLAY:" + attempt.preparedCommitId());
    }
    if (!"ADMITTED".equals(attempt.state())) {
      throw new IllegalStateException("PUBLICATION_ATTEMPT_OUTCOME_UNRESOLVED:" + attempt.state());
    }
    admission.assertOwned();
    store.markPublicationDispatched(attempt.attemptId());
    try {
      admission.assertOwned();
      GitToolResult result = remotePush.get();
      boolean published = result != null && result.isOk();
      store.settlePublication(
          attempt.attemptId(),
          admission.leaseForSettlement(),
          published,
          PublicationPreparationService.sha256(result == null ? "null" : result.value()),
          published ? null : "PUBLICATION_REMOTE_OUTCOME_UNPROVEN");
      return result == null
          ? new GitToolResult.Err("prepared publication returned no outcome")
          : result;
    } catch (RuntimeException failure) {
      try {
        store.settlePublication(
            attempt.attemptId(),
            admission.leaseForSettlement(),
            false,
            PublicationPreparationService.sha256(failure.getClass().getName()),
            "PUBLICATION_REMOTE_OUTCOME_UNPROVEN");
      } catch (RuntimeException settlementFailure) {
        failure.addSuppressed(settlementFailure);
      }
      throw failure;
    }
  }

  private static String normalizedSha(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase();
  }
}
