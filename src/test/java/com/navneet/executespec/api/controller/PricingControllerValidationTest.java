package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.api.dto.PricingUpdateRequest;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingId;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/** RR-962 — pricing upsert validates finite non-negative rates and preserves the cache rate. */
class PricingControllerValidationTest {

  private final ModelPricingRepository pricingRepo = mock(ModelPricingRepository.class);
  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
  private final PricingController controller =
      new PricingController(pricingRepo, guard, modelRegistry);

  private void stubRowMapping() {
    when(modelRegistry.allModels()).thenReturn(Map.of());
    when(modelRegistry.displayLabelFor(any())).thenReturn(null);
    when(pricingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void rejectsNegativeInputRate() {
    assertThatThrownBy(
            () ->
                controller.upsert(
                    "anthropic", "haiku", new PricingUpdateRequest(-1.0, 2.0, null, null), null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }

  @Test
  void rejectsMissingRequiredRate() {
    assertThatThrownBy(
            () ->
                controller.upsert(
                    "anthropic", "haiku", new PricingUpdateRequest(null, 2.0, null, null), null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }

  @Test
  void rejectsNonFiniteRate() {
    assertThatThrownBy(
            () ->
                controller.upsert(
                    "anthropic",
                    "haiku",
                    new PricingUpdateRequest(Double.POSITIVE_INFINITY, 2.0, null, null),
                    null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }

  @Test
  void preservesExistingCacheRateWhenOmitted() {
    stubRowMapping();
    when(pricingRepo.findByIdProviderAndIdModel("anthropic", "haiku"))
        .thenReturn(
            Optional.of(
                ModelPricing.builder()
                    .id(new ModelPricingId("anthropic", "haiku"))
                    .inputCostPer1m(1.0)
                    .outputCostPer1m(2.0)
                    .cachedInputCostPer1m(0.25)
                    .build()));

    controller.upsert("anthropic", "haiku", new PricingUpdateRequest(3.0, 4.0, null, null), null);

    ArgumentCaptor<ModelPricing> captor = ArgumentCaptor.forClass(ModelPricing.class);
    org.mockito.Mockito.verify(pricingRepo).save(captor.capture());
    assertThat(captor.getValue().getCachedInputCostPer1m()).isEqualTo(0.25);
    assertThat(captor.getValue().getInputCostPer1m()).isEqualTo(3.0);
  }

  @Test
  void updatesCacheRateWhenProvided() {
    stubRowMapping();
    when(pricingRepo.findByIdProviderAndIdModel("anthropic", "haiku")).thenReturn(Optional.empty());

    controller.upsert("anthropic", "haiku", new PricingUpdateRequest(3.0, 4.0, 0.5, null), null);

    ArgumentCaptor<ModelPricing> captor = ArgumentCaptor.forClass(ModelPricing.class);
    org.mockito.Mockito.verify(pricingRepo).save(captor.capture());
    assertThat(captor.getValue().getCachedInputCostPer1m()).isEqualTo(0.5);
  }
}
