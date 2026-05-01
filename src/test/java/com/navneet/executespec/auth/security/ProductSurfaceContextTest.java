package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ProductSurfaceContextTest {

  @AfterEach
  void clearContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void returnsDefaultWhenNoRequestBound() {
    RequestContextHolder.resetRequestAttributes();
    assertThat(ProductSurfaceContext.current()).isEqualTo(ProductSurfaceFilter.DEFAULT_SURFACE);
  }

  @Test
  void readsCurrentSurfaceFromRequestAttribute() {
    HttpServletRequest req = newRequestWith("vibe");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    assertThat(ProductSurfaceContext.current()).isEqualTo("vibe");
  }

  @Test
  void isVibePredicateMatches() {
    HttpServletRequest req = newRequestWith("vibe");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    assertThat(ProductSurfaceContext.isVibe()).isTrue();
    assertThat(ProductSurfaceContext.isExecutespec()).isFalse();
  }

  @Test
  void isExecutespecPredicateMatches() {
    HttpServletRequest req = newRequestWith("executespec");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    assertThat(ProductSurfaceContext.isExecutespec()).isTrue();
    assertThat(ProductSurfaceContext.isVibe()).isFalse();
  }

  @Test
  void missingAttributeFallsBackToDefault() {
    HttpServletRequest req = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    assertThat(ProductSurfaceContext.current()).isEqualTo(ProductSurfaceFilter.DEFAULT_SURFACE);
  }

  private static HttpServletRequest newRequestWith(String surface) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setAttribute(ProductSurfaceFilter.ATTR_CURRENT_PRODUCT, surface);
    return req;
  }
}
