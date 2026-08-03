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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * RR-1551 — the historical k-NN cost estimate sets {@code creditsCharged} and {@code hardCapUsd} on
 * a customer's run, so it must be derived exclusively from that customer's own org and exact
 * physical route history.
 */
class CostHistoryServiceTenantScopeTest {

  private static final String ROUTE = "DEEPSEEK:deepseek-v4-pro-native";
  private static final String SHAPE = "LOW:1,MEDIUM:1,HIGH:1";
  private final RunCostHistoryRepository historyRepo = mock(RunCostHistoryRepository.class);
  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final CostHistoryService service =
      new CostHistoryService(historyRepo, runRepo, mock(RunStepUsageRepository.class));

  private static List<BigDecimal> costs(double value, int count) {
    return Stream.generate(() -> BigDecimal.valueOf(value))
        .limit(count)
        .collect(Collectors.toList());
  }

  @Test
  void strictQueryIsScopedToSubmittingOrgAndExactRoute() {
    when(historyRepo.findSimilarCosts(
            eq("org-a"),
            eq("SMALL"),
            eq("ANTHROPIC"),
            eq(ROUTE),
            anyInt(),
            anyInt(),
            eq(SHAPE),
            any(Pageable.class)))
        .thenReturn(costs(2.00, CostHistoryService.MIN_SAMPLES));

    Optional<RunCostTracker.Estimate> est =
        service.estimateFromHistory("org-a", "SMALL", "ANTHROPIC", ROUTE, 1, 3, SHAPE);

    assertThat(est).isPresent();
    assertThat(est.get().lowUsd()).isEqualTo(2.00);
  }

  @Test
  void foreignOrgCostsDoNotInfluenceThisOrgsEstimate() {
    when(historyRepo.findSimilarCosts(
            eq("org-cheap"),
            eq("SMALL"),
            eq("DEEPSEEK"),
            eq(ROUTE),
            anyInt(),
            anyInt(),
            eq(SHAPE),
            any(Pageable.class)))
        .thenReturn(costs(0.10, CostHistoryService.MIN_SAMPLES));
    when(historyRepo.findSimilarCosts(
            eq("org-rich"),
            eq("SMALL"),
            eq("DEEPSEEK"),
            eq(ROUTE),
            anyInt(),
            anyInt(),
            eq(SHAPE),
            any(Pageable.class)))
        .thenReturn(costs(48.00, CostHistoryService.MIN_SAMPLES));

    RunCostTracker.Estimate cheap =
        service
            .estimateFromHistory("org-cheap", "SMALL", "DEEPSEEK", ROUTE, 1, 2, SHAPE)
            .orElseThrow();
    RunCostTracker.Estimate rich =
        service
            .estimateFromHistory("org-rich", "SMALL", "DEEPSEEK", ROUTE, 1, 2, SHAPE)
            .orElseThrow();

    assertThat(cheap.highUsd()).isEqualTo(0.10);
    assertThat(cheap.credits()).isEqualTo(1);
    assertThat(cheap.hardCapUsd()).isEqualTo(5.0);
    assertThat(rich.credits()).isGreaterThan(cheap.credits());
  }

  @Test
  void orgBelowMinSamplesFallsThroughToFormula() {
    when(historyRepo.findSimilarCosts(
            eq("org-new"),
            any(),
            any(),
            eq(ROUTE),
            anyInt(),
            anyInt(),
            eq(SHAPE),
            any(Pageable.class)))
        .thenReturn(costs(3.00, 2));
    assertThat(service.estimateFromHistory("org-new", "SMALL", "OPENAI", ROUTE, 1, 1, SHAPE))
        .isEmpty();
  }

  @Test
  void blankOrgOrRouteSkipsHistoryEntirely() {
    assertThat(service.estimateFromHistory(null, "SMALL", "ANTHROPIC", ROUTE, 1, 1, SHAPE))
        .isEmpty();
    assertThat(service.estimateFromHistory("  ", "SMALL", "ANTHROPIC", ROUTE, 1, 1, SHAPE))
        .isEmpty();
    assertThat(service.estimateFromHistory("org-a", "SMALL", "ANTHROPIC", null, 1, 1, SHAPE))
        .isEmpty();
    assertThat(service.estimateFromHistory("org-a", "SMALL", "ANTHROPIC", ROUTE, 1, 1, null))
        .isEmpty();

    verify(historyRepo, never())
        .findSimilarCosts(
            any(), any(), any(), any(), anyInt(), anyInt(), any(), any(Pageable.class));
  }

  @Test
  void complexityDistributionIsAnExactCohortFence() {
    when(historyRepo.findSimilarCosts(
            "org-a", "SMALL", "DEEPSEEK", ROUTE, 1, 3, SHAPE, PageRequest.of(0, 100)))
        .thenReturn(costs(1.00, CostHistoryService.MIN_SAMPLES));

    assertThat(service.estimateFromHistory("org-a", "SMALL", "DEEPSEEK", ROUTE, 1, 3, SHAPE))
        .isPresent();
    assertThat(
            service.estimateFromHistory(
                "org-a", "SMALL", "DEEPSEEK", ROUTE, 1, 3, "LOW:3,MEDIUM:0,HIGH:0"))
        .isEmpty();
  }
}
