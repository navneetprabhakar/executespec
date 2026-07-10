package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderDispatchAuthorizationTest {

  @Test
  void disabledTransportFailsBeforeAnyProviderDispatch() {
    var authorization = new ProviderDispatchAuthorization(false, false, "");

    assertThatThrownBy(() -> authorization.authorize("openai", "gpt-5.4", "run-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disabled");
  }

  @Test
  void productionTransportDoesNotRequireTestReservation() {
    var authorization = new ProviderDispatchAuthorization(true, false, "");

    assertThatCode(() -> authorization.authorize("anthropic", "claude-sonnet-4-6", "run-1"))
        .doesNotThrowAnyException();
  }

  @Test
  void goalLiveModeFailsClosedWithoutReservation() {
    var authorization = new ProviderDispatchAuthorization(true, true, "");

    assertThatThrownBy(() -> authorization.authorize("gemini", "gemini-3-flash", "run-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reservation-id");
  }
}
