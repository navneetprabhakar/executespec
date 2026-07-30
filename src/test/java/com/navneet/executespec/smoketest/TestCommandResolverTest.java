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

  @Test
  void resolvesPythonAndFrontendSuitesInAFullStackWorkspace(@TempDir Path workspace)
      throws Exception {
    Files.createDirectories(workspace.resolve("backend/tests"));
    Files.writeString(workspace.resolve("backend/requirements.txt"), "pytest\n");
    Files.writeString(workspace.resolve("backend/tests/test_api.py"), "def test_ok(): pass\n");
    Files.createDirectories(workspace.resolve("frontend"));
    Files.writeString(
        workspace.resolve("frontend/package.json"),
        "{\"scripts\":{\"test\":\"vitest run\"},\"devDependencies\":{\"vitest\":\"latest\"}}");

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.MULTI);
    assertThat(command.command())
        .contains("cd backend && python3 -m pytest -q")
        .contains("cd frontend && npm test --silent");
  }

  @Test
  void prefersMavenWrapperWhenPresent(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("pom.xml"), "<project/>");
    Files.writeString(workspace.resolve("mvnw"), "#!/bin/sh\n");

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.command()).isEqualTo("./mvnw -q test");
    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.SUREFIRE);
  }

  @Test
  void fallsBackToSystemMavenWithoutWrapper(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("pom.xml"), "<project/>");

    assertThat(new TestCommandResolver().resolve(workspace).orElseThrow().command())
        .isEqualTo("mvn -q test");
  }
}
