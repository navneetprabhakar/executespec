package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.api.dto.RunRequest;
import com.navneet.executespec.run.RunRequestFingerprint;

import org.junit.jupiter.api.Test;

/**
 * RR-1553 — the submission fingerprint is the sole equality test behind idempotent submit. Anything
 * it omits is silently discarded when a caller retries with the same Idempotency-Key: the server
 * returns the EARLIER run with HTTP 202 and no signal that the corrected value was dropped.
 */
class RunControllerSubmitFingerprintTest {

  private static RunRequest base() {
    return new RunRequest(
        "build a thing",
        "MANUAL",
        null,
        null,
        null,
        null,
        "PRODUCTION",
        null,
        "ANTHROPIC",
        "proj-1",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static String hash(RunRequest req) {
    return RunRequestFingerprint.of(
        new java.util.ArrayList<>(RunController.fingerprintOf(req).values()));
  }

  /**
   * Every {@link RunRequest} component must be fingerprinted. A future field added to the DTO and
   * forgotten here would reintroduce exactly this defect class, so the assertion is reflective
   * rather than a hand-maintained count.
   */
  @Test
  void everyRunRequestComponentIsFingerprinted() {
    List<String> components =
        java.util.Arrays.stream(RunRequest.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();

    assertThat(RunController.fingerprintOf(base()).keySet())
        .as(
            "a RunRequest field that is not fingerprinted is silently droppable on an idempotent"
                + " retry")
        .containsExactlyInAnyOrderElementsOf(components);
  }

  /**
   * The headline defect: a user submits, realises "Confirm model upgrades" was off, and retries
   * with the same key and the guard ON. Before this fix both bodies hashed identically, so the
   * retry resolved to the earlier UNGUARDED run.
   */
  @Test
  void enablingTheContinuationConfirmationGuardChangesTheFingerprint() {
    RunRequest unguarded = base();
    RunRequest guarded =
        new RunRequest(
            unguarded.userRequest(),
            unguarded.source(),
            unguarded.repoUrl(),
            unguarded.repoMode(),
            unguarded.skipApproval(),
            unguarded.autoMerge(),
            unguarded.executionMode(),
            unguarded.modelName(),
            unguarded.aiProvider(),
            unguarded.projectId(),
            unguarded.selectedRepoIds(),
            unguarded.figmaUrl(),
            unguarded.tddMode(),
            unguarded.branch(),
            unguarded.prReviewComments(),
            unguarded.userCostCap(),
            unguarded.skipAutoPush(),
            Boolean.TRUE,
            unguarded.requireBenchmarkPriorForAutomaticContinuation(),
            unguarded.lowComplexityWorkerStartTier(),
            unguarded.localExecution(),
            unguarded.localInstallationId(),
            unguarded.localRepositoryInventory());

    assertThat(hash(guarded)).isNotEqualTo(hash(unguarded));
  }

  @Test
  void enablingTheBenchmarkPriorGuardChangesTheFingerprint() {
    RunRequest before = base();
    RunRequest after =
        new RunRequest(
            before.userRequest(),
            before.source(),
            before.repoUrl(),
            before.repoMode(),
            before.skipApproval(),
            before.autoMerge(),
            before.executionMode(),
            before.modelName(),
            before.aiProvider(),
            before.projectId(),
            before.selectedRepoIds(),
            before.figmaUrl(),
            before.tddMode(),
            before.branch(),
            before.prReviewComments(),
            before.userCostCap(),
            before.skipAutoPush(),
            before.requireConfirmationBeforeExpensiveContinuation(),
            Boolean.TRUE,
            before.lowComplexityWorkerStartTier(),
            before.localExecution(),
            before.localInstallationId(),
            before.localRepositoryInventory());

    assertThat(hash(after)).isNotEqualTo(hash(before));
  }

  @Test
  void changingTheLocalRepositoryInventoryChangesTheFingerprint() {
    RunRequest before = base();
    RunRequest after =
        new RunRequest(
            before.userRequest(),
            before.source(),
            before.repoUrl(),
            before.repoMode(),
            before.skipApproval(),
            before.autoMerge(),
            before.executionMode(),
            before.modelName(),
            before.aiProvider(),
            before.projectId(),
            before.selectedRepoIds(),
            before.figmaUrl(),
            before.tddMode(),
            before.branch(),
            before.prReviewComments(),
            before.userCostCap(),
            before.skipAutoPush(),
            before.requireConfirmationBeforeExpensiveContinuation(),
            before.requireBenchmarkPriorForAutomaticContinuation(),
            before.lowComplexityWorkerStartTier(),
            before.localExecution(),
            before.localInstallationId(),
            "src/\napp/");

    assertThat(hash(after)).isNotEqualTo(hash(before));
  }

  /**
   * Repo selection is a set. {@code String.valueOf(List)} made a reordered but identical selection
   * hash differently, producing a spurious CONFLICT on a semantically identical retry.
   */
  @Test
  void reorderingSelectedRepoIdsKeepsTheSameFingerprint() {
    UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    assertThat(hash(withRepos(List.of(a, b)))).isEqualTo(hash(withRepos(List.of(b, a))));
  }

  @Test
  void aDifferentRepoSelectionStillConflicts() {
    UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    UUID c = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    assertThat(hash(withRepos(List.of(a, b)))).isNotEqualTo(hash(withRepos(List.of(a, c))));
    assertThat(hash(withRepos(List.of(a, b)))).isNotEqualTo(hash(withRepos(List.of(a))));
  }

  private static RunRequest withRepos(List<UUID> repos) {
    RunRequest b = base();
    return new RunRequest(
        b.userRequest(),
        b.source(),
        b.repoUrl(),
        b.repoMode(),
        b.skipApproval(),
        b.autoMerge(),
        b.executionMode(),
        b.modelName(),
        b.aiProvider(),
        b.projectId(),
        repos,
        b.figmaUrl(),
        b.tddMode(),
        b.branch(),
        b.prReviewComments(),
        b.userCostCap(),
        b.skipAutoPush(),
        b.requireConfirmationBeforeExpensiveContinuation(),
        b.requireBenchmarkPriorForAutomaticContinuation(),
        b.lowComplexityWorkerStartTier(),
        b.localExecution(),
        b.localInstallationId(),
        b.localRepositoryInventory());
  }
}
