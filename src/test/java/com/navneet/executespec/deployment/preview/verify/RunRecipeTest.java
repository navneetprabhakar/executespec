package com.navneet.executespec.deployment.preview.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** RL-2026-05-066 — pins {@link RunRecipe} parsing + safety clamping. */
class RunRecipeTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void parses_explicit_fields() throws Exception {
    RunRecipe r =
        RunRecipe.fromJson(
            json.readTree("{\"port\":8080,\"healthPath\":\"/h\",\"startTimeoutSec\":45}"));
    assertThat(r.port()).isEqualTo(8080);
    assertThat(r.healthPath()).isEqualTo("/h");
    assertThat(r.startTimeoutSec()).isEqualTo(45);
  }

  @Test
  void applies_defaults_for_missing_fields() throws Exception {
    RunRecipe r = RunRecipe.fromJson(json.readTree("{}"));
    assertThat(r.port()).isEqualTo(RunRecipe.DEFAULT_PORT);
    assertThat(r.healthPath()).isEqualTo(RunRecipe.DEFAULT_HEALTH_PATH);
    assertThat(r.startTimeoutSec()).isEqualTo(RunRecipe.DEFAULT_START_TIMEOUT_SEC);
  }

  @Test
  void clamps_out_of_range_values() {
    assertThat(new RunRecipe(0, "", 0).port()).isEqualTo(RunRecipe.DEFAULT_PORT);
    assertThat(new RunRecipe(0, "", 0).healthPath()).isEqualTo(RunRecipe.DEFAULT_HEALTH_PATH);
    assertThat(new RunRecipe(0, "", 0).startTimeoutSec())
        .isEqualTo(RunRecipe.DEFAULT_START_TIMEOUT_SEC);
    // a recipe can never ask the boot to outlast the security ceiling
    assertThat(new RunRecipe(70000, "/", 9999).port()).isEqualTo(RunRecipe.DEFAULT_PORT);
    assertThat(new RunRecipe(70000, "/", 9999).startTimeoutSec())
        .isEqualTo(RunRecipe.MAX_START_TIMEOUT_SEC);
  }

  @Test
  void null_node_is_null() {
    assertThat(RunRecipe.fromJson(null)).isNull();
  }
}
