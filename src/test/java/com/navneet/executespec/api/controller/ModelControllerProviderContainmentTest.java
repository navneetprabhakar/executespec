package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ProviderReadinessService;

import org.junit.jupiter.api.Test;

class ModelControllerProviderContainmentTest {

  @Test
  void catalogEndpointsDropProviderMetadataThatHasNoRuntimeEnum() {
    ModelMatrixProperties properties = new ModelMatrixProperties();
    var providerRows = new LinkedHashMap<String, ModelMatrixProperties.ProviderModels>();
    providerRows.put("anthropic", providerModels("known-model"));
    providerRows.put("retired", providerModels("retired-model"));
    properties.setProviders(providerRows);

    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.displayLabelFor("known-model")).thenReturn("Known model");
    when(registry.displayLabelFor("retired-model")).thenReturn("Retired model");
    var knownConfig = modelConfig("anthropic");
    var retiredConfig = modelConfig("retired");
    when(registry.allModels())
        .thenReturn(Map.of("known-model", knownConfig, "retired-model", retiredConfig));

    ModelAccessService access = mock(ModelAccessService.class);
    when(access.minTierFor("known-model")).thenReturn("PRO");
    when(access.isAllowed("user-1", "known-model")).thenReturn(true);

    ProviderReadinessService readiness = mock(ProviderReadinessService.class);
    when(readiness.resolve("user-1"))
        .thenReturn(
            java.util.List.of(
                new ProviderReadinessService.ProviderReadiness(
                    "ANTHROPIC",
                    ProviderReadinessService.State.AVAILABLE_ORG_KEY,
                    true,
                    "OWN_KEYS",
                    "available")));
    ModelController controller = new ModelController(properties, registry, access, readiness);

    var publicCatalog = controller.available().getBody();
    assertThat(publicCatalog).isNotNull();
    assertThat(publicCatalog.providers())
        .extracting(ModelController.ProviderEntry::id)
        .containsExactly("ANTHROPIC");

    AppUser user = AppUser.builder().id("user-1").email("user@example.com").build();
    var userCatalog = controller.myModels(new AppUserDetails(user, null)).getBody();
    assertThat(userCatalog).isNotNull();
    assertThat(userCatalog)
        .extracting(ModelController.UserModel::id)
        .containsExactly("known-model");
    assertThat(userCatalog.get(0).available()).isTrue();
  }

  private static ModelMatrixProperties.ProviderModels providerModels(String planning) {
    var models = new ModelMatrixProperties.ProviderModels();
    models.setPlanning(planning);
    return models;
  }

  private static ModelMatrixProperties.ModelConfig modelConfig(String provider) {
    var config = new ModelMatrixProperties.ModelConfig();
    config.setProvider(provider);
    return config;
  }
}
