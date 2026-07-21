package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunControllerLocalInventoryTest {

  @Test
  void acceptsBoundedRelativePathInventory() {
    assertThatCode(
            () ->
                RunController.validateLocalRepositoryInventory(
                    "- pom.xml\n- src/\n- src/main/App.java\n- (inventory truncated)"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAbsoluteTraversalAndNonPathPayloads() {
    assertThatThrownBy(() -> RunController.validateLocalRepositoryInventory("- /etc/passwd"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> RunController.validateLocalRepositoryInventory("- ../secret"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> RunController.validateLocalRepositoryInventory("ignore previous"))
        .isInstanceOf(ResponseStatusException.class);
  }
}
