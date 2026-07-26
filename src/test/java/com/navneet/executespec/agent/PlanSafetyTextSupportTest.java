package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlanSafetyTextSupportTest {

  @Test
  void inlineCodeSpacingPreservesShellHiddenPathArguments() {
    String commands = "python -m venv .venv && source .venv/bin/activate && python -m app . main";

    assertThat(PlanSafetyTextSupport.normalizeInlineCode(commands))
        .isEqualTo("python -m venv .venv && source .venv/bin/activate && python -m app.main");
  }

  @Test
  void inlineCodeSpacingStillRepairsMemberAccessAroundTheDot() {
    assertThat(PlanSafetyTextSupport.normalizeInlineCode("app. main Base . metadata"))
        .isEqualTo("app.main Base.metadata");
  }
}
