package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * RR-1551 — the historical k-NN cost estimate sets {@code creditsCharged} and {@code hardCapUsd} on
 * a customer's run, so it must be derived exclusively from that customer's own org history. These
 * tests pin the tenant boundary at the service seam.
 */
class CostHistoryServiceTenantScopeTest {

  private final RunCostHistoryRepository historyRepo = mock(RunCostHistoryRepository.class);
  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final CostHistoryService service = new CostHistoryService(historyRepo, runRepo);

  private static List<BigDecimal> costs(double value, int count) {
    return Stream.generate(() -> BigDecimal.valueOf(value))
        .limit(count)
        .collect(Collectors.toList());
  }

  /** RR-1551 — both queries must be issued with the caller's orgId, never unscoped. */
  @Test
  void strictQueryIsScopedToTheSubmittingOrg() {
    when(historyRepo.findSimilarCosts(
            eq("org-a"), eq("SMALL"), eq("ANTHROPIC"), anyInt(), anyInt(), any(Pageable.class)))
        .thenReturn(costs(2.00, CostHistoryService.MIN_SAMPLES));

    Optional<RunCostTracker.Estimate> est =
        service.estimateFromHistory("org-a", "SMALL", "ANTHROPIC", 1, 3);

    assertThat(est).isPresent();
    assertThat(est.get().lowUsd()).isEqualTo(2.00);
    // The loose fallback must not have run — the strict query already had enough same-org samples.
    verify(historyRepo, never()).findSimilarCostsLoose(any(), any(), any(), any(Pageable.class));
  }

  /**
   * RR-1551 — two orgs with disjoint cost profiles get independent estimates; a foreign org's
   * expensive runs cannot move this org's credit charge or hard cap.
   */
  @Test
  void foreignOrgCostsDoNotInfluenceThisOrgsEstimate() {
    when(historyRepo.findSimilarCosts(
            eq("org-cheap"), eq("SMALL"), eq("DEEPSEEK"), anyInt(), anyInt(), any(Pageable.class)))
        .thenReturn(costs(0.10, CostHistoryService.MIN_SAMPLES));
    when(historyRepo.findSimilarCosts(
            eq("org-rich"), eq("SMALL"), eq("DEEPSEEK"), anyInt(), anyInt(), any(Pageable.class)))
        .thenReturn(costs(48.00, CostHistoryService.MIN_SAMPLES));

    RunCostTracker.Estimate cheap =
        service.estimateFromHistory("org-cheap", "SMALL", "DEEPSEEK", 1, 2).orElseThrow();
    RunCostTracker.Estimate rich =
        service.estimateFromHistory("org-rich", "SMALL", "DEEPSEEK", 1, 2).orElseThrow();

    assertThat(cheap.highUsd()).isEqualTo(0.10);
    assertThat(cheap.credits()).isEqualTo(1);
    assertThat(cheap.hardCapUsd()).isEqualTo(5.0);
    // The rich org prices independently; the cheap org's charge is unchanged by its existence.
    assertThat(rich.credits()).isGreaterThan(cheap.credits());
  }

  /** RR-1551 — an org below MIN_SAMPLES falls through to the formula, not to a global pool. */
  @Test
  void orgBelowMinSamplesFallsThroughToFormula() {
    when(historyRepo.findSimilarCosts(
            eq("org-new"), any(), any(), anyInt(), anyInt(), any(Pageable.class)))
        .thenReturn(costs(3.00, 2));
    when(historyRepo.findSimilarCostsLoose(eq("org-new"), any(), any(), any(Pageable.class)))
        .thenReturn(costs(3.00, CostHistoryService.MIN_SAMPLES - 1));

    assertThat(service.estimateFromHistory("org-new", "SMALL", "OPENAI", 1, 1)).isEmpty();
  }

  /** RR-1551 — the loose fallback relaxes shape, never the tenant boundary. */
  @Test
  void looseFallbackStillCarriesTheOrgId() {
    when(historyRepo.findSimilarCosts(
            eq("org-b"), any(), any(), anyInt(), anyInt(), any(Pageable.class)))
        .thenReturn(new ArrayList<>());
    when(historyRepo.findSimilarCostsLoose(
            eq("org-b"), eq("LARGE"), eq("OPENAI"), any(Pageable.class)))
        .thenReturn(costs(1.00, CostHistoryService.MIN_SAMPLES));

    assertThat(service.estimateFromHistory("org-b", "LARGE", "OPENAI", 4, 20)).isPresent();
    verify(historyRepo)
        .findSimilarCostsLoose(eq("org-b"), eq("LARGE"), eq("OPENAI"), any(Pageable.class));
  }

  /** RR-1551 — an unattributable run must not be priced from anyone's history. */
  @Test
  void blankOrgIdSkipsHistoryEntirely() {
    assertThat(service.estimateFromHistory(null, "SMALL", "ANTHROPIC", 1, 1)).isEmpty();
    assertThat(service.estimateFromHistory("  ", "SMALL", "ANTHROPIC", 1, 1)).isEmpty();

    verify(historyRepo, never())
        .findSimilarCosts(any(), any(), any(), anyInt(), anyInt(), any(Pageable.class));
    verify(historyRepo, never()).findSimilarCostsLoose(any(), any(), any(), any(Pageable.class));
  }
}
