package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.router.ProviderReadinessService;
import com.navneet.executespec.router.ProviderReadinessService.ProviderReadiness;
import com.navneet.executespec.router.ProviderReadinessService.State;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProviderReadinessControllerTest {

  @Test
  void resolvesReadinessForTheAuthenticatedAccountWithoutExposingKeys() {
    ProviderReadinessService service = mock(ProviderReadinessService.class);
    when(service.resolve("u1"))
        .thenReturn(
            List.of(
                new ProviderReadiness(
                    "DEEPSEEK",
                    State.AVAILABLE_ORG_KEY,
                    true,
                    "OWN_KEYS",
                    "Using the organization provider key.")));
    var controller = new ProviderReadinessController(service);
    var principal =
        new AppUserDetails(
            AppUser.builder().id("u1").email("user@example.test").status("ACTIVE").build(), null);

    var response = controller.readiness(principal);

    assertThat(response.schemaVersion()).isEqualTo(1);
    assertThat(response.providers())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.state()).isEqualTo(State.AVAILABLE_ORG_KEY);
              assertThat(item.reason()).doesNotContain("secret");
            });
  }

  @Test
  void rejectsMissingAuthenticationInsteadOfFallingBackToSystemReadiness() {
    var controller = new ProviderReadinessController(mock(ProviderReadinessService.class));

    assertThatThrownBy(() -> controller.readiness(null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("401");
  }
}
