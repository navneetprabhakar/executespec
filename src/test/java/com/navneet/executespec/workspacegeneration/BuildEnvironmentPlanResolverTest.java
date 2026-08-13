package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildEnvironmentPlanResolverTest {

  @TempDir Path workspace;

  @Test
  void resolvesOfflineLockBoundPlansForSupportedEcosystems() throws Exception {
    module(
        "node",
        "package.json",
        "{\"dependencies\":{\"left-pad\":\"1.3.0\"}}",
        "package-lock.json",
        "{}");
    module("maven", "pom.xml", "<project/>", null, null);
    module("gradle", "build.gradle.kts", "plugins {}", "gradle.lockfile", "locked");
    module("rust", "Cargo.toml", "[package]\nname='x'", "Cargo.lock", "version = 3");
    module("go", "go.mod", "module example.test/x", "go.sum", "sum");
    module("python", "requirements.txt", "fastapi==0.115.12", null, null);

    BuildEnvironmentPlan plan = resolver().resolve(workspace.toAbsolutePath());

    assertThat(plan.networkPolicy()).isEqualTo("DENY_ALL");
    assertThat(plan.cachePolicy()).isEqualTo("IMAGE_PRESEEDED_OFFLINE_ONLY");
    assertThat(plan.runtimeImage()).contains("@sha256:");
    assertThat(plan.planDigest()).matches("[0-9a-f]{64}");
    assertThat(plan.modules())
        .extracting(BuildEnvironmentPlan.Module::ecosystem)
        .containsExactly("GO", "GRADLE", "MAVEN", "NODE", "PYTHON", "RUST");
    assertThat(plan.modules())
        .allSatisfy(
            module -> {
              assertThat(module.manifestDigest()).matches("[0-9a-f]{64}");
              assertThat(module.argv()).isNotEmpty();
            });
    assertThat(
            plan.modules().stream()
                .filter(module -> module.ecosystem().equals("GO"))
                .findFirst()
                .orElseThrow()
                .argv())
        .contains("GOPROXY=off", "GOSUMDB=off");
    assertThat(
            plan.modules().stream()
                .filter(module -> module.ecosystem().equals("PYTHON"))
                .findFirst()
                .orElseThrow()
                .argv())
        .doesNotContain("pip", "--target");
  }

  @Test
  void nodeDependenciesWithoutALockfileFailBeforeExecution() throws Exception {
    module("node", "package.json", "{\"dependencies\":{\"left-pad\":\"1.3.0\"}}", null, null);

    assertThatThrownBy(() -> resolver().resolve(workspace.toAbsolutePath()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BUILD_ENVIRONMENT_NODE_LOCKFILE_REQUIRED");
  }

  @Test
  void dependencyFreeNodeModuleNeedsNoMaterialization() throws Exception {
    module("node", "package.json", "{\"scripts\":{\"test\":\"node --test\"}}", null, null);

    assertThat(resolver().resolve(workspace.toAbsolutePath()).modules()).isEmpty();
  }

  @Test
  void preservesEveryEcosystemWhenMultiplePrimaryManifestsShareOneModuleRoot() throws Exception {
    Files.writeString(workspace.resolve("package.json"), "{\"dependencies\":{\"x\":\"1\"}}");
    Files.writeString(workspace.resolve("package-lock.json"), "{}");
    Files.writeString(workspace.resolve("pom.xml"), "<project/>");

    assertThat(resolver().resolve(workspace.toAbsolutePath()).modules())
        .extracting(BuildEnvironmentPlan.Module::ecosystem)
        .containsExactly("MAVEN", "NODE");
  }

  @Test
  void pythonRequirementsMustBeExactlyPinned() throws Exception {
    Files.writeString(workspace.resolve("requirements.txt"), "fastapi>=0.100\n");

    assertThatThrownBy(() -> resolver().resolve(workspace.toAbsolutePath()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BUILD_ENVIRONMENT_PYTHON_LOCK_REQUIRED");
  }

  @Test
  void unpinnedRuntimeImageCannotEnterAPlanReceipt() throws Exception {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.runtimeImageIdentity()).thenReturn("toolchain:latest");

    assertThatThrownBy(
            () ->
                new BuildEnvironmentPlanResolver(new ObjectMapper(), routing)
                    .resolve(workspace.toAbsolutePath()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BUILD_ENVIRONMENT_RUNTIME_IMAGE_NOT_PINNED");
  }

  private BuildEnvironmentPlanResolver resolver() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.runtimeImageIdentity()).thenReturn("toolchain@sha256:" + "a".repeat(64));
    return new BuildEnvironmentPlanResolver(new ObjectMapper(), routing);
  }

  private void module(
      String directory, String manifest, String content, String lockfile, String lockContent)
      throws Exception {
    Path root = Files.createDirectories(workspace.resolve(directory));
    Files.writeString(root.resolve(manifest), content);
    if (lockfile != null) Files.writeString(root.resolve(lockfile), lockContent);
  }
}
