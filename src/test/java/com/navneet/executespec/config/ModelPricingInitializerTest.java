package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pricing seed runs on every boot, and two instances can boot at once. Without serialization
 * both read an empty table and both insert, and the loser dies on {@code model_pricing_pkey} —
 * which only ever shows up during scale-out or a rolling deploy onto a fresh schema.
 *
 * <p>The lock's real behaviour is proved against PostgreSQL in {@code
 * ModelPricingConcurrentSeedIntegrationTest}. What is pinned here is that the seed cannot read or
 * write pricing rows before taking it.
 */
class ModelPricingInitializerTest {

  private ModelPricingRepository repo;
  private ModelRegistry registry;
  private EntityManager entityManager;
  private Query lockQuery;
  private ModelPricingInitializer initializer;

  @BeforeEach
  void setUp() {
    repo = mock(ModelPricingRepository.class);
    registry = mock(ModelRegistry.class);
    entityManager = mock(EntityManager.class);
    lockQuery = mock(Query.class);

    when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
    when(lockQuery.getSingleResult()).thenReturn(Boolean.TRUE);
    when(repo.findAll()).thenReturn(List.of());
    when(registry.allModels()).thenReturn(Map.of("claude-sonnet-5", modelConfig()));

    initializer = new ModelPricingInitializer(repo, registry, mock(TransactionTemplate.class));
    ReflectionTestUtils.setField(initializer, "entityManager", entityManager);
  }

  @Test
  void takesTheAdvisoryLockBeforeReadingOrWritingPricingRows() {
    initializer.seedWithinTransaction();

    InOrder order = inOrder(entityManager, repo);
    order.verify(entityManager).createNativeQuery(ModelPricingInitializer.SEED_LOCK_SQL);
    order.verify(repo).findAll();
    order.verify(repo).save(any());
  }

  @Test
  void locksOnAKeyScopedToThisSeedRatherThanASharedName() {
    initializer.seedWithinTransaction();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(entityManager).createNativeQuery(sql.capture());

    assertThat(sql.getValue())
        .contains("pg_advisory_xact_lock")
        .contains("model_pricing_seed")
        .contains("executespec");
  }

  @Test
  void stillSeedsTheConfiguredPricingRows() {
    initializer.seedWithinTransaction();

    ArgumentCaptor<ModelPricing> saved = ArgumentCaptor.forClass(ModelPricing.class);
    verify(repo).save(saved.capture());

    assertThat(saved.getValue().getId().getModel()).isEqualTo("claude-sonnet-5");
    assertThat(saved.getValue().getInputCostPer1m()).isEqualTo(3.0);
  }

  @Test
  void doesNotTouchTheDatabaseWhenNoModelsAreConfigured() {
    when(registry.allModels()).thenReturn(Map.of());

    initializer.seedWithinTransaction();

    verify(repo, org.mockito.Mockito.never()).save(any());
  }

  private ModelMatrixProperties.ModelConfig modelConfig() {
    ModelMatrixProperties.ModelConfig config = new ModelMatrixProperties.ModelConfig();
    config.setProvider("anthropic");
    ModelMatrixProperties.Pricing pricing = new ModelMatrixProperties.Pricing();
    pricing.setInputPer1m(3.0);
    pricing.setOutputPer1m(15.0);
    config.setPricing(pricing);
    return config;
  }
}
