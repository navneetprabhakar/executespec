package com.navneet.executespec.continuationfixture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class ContinuationFixtureControllerTest {

  @Test
  void endpointIsLocalDevOnlyAndSuperAdminOnly() throws Exception {
    Class<ContinuationFixtureController> type = ContinuationFixtureController.class;
    assertThat(type.getAnnotation(Profile.class).value()).containsExactlyInAnyOrder("local", "dev");
    assertThat(type.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('SUPER_ADMIN')");
    assertThat(type.getAnnotation(RequestMapping.class).value())
        .containsExactly("/api/v2/test/continuation-fixtures");
    Method method =
        type.getDeclaredMethod(
            "start",
            com.navneet.executespec.auth.security.AppUserDetails.class,
            ContinuationFixtureController.StartRequest.class);
    assertThat(method.getAnnotation(PostMapping.class)).isNotNull();
  }

  @Test
  void scenarioInventoryIsClosedAndExplicit() {
    assertThat(ContinuationFixtureScenario.values())
        .containsExactly(
            ContinuationFixtureScenario.STEP_UP_QUALITY,
            ContinuationFixtureScenario.STEP_DOWN_SIMPLE,
            ContinuationFixtureScenario.CHANGE_PROVIDER_BREAKDOWN,
            ContinuationFixtureScenario.BLOCK_BENCHMARK,
            ContinuationFixtureScenario.BLOCK_CONFIRMATION,
            ContinuationFixtureScenario.ROUTE_PINNED);
  }
}
