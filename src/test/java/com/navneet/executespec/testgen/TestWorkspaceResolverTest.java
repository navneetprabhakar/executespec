package com.navneet.executespec.testgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class TestWorkspaceResolverTest {

  @Test
  void springCanInstantiateTheComponentWithItsConfiguredWorkspace(@TempDir Path agentWorkspace) {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource("test", Map.of("agent.workspace", agentWorkspace.toString())));
      context.register(TestWorkspaceResolver.class);
      context.refresh();

      assertThat(context.getBean(TestWorkspaceResolver.class)).isNotNull();
    }
  }

  @Test
  void generatedTestsResolveInsideTheRunOwnedSandboxMount(@TempDir Path agentWorkspace)
      throws Exception {
    Path runRoot = Files.createDirectory(agentWorkspace.resolve("run-1"));
    TestWorkspaceResolver resolver = new TestWorkspaceResolver(agentWorkspace);

    Path canonicalRunRoot = runRoot.toRealPath();
    assertThat(resolver.projectRoot("run-1"))
        .isEqualTo(canonicalRunRoot.resolve(".executespec/generated-tests"));
    assertThat(resolver.testSourceRoot("run-1"))
        .isEqualTo(
            canonicalRunRoot.resolve(
                ".executespec/generated-tests/src/test/java/com/executespec/tests"));
  }

  @Test
  void repositorySymlinkCannotRedirectGeneratedTestsOutsideTheRun(@TempDir Path agentWorkspace)
      throws Exception {
    Path runRoot = Files.createDirectory(agentWorkspace.resolve("run-1"));
    Path outside = Files.createDirectory(agentWorkspace.resolve("outside"));
    Files.createSymbolicLink(runRoot.resolve(".executespec"), outside);
    TestWorkspaceResolver resolver = new TestWorkspaceResolver(agentWorkspace);

    assertThatThrownBy(() -> resolver.projectRoot("run-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not safely contained");
  }

  @Test
  void unsafeRunIdentifierIsRejectedBeforeFilesystemResolution(@TempDir Path agentWorkspace) {
    TestWorkspaceResolver resolver = new TestWorkspaceResolver(agentWorkspace);

    assertThatThrownBy(() -> resolver.projectRoot("../other-run"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe run id");
  }
}
