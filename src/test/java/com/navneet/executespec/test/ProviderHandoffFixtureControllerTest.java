package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.Set;

import com.navneet.executespec.agent.ProviderDispatchAuthorization;
import com.navneet.executespec.benchmark.ProviderHandoffFixtureHarness;
import com.navneet.executespec.engine.WorkerAgent;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

class ProviderHandoffFixtureControllerTest {

  @Test
  void endpointIsOperatorOnlyAndProfileRestricted() throws Exception {
    Class<ProviderHandoffFixtureController> type = ProviderHandoffFixtureController.class;
    assertThat(type.getAnnotation(Profile.class).value())
        .containsExactlyInAnyOrder("local", "dev", "benchmark");
    assertThat(type.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('SUPER_ADMIN')");
    Method method = type.getDeclaredMethod("run", ProviderHandoffFixtureController.Request.class);
    assertThat(method.getAnnotation(PostMapping.class).value())
        .containsExactly("/provider-handoff");
  }

  @Test
  void requestCarriesNoImplicitRouteDefaults() {
    var request =
        new ProviderHandoffFixtureController.Request(
            "repository-localization-v1",
            "v1",
            "deepseek",
            "native",
            "r1",
            "openrouter",
            "fallback",
            "r2",
            Set.of("deepseek", "openrouter"),
            ProviderHandoffFixtureHarness.HandoffTrigger.ON_PROVIDER_ERROR);
    assertThat(request.fixtureId()).isEqualTo("repository-localization-v1");
    assertThat(request.allowedProviders()).containsExactlyInAnyOrder("deepseek", "openrouter");
    assertThat(request.handoffTrigger())
        .isEqualTo(ProviderHandoffFixtureHarness.HandoffTrigger.ON_PROVIDER_ERROR);
    assertThat(
            new ProviderHandoffFixtureController(
                mock(WorkerAgent.class), mock(ProviderDispatchAuthorization.class)))
        .isNotNull();
  }
}
