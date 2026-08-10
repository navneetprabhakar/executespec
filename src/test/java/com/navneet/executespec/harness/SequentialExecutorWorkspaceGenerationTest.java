package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SequentialExecutorWorkspaceGenerationTest {

  @Test
  void localHandsBuildCannotBypassGenerationProof() {
    assertThatThrownBy(() -> BuildTaskGenerationCoordinator.requireServerOwnedWorkspace(true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("LOCAL_HANDS_BUILD_GENERATION_PROTOCOL_REQUIRED");
  }

  @Test
  void serverOwnedBuildKeepsTheGenerationProtocolPathOpen() {
    assertThatCode(() -> BuildTaskGenerationCoordinator.requireServerOwnedWorkspace(false))
        .doesNotThrowAnyException();
  }
}
