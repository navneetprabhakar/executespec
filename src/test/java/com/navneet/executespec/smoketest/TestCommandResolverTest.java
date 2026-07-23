package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCommandResolverTest {

  @Test
  void prefersGeneratedPythonVirtualenv(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("pyproject.toml"), "[project]\nname='sample'\n");
    Files.createDirectories(workspace.resolve("venv/bin"));
    Files.writeString(workspace.resolve("venv/bin/python"), "");

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.command()).isEqualTo("venv/bin/python -m pytest -q");
    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.PYTEST);
  }

  @Test
  void detectsVitestFromPackageMetadata(@TempDir Path workspace) throws Exception {
    Files.writeString(
        workspace.resolve("package.json"),
        "{\"scripts\":{\"test\":\"vitest run\"},\"devDependencies\":{\"vitest\":\"latest\"}}");

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.VITEST);
  }
}
