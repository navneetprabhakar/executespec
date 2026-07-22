package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** RR-237 — a run cannot be reassigned into a project owned by a different organization. */
class RunControllerReassignmentTest {

  @Test
  void rejectsCrossOrgReassignment() {
    assertThatThrownBy(() -> RunController.assertSameOrgReassignment("org-host", "org-home"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void allowsSameOrgReassignment() {
    assertThatCode(() -> RunController.assertSameOrgReassignment("org-a", "org-a"))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsWhenEitherOrgIsUnknown() {
    // Legacy rows may carry a null org on either side; the prior behavior is preserved.
    assertThatCode(() -> RunController.assertSameOrgReassignment(null, "org-a"))
        .doesNotThrowAnyException();
    assertThatCode(() -> RunController.assertSameOrgReassignment("org-a", null))
        .doesNotThrowAnyException();
  }
}
