package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class RunCostEvidenceServiceTest {

  private static final int SIZE = RunCostEvidenceService.DEFAULT_STEP_CALLS_PAGE_SIZE;

  /**
   * The repository page is NEWEST-first; the service reverses within the page so the emitted array
   * keeps its historical oldest-to-newest orientation. Tests therefore hand rows back in reverse.
   */
  private static void stubLedger(
      RunStepUsageRepository repo,
      String runId,
      List<RunStepUsage> chronological,
      long unknownCostAttempts,
      double knownCost) {
    List<RunStepUsage> newestFirst = new java.util.ArrayList<>(chronological);
    java.util.Collections.reverse(newestFirst);
    when(repo.findByRunIdOrderByCreatedAtDesc(eq(runId), any(Pageable.class)))
        .thenReturn((Page<RunStepUsage>) new PageImpl<>(newestFirst));
    when(repo.countByRunId(runId)).thenReturn((long) chronological.size());
    when(repo.countByRunIdAndAttemptStatus(runId, "SUCCESS"))
        .thenReturn(
            chronological.stream()
                .filter(call -> "SUCCESS".equals(call.getAttemptStatus()))
                .count());
    when(repo.countUnknownCostAttemptsByRunId(runId)).thenReturn(unknownCostAttempts);
    when(repo.sumKnownCostByRunId(runId)).thenReturn(knownCost);
  }

  private static AppUserDetails principal(String globalRole, String orgId, String orgRole) {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setGlobalRole(globalRole);
    user.setOrgId(orgId);
    OrgMember member = null;
    if (orgRole != null) {
      member = new OrgMember();
      member.setOrgId(orgId);
      member.setOrgRole(orgRole);
    }
    return new AppUserDetails(user, member);
  }

  @Test
  void exposesCompleteCallAttributionCoherenceAndHonestRemainder() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("r1");
    run.setCoherenceCostUsd(0.04);
    RunStepUsage call =
        RunStepUsage.builder()
            .id("c1")
            .runId("r1")
            .taskId("t1")
            .agentRole("worker")
            .stepId("worker:t1:1")
            .retryOf("worker:t1:0")
            .attemptStatus("SUCCESS")
            .provider("deepseek")
            .model("deepseek-chat")
            .inputTokens(100)
            .outputTokens(20)
            .cacheReadTokens(30)
            .cacheWriteTokens(5)
            .reasoningTokens(8)
            .costUsd(0.40)
            .callIndex(2)
            .createdAt(Instant.parse("2026-07-17T00:00:00Z"))
            .build();
    stubLedger(repository, "r1", List.of(call), 0L, 0.40d);
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 0.50));

    service.enrich(body, run, true, 0, SIZE);

    assertThat(body.get("phases"))
        .isInstanceOfSatisfying(
            Map.class, phases -> assertThat(phases.get("coherence")).isEqualTo(0.04));
    assertThat((List<?>) body.get("stepCalls"))
        .singleElement()
        .asString()
        .contains("deepseek-chat");
    assertThat(body).containsEntry("attributedCostUsd", 0.40).containsEntry("reconciled", false);
    assertThat((double) body.get("unattributedCostUsd")).isCloseTo(0.10, within(0.000001));
  }

  @Test
  void asyncAttemptLedgerCannotProduceNegativeUnattributedCostOrStaleTokenTotals() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("async");
    stubLedger(repository, "async", List.of(), 0L, 0.133d);
    when(repository.sumSuccessfulInputTokensByRunId("async")).thenReturn(1_700_000L);
    when(repository.sumSuccessfulOutputTokensByRunId("async")).thenReturn(98_000L);
    when(repository.sumSuccessfulCacheReadTokensByRunId("async")).thenReturn(1_590_000L);
    when(repository.sumSuccessfulCacheWriteTokensByRunId("async")).thenReturn(12L);
    when(repository.sumSuccessfulReasoningTokensByRunId("async")).thenReturn(55_000L);
    Map<String, Object> body =
        new LinkedHashMap<>(
            Map.of(
                "costUsd", 0.129d,
                "inputTokens", 1_689_000L,
                "outputTokens", 94_000L));

    service.enrich(body, run, true, 0, SIZE);

    assertThat(body)
        .containsEntry("costUsd", 0.133d)
        .containsEntry("pipelineRecordedCostUsd", 0.129d)
        .containsEntry("inputTokens", 1_700_000L)
        .containsEntry("outputTokens", 98_000L)
        .containsEntry("cacheReadTokens", 1_590_000L)
        .containsEntry("cacheWriteTokens", 12L)
        .containsEntry("reasoningTokens", 55_000L)
        .containsEntry("unattributedCostUsd", 0.0d)
        .containsEntry("reconciled", true);
  }

  @Test
  void terminalNullCacheFieldsAreReplacedByLedgerTotalsInsteadOfCrashing() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("terminal");
    stubLedger(repository, "terminal", List.of(), 0L, 0.16d);
    when(repository.sumSuccessfulInputTokensByRunId("terminal")).thenReturn(1_000L);
    when(repository.sumSuccessfulOutputTokensByRunId("terminal")).thenReturn(100L);
    when(repository.sumSuccessfulCacheReadTokensByRunId("terminal")).thenReturn(800L);
    when(repository.sumSuccessfulCacheWriteTokensByRunId("terminal")).thenReturn(20L);
    when(repository.sumSuccessfulReasoningTokensByRunId("terminal")).thenReturn(40L);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("costUsd", 0.12d);
    body.put("inputTokens", 900L);
    body.put("outputTokens", 90L);
    body.put("cacheReadTokens", null);
    body.put("cacheWriteTokens", null);
    body.put("reasoningTokens", null);
    body.put("source", "stored_total");

    service.enrich(body, run, true, 0, SIZE);

    assertThat(body)
        .containsEntry("costUsd", 0.16d)
        .containsEntry("cacheReadTokens", 800L)
        .containsEntry("cacheWriteTokens", 20L)
        .containsEntry("reasoningTokens", 40L)
        .containsEntry("calls", 0L)
        .containsEntry("source", "stored_total+attempt_ledger");
  }

  /**
   * C1 Step 5 (G5). Without causality fields a cross-provider failover is indistinguishable from a
   * same-provider retry in {@code /api/runs/{id}/cost}. The assertion is chain-shaped on purpose:
   * it pins that the FAILOVER attempt names its parent, not merely that the fields exist.
   */
  @Test
  @SuppressWarnings("unchecked")
  void stepCallsExposeAttemptCausalitySoFailoverIsDistinguishableFromRetry() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("r2");
    RunStepUsage primary =
        RunStepUsage.builder()
            .id("a1")
            .runId("r2")
            .provider("deepseek")
            .model("deepseek-v4-pro-native")
            .modelTier("WORKHORSE")
            .attemptKind("PRIMARY")
            .physicalAttemptId("call-1#1")
            .logicalCallId("call-1")
            .attemptStatus("FAILED")
            .createdAt(Instant.parse("2026-07-19T00:00:00Z"))
            .build();
    RunStepUsage failover =
        RunStepUsage.builder()
            .id("a2")
            .runId("r2")
            .provider("openrouter")
            .model("glm-5.2")
            .modelTier("WORKHORSE")
            .attemptKind("FAILOVER")
            .physicalAttemptId("call-1#2")
            .parentAttemptId("call-1#1")
            .logicalCallId("call-1")
            .attemptStatus("SUCCESS")
            .createdAt(Instant.parse("2026-07-19T00:00:01Z"))
            .build();
    stubLedger(repository, "r2", List.of(primary, failover), 1L, 0.0d);
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 0.0));

    service.enrich(body, run, true, 0, SIZE);

    List<Map<String, Object>> calls = (List<Map<String, Object>>) body.get("stepCalls");
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0))
        .containsEntry("attemptKind", "PRIMARY")
        .containsEntry("physicalAttemptId", "call-1#1")
        .containsEntry("parentAttemptId", null)
        .containsEntry("modelTier", "WORKHORSE")
        .containsEntry("logicalCallId", "call-1");
    assertThat(calls.get(1))
        .containsEntry("attemptKind", "FAILOVER")
        .containsEntry("physicalAttemptId", "call-1#2")
        .containsEntry("parentAttemptId", "call-1#1")
        .containsEntry("provider", "openrouter");
    // The route changed AND the change is attributable to a distinguishable kind + parent, which
    // is the C1 contract: route changes are recorded, not forbidden.
    assertThat(calls.get(0).get("provider")).isNotEqualTo(calls.get(1).get("provider"));
  }

  /**
   * RR-1556. A FAILED attempt is persisted with a hard {@code costUsd = 0}; summing it as fact and
   * reporting {@code reconciled: true} asserts full reconciliation over evidence the codebase
   * elsewhere refuses to call proven.
   */
  @Test
  void aFailedAttemptMakesTheLedgerALowerBoundNotAReconciledTotal() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("r3");
    RunStepUsage failed =
        RunStepUsage.builder()
            .id("f1")
            .runId("r3")
            .attemptStatus("FAILED")
            .provider("deepseek")
            .model("deepseek-chat")
            .costUsd(0d)
            .createdAt(Instant.parse("2026-07-19T00:00:00Z"))
            .build();
    stubLedger(repository, "r3", List.of(failed), 1L, 0d);
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 0d));

    service.enrich(body, run, true, 0, SIZE);

    // The sums agree exactly (0 == 0) yet reconciliation is still refused, because one attempt's
    // cost was never reported.
    assertThat(body)
        .containsEntry("reconciled", false)
        .containsEntry("unknownCostAttempts", 1L)
        .containsEntry("knownCostUsdLowerBound", 0d)
        .containsEntry("costStatus", "UNKNOWN_PROVIDER_NOT_REPORTED_PRESENT");
  }

  /** RR-1556. A model with no pricing row prices to exactly 0 despite consuming tokens. */
  @Test
  @SuppressWarnings("unchecked")
  void anUnpricedModelIsReportedAsUnknownCostNotAProvenZero() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("r4");
    RunStepUsage unpriced =
        RunStepUsage.builder()
            .id("u1")
            .runId("r4")
            .attemptStatus("SUCCESS")
            .provider("openrouter")
            .model("model-missing-from-matrix")
            .inputTokens(4_000)
            .outputTokens(900)
            .costUsd(0d)
            .createdAt(Instant.parse("2026-07-19T00:00:00Z"))
            .build();
    stubLedger(repository, "r4", List.of(unpriced), 1L, 0d);
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 0d));

    service.enrich(body, run, true, 0, SIZE);

    assertThat(body).containsEntry("reconciled", false).containsEntry("unknownCostAttempts", 1L);
    List<Map<String, Object>> calls = (List<Map<String, Object>>) body.get("stepCalls");
    assertThat(calls.get(0))
        .containsEntry("knownCostUsd", null)
        .containsEntry("costStatus", "UNKNOWN_PROVIDER_NOT_REPORTED");
  }

  /** RR-1556. A fully priced, all-SUCCESS run must still reconcile. */
  @Test
  void aFullyPricedAllSuccessRunStillReconciles() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("r5");
    RunStepUsage ok =
        RunStepUsage.builder()
            .id("o1")
            .runId("r5")
            .attemptStatus("SUCCESS")
            .provider("anthropic")
            .model("claude-sonnet-5")
            .inputTokens(1_000)
            .outputTokens(200)
            .costUsd(0.25d)
            .createdAt(Instant.parse("2026-07-19T00:00:00Z"))
            .build();
    stubLedger(repository, "r5", List.of(ok), 0L, 0.25d);
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 0.25d));

    service.enrich(body, run, true, 0, SIZE);

    assertThat(body).containsEntry("reconciled", true).containsEntry("unknownCostAttempts", 0L);
  }

  /**
   * RR-1558. The endpoint is polled continuously on a live run; it must never materialize the whole
   * ledger. The aggregate is a SQL sum, so it is independent of the visible page.
   */
  @Test
  void theAttemptLedgerIsPagedAndAggregatesComeFromSumsNotEntities() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("big");
    RunStepUsage only =
        RunStepUsage.builder()
            .id("p1")
            .runId("big")
            .attemptStatus("SUCCESS")
            .provider("anthropic")
            .model("claude-sonnet-5")
            .inputTokens(10)
            .outputTokens(2)
            .costUsd(1d)
            .createdAt(Instant.parse("2026-07-19T00:00:00Z"))
            .build();
    when(repository.findByRunIdOrderByCreatedAtDesc(eq("big"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(only)));
    when(repository.countByRunId("big")).thenReturn(5_000L);
    when(repository.countUnknownCostAttemptsByRunId("big")).thenReturn(0L);
    when(repository.sumKnownCostByRunId("big")).thenReturn(123.75d);
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 123.75d));

    service.enrich(body, run, true, 0, 1);

    assertThat((List<?>) body.get("stepCalls")).hasSize(1);
    assertThat(body)
        .containsEntry("stepCallsTotal", 5_000L)
        .containsEntry("stepCallsHasMore", true)
        .containsEntry("stepCallsSize", 1)
        // The full-ledger sum, not the page's sum.
        .containsEntry("knownCostUsdLowerBound", 123.75d)
        .containsEntry("reconciled", true);
    // The unbounded read must not be used by this surface at all.
    verify(repository, never()).findByRunIdOrderByCreatedAtAsc("big");
  }

  /**
   * RR-1552. The per-attempt array carries orgId/userId/keySource/provider/model and per-attempt
   * spend. cost/CLAUDE.md restricts that to own-org ORG_ADMIN and SUPER_ADMIN; the run read guard
   * admits far more. Aggregates stay visible so the cost card still works for everyone.
   */
  @Test
  void ordinaryReadersGetAggregatesButNotThePerAttemptLedger() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("r6");
    stubLedger(repository, "r6", List.of(), 0L, 2d);
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 2d));

    service.enrich(body, run, false, 0, SIZE);

    assertThat(body)
        // Empty, not null: the IDE maps over this array without a null guard. The flag plus
        // stepCallsTotal are what tell a client the ledger was withheld rather than absent.
        .containsEntry("stepCalls", List.of())
        .containsEntry("stepCallsIncluded", false)
        .containsEntry("stepCallsRestrictedReason", "ORG_ADMIN_OR_SUPER_ADMIN_REQUIRED")
        .containsEntry("knownCostUsdLowerBound", 2d);
    verify(repository, never()).findByRunIdOrderByCreatedAtDesc(eq("r6"), any(Pageable.class));
  }

  @Test
  void billingAdminOrgDeveloperAndCrossOrgViewerAreDeniedAttemptDetail() {
    PipelineRun run = new PipelineRun();
    run.setRunId("r7");
    run.setOrgId("org-a");
    run.setCreatedByUserId("someone-else");

    assertThat(
            RunCostEvidenceService.canViewPhysicalAttemptDetail(
                principal("USER", "org-a", "BILLING_ADMIN"), run))
        .isFalse();
    assertThat(
            RunCostEvidenceService.canViewPhysicalAttemptDetail(
                principal("USER", "org-a", "DEVELOPER"), run))
        .isFalse();
    // Cross-org project viewer — admitted by the run access guard, denied here.
    assertThat(
            RunCostEvidenceService.canViewPhysicalAttemptDetail(
                principal("USER", "org-b", "ORG_ADMIN"), run))
        .isFalse();
    assertThat(RunCostEvidenceService.canViewPhysicalAttemptDetail(null, run)).isFalse();
  }

  @Test
  void owningOrgAdminAndSuperAdminGetTheFullLedger() {
    PipelineRun run = new PipelineRun();
    run.setRunId("r8");
    run.setOrgId("org-a");

    assertThat(
            RunCostEvidenceService.canViewPhysicalAttemptDetail(
                principal("USER", "org-a", "ORG_ADMIN"), run))
        .isTrue();
    assertThat(
            RunCostEvidenceService.canViewPhysicalAttemptDetail(
                principal("SUPER_ADMIN", null, null), run))
        .isTrue();
  }

  @Test
  void anOrglessRunExposesAttemptDetailOnlyToItsCreator() {
    PipelineRun run = new PipelineRun();
    run.setRunId("r9");
    run.setCreatedByUserId("u1");

    assertThat(
            RunCostEvidenceService.canViewPhysicalAttemptDetail(principal("USER", null, null), run))
        .isTrue();

    run.setCreatedByUserId("someone-else");
    assertThat(
            RunCostEvidenceService.canViewPhysicalAttemptDetail(principal("USER", null, null), run))
        .isFalse();
  }

  private static org.assertj.core.data.Offset<Double> within(double value) {
    return org.assertj.core.data.Offset.offset(value);
  }
}
