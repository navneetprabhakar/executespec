package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildCommandResolverFullStackTest {

  @Test
  void verifiesTheOnlyDirectBuildRootWhileAnotherFullStackRootIsNotCreatedYet(
      @TempDir Path workspace) throws Exception {
    Files.createDirectories(workspace.resolve("backend"));
    Files.writeString(workspace.resolve("backend/requirements.txt"), "fastapi\n");

    BuildCommand command = new BuildCommandResolver().resolve(workspace, null);

    assertThat(command.sourceDescription()).isEqualTo("inferred:child-root");
    assertThat(command.command()).isEqualTo("(cd backend && python3 -m compileall -q .)");
  }

  @Test
  void verifiesEveryDirectBuildRootInsteadOfTrustingABackendOnlyPlannerCommand(
      @TempDir Path workspace) throws Exception {
    Files.createDirectories(workspace.resolve("backend"));
    Files.writeString(workspace.resolve("backend/requirements.txt"), "fastapi\n");
    Files.createDirectories(workspace.resolve("frontend"));
    Files.writeString(
        workspace.resolve("frontend/package.json"), "{\"scripts\":{\"build\":\"vite build\"}}");

    BuildCommand command =
        new BuildCommandResolver().resolve(workspace, "python3 -m compileall -q backend/");

    assertThat(command.sourceDescription()).isEqualTo("inferred:multi-root");
    assertThat(command.command())
        .contains("cd backend && python3 -m compileall -q .")
        .contains("cd frontend && npm run build --if-present");
  }
}
