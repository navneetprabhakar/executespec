package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildCommandResolverFullStackTest {

  @Test
  void verifiesTheOnlyDirectBuildRootWhileAnotherFullStackRootIsNotCreatedYet(
      @TempDir Path workspace) throws Exception {
    Files.createDirectories(workspace.resolve("backend"));
    Files.writeString(workspace.resolve("backend/requirements.txt"), "fastapi\n");

    BuildCommand command = new BuildCommandResolver().resolve(workspace, null);

    assertThat(command.sourceDescription()).isEqualTo("inferred:python@backend");
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
    try (Git git = Git.init().setDirectory(workspace.toFile()).call()) {
      git.add().addFilepattern(".").call();
      git.commit().setMessage("baseline").setAuthor("test", "test@example.com").call();
    }
    Files.writeString(workspace.resolve("backend/app.py"), "print('changed')\n");
    Files.writeString(workspace.resolve("frontend/app.ts"), "export const changed = true\n");

    BuildCommand command =
        new BuildCommandResolver().resolve(workspace, "python3 -m compileall -q backend/");

    assertThat(command.sourceDescription()).isEqualTo("inferred:changed-multi-root");
    assertThat(command.command())
        .contains("cd backend && python3 -m compileall -q .")
        .contains("cd frontend && npm run build");
  }

  @Test
  void helmShapedRepoBuildsOnlyChangedNestedMavenModule(@TempDir Path workspace) throws Exception {
    Path helm = workspace.resolve("repos/helm");
    Files.createDirectories(helm.resolve("backend-java/src/main/java"));
    Files.writeString(helm.resolve("backend-java/pom.xml"), "<project/>\n");
    Files.createDirectories(helm.resolve("crawler-node/src"));
    Files.writeString(
        helm.resolve("crawler-node/package.json"), "{\"scripts\":{\"build\":\"tsc\"}}");
    Files.createDirectories(helm.resolve("frontend-react/src"));
    Files.writeString(
        helm.resolve("frontend-react/package.json"), "{\"scripts\":{\"build\":\"vite build\"}}");
    try (Git git = Git.init().setDirectory(helm.toFile()).call()) {
      git.add().addFilepattern(".").call();
      git.commit().setMessage("baseline").setAuthor("test", "test@example.com").call();
    }
    Files.writeString(
        helm.resolve("backend-java/src/main/java/Changed.java"), "final class Changed {}\n");

    BuildCommand command = new BuildCommandResolver().resolve(workspace, null);

    assertThat(command.command())
        .isEqualTo("(cd repos/helm/backend-java && mvn -q -DskipTests compile)")
        .doesNotContain("crawler-node", "frontend-react");
    assertThat(command.sourceDescription()).isEqualTo("inferred:maven@repos/helm/backend-java");
    assertThat(command.validationRoots()).containsExactly(helm.resolve("backend-java"));
  }

  @Test
  void multipleUnchangedNestedModulesAreHonestlyAmbiguous(@TempDir Path workspace)
      throws Exception {
    Files.createDirectories(workspace.resolve("repo/backend"));
    Files.writeString(workspace.resolve("repo/backend/pom.xml"), "<project/>\n");
    Files.createDirectories(workspace.resolve("repo/frontend"));
    Files.writeString(workspace.resolve("repo/frontend/package.json"), "{}\n");

    BuildCommand command = new BuildCommandResolver().resolve(workspace, null);

    assertThat(command.command()).isEqualTo("true");
    assertThat(command.sourceDescription()).isEqualTo("fallback:no-op");
  }
}
