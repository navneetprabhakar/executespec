package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.anonrun.AnonymousRunService;

import org.junit.jupiter.api.Test;

class AnonymousRunControllerClientIpTest {

  @Test
  void callerForwardingHeaderCannotChooseAnonymousRateLimitIdentity() {
    AnonymousRunService service = mock(AnonymousRunService.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("198.51.100.8");
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.99");
    when(service.submit("build a calculator", "198.51.100.8"))
        .thenReturn(new AnonymousRunService.Outcome.Rejected(429, "rate_limit", "limited"));

    var response =
        new AnonymousRunController(service)
            .submit(new AnonymousRunController.AnonymousRunRequest("build a calculator"), request);

    assertThat(response.getStatusCode().value()).isEqualTo(429);
    verify(service).submit("build a calculator", "198.51.100.8");
  }
}
