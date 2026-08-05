package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCommandResolverTest {

  @Test
  void nodeBuiltInTestRunnerIsNotMisclassifiedAsJest(@TempDir Path workspace) throws Exception {
    Files.writeString(
        workspace.resolve("package.json"), "{\"scripts\":{\"test\":\"node --test\"}}\n");

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.command()).isEqualTo("npm test --silent");
    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.NODE_TEST);
  }

  @Test
  void unrelatedNodeTestTextOutsideTheTestScriptDoesNotChangeFramework(@TempDir Path workspace)
      throws Exception {
    Files.writeString(
        workspace.resolve("package.json"),
        "{\"description\":\"example: node --test\",\"scripts\":{\"test\":\"jest\"}}\n");

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.JEST);
  }

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
    try (Git git = Git.init().setDirectory(workspace.toFile()).call()) {
      git.add().addFilepattern(".").call();
      git.commit().setMessage("baseline").setAuthor("test", "test@example.com").call();
    }
    Files.writeString(
        workspace.resolve("backend/tests/test_changed.py"), "def test_changed(): pass\n");
    Files.writeString(workspace.resolve("frontend/changed.ts"), "export const changed = true\n");

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
    workspace.resolve("mvnw").toFile().setExecutable(true);

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.command()).isEqualTo("./mvnw -q test");
    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.SUREFIRE);
  }

  @Test
  void fallsBackToSystemMavenWhenWrapperIsNotExecutable(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("pom.xml"), "<project/>");
    Files.writeString(workspace.resolve("mvnw"), "#!/bin/sh\n");
    workspace.resolve("mvnw").toFile().setExecutable(false);

    assertThat(new TestCommandResolver().resolve(workspace).orElseThrow().command())
        .isEqualTo("mvn -q test");
  }

  @Test
  void fallsBackToSystemMavenWithoutWrapper(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("pom.xml"), "<project/>");

    assertThat(new TestCommandResolver().resolve(workspace).orElseThrow().command())
        .isEqualTo("mvn -q test");
  }

  @Test
  void nestedSingleRepoKeepsMavenCommandAndSurefireAttribution(@TempDir Path workspace)
      throws Exception {
    Path repository = workspace.resolve("repos/orders-service");
    Files.createDirectories(repository.resolve("src/test/python"));
    Files.writeString(repository.resolve("pom.xml"), "<project/>");
    // This nested helper made the old resolver classify the grouping `repos/` directory as pytest.
    Files.writeString(
        repository.resolve("src/test/python/test_fixture.py"), "def test_ok(): pass\n");

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.command()).isEqualTo("(cd repos/orders-service && mvn -q test)");
    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.SUREFIRE);
    assertThat(command.source()).isEqualTo("maven@repos/orders-service");
  }

  @Test
  void helmShapedRepoTestsOnlyChangedNestedMavenModule(@TempDir Path workspace) throws Exception {
    Path helm = workspace.resolve("repos/helm");
    Files.createDirectories(helm.resolve("backend-java/src/test/java"));
    Files.writeString(helm.resolve("backend-java/pom.xml"), "<project/>\n");
    Files.createDirectories(helm.resolve("crawler-node/src"));
    Files.writeString(
        helm.resolve("crawler-node/package.json"), "{\"scripts\":{\"test\":\"jest\"}}");
    Files.createDirectories(helm.resolve("frontend-react/src"));
    Files.writeString(
        helm.resolve("frontend-react/package.json"), "{\"scripts\":{\"test\":\"vitest\"}}");
    try (Git git = Git.init().setDirectory(helm.toFile()).call()) {
      git.add().addFilepattern(".").call();
      git.commit().setMessage("baseline").setAuthor("test", "test@example.com").call();
    }
    Files.writeString(
        helm.resolve("backend-java/src/test/java/ChangedTest.java"),
        "final class ChangedTest {}\n");

    var command = new TestCommandResolver().resolve(workspace).orElseThrow();

    assertThat(command.command())
        .isEqualTo("(cd repos/helm/backend-java && mvn -q test)")
        .doesNotContain("crawler-node", "frontend-react");
    assertThat(command.framework()).isEqualTo(TestCommandResolver.Framework.SUREFIRE);
    assertThat(command.source()).isEqualTo("maven@repos/helm/backend-java");
  }
}
