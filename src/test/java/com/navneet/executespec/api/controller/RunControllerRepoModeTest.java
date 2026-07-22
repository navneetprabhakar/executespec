package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** RR-1671 — repoMode is parsed to the typed enum at the controller boundary. */
class RunControllerRepoModeTest {

  @Test
  void acceptsCanonicalModesCaseInsensitively() {
    assertThat(RunController.parseRepoMode("new")).isEqualTo("NEW");
    assertThat(RunController.parseRepoMode("EXISTING")).isEqualTo("EXISTING");
    assertThat(RunController.parseRepoMode(" none ")).isEqualTo("NONE");
  }

  @Test
  void rejectsUnknownModeWith400() {
    assertThatThrownBy(() -> RunController.parseRepoMode("DELETE_EVERYTHING"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }

  @Test
  void rejectsBlankModeWith400() {
    assertThatThrownBy(() -> RunController.parseRepoMode("  "))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
    assertThatThrownBy(() -> RunController.parseRepoMode(null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }
}
