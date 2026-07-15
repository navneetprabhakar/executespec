package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderHandoffDossierTest {

  @TempDir Path workspace;

  @Test
  void writesAndReloadsActualRouteAndFailureContext() {
    ProviderHandoffDossier.write(
        workspace,
        "run-1",
        "T1",
        "anthropic",
        "claude-sonnet",
        "deepseek",
        "deepseek-v4",
        "CHANGE_PROVIDER",
        "build failed on the previous route",
        "missing import");

    var dossier = ProviderHandoffDossier.read(workspace, "run-1", "T1").orElseThrow();
    assertThat(dossier.path("fromProvider").asText()).isEqualTo("anthropic");
    assertThat(dossier.path("toModel").asText()).isEqualTo("deepseek-v4");
    assertThat(ProviderHandoffDossier.recoveryNote(dossier))
        .contains("CHANGE_PROVIDER", "Do not repeat the failed approach blindly");
  }

  @Test
  void refusesDossierFromAnotherTask() {
    ProviderHandoffDossier.write(
        workspace, "run-1", "T1", "openai", "gpt", "gemini", "flash", "STEP_UP_MODEL", "", "");

    assertThat(ProviderHandoffDossier.read(workspace, "run-1", "T2")).isEmpty();
  }
}
