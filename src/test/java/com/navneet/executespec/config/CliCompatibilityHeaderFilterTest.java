package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CliCompatibilityHeaderFilterTest {

  @Test
  void apiResponseAdvertisesServerOwnedCompatibilityFloor() throws Exception {
    CliCompatibilityHeaderFilter filter = new CliCompatibilityHeaderFilter("1.0.0");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
    request.addHeader("X-ES-CLI-Version", "0.9.9");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getHeader(CliCompatibilityHeaderFilter.MINIMUM_VERSION_HEADER))
        .isEqualTo("1.0.0");
  }

  @Test
  void headerExistsBeforeDownstreamAuthenticationOrStreamingDispatch() throws Exception {
    CliCompatibilityHeaderFilter filter = new CliCompatibilityHeaderFilter("1.0.0");
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/agent/sessions/id/turns/stream");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (ignoredRequest, downstreamResponse) -> {
          assertThat(
                  ((MockHttpServletResponse) downstreamResponse)
                      .getHeader(CliCompatibilityHeaderFilter.MINIMUM_VERSION_HEADER))
              .isEqualTo("1.0.0");
          ((MockHttpServletResponse) downstreamResponse).setStatus(401);
        });

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void nonApiResponsesDoNotAdvertiseCliProtocolContract() throws Exception {
    CliCompatibilityHeaderFilter filter = new CliCompatibilityHeaderFilter("1.0.0");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        new MockHttpServletRequest("GET", "/assets/index.js"), response, new MockFilterChain());

    assertThat(response.getHeader(CliCompatibilityHeaderFilter.MINIMUM_VERSION_HEADER)).isNull();
  }

  @Test
  void malformedConfiguredFloorFailsClosed() {
    assertThatThrownBy(() -> new CliCompatibilityHeaderFilter("latest"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("major.minor.patch");
    assertThatThrownBy(() -> new CliCompatibilityHeaderFilter(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("major.minor.patch");
  }
}
