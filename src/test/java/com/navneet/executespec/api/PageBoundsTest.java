package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PageBoundsTest {

  @Test
  void acceptsBoundedPage() {
    var request = PageBounds.request(2, 100, 100);
    assertThat(request.getPageNumber()).isEqualTo(2);
    assertThat(request.getPageSize()).isEqualTo(100);
    assertThat(request.getOffset()).isEqualTo(200);
  }

  @Test
  void rejectsNegativePageAndInvalidSizes() {
    assertBadRequest(() -> PageBounds.request(-1, 20, 100));
    assertBadRequest(() -> PageBounds.request(0, 0, 100));
    assertBadRequest(() -> PageBounds.request(0, 101, 100));
  }

  @Test
  void rejectsIntegerOffsetOverflow() {
    assertBadRequest(() -> PageBounds.request(Integer.MAX_VALUE, 2, 100));
  }

  private static void assertBadRequest(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }
}
