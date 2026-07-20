package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the CONTAINER_STRICT_V1 argument vector.
 *
 * <p>These assertions prove what is passed to the container engine. They do NOT prove what the
 * resulting container can or cannot do; that requires the live security matrix, which has not been
 * executed. See the class javadoc on {@link ContainerStrictV1Runtime}.
 */
class SandboxContainerCommandTest {

  private static final Path ROOT = Path.of("/var/lib/executespec/sandboxes");

  @Test
  void strictProfileAppliesEveryDeclaredHardeningFlag() {
    List<String> argv = SandboxContainerCommand.create("es-sbx-1", spec(), ROOT);
    String joined = String.join(" ", argv);

    assertThat(argv).startsWith("docker", "run", "--detach");
    assertThat(joined).contains("--network none");
    assertThat(joined).contains("--user " + SandboxContainerCommand.NON_ROOT_USER);
    assertThat(joined).contains("--security-opt no-new-privileges");
    assertThat(joined).contains("--cap-drop ALL");
    assertThat(argv).contains("--read-only");
    assertThat(joined).contains("--cpus 2.0");
    assertThat(joined).contains("--memory 1073741824b");
    assertThat(joined).contains("--pids-limit 128");
    assertThat(joined).contains("--workdir /workspace");
  }

  @Test
  void memorySwapIsPinnedToMemorySoSwapCannotDefeatTheCap() {
    String joined = String.join(" ", SandboxContainerCommand.create("es-sbx-1", spec(), ROOT));
    assertThat(joined).contains("--memory 1073741824b");
    assertThat(joined).contains("--memory-swap 1073741824b");
  }

  @Test
  void theOnlyWritableSurfacesAreTheCappedTmpfsAndExplicitMounts() {
    List<String> argv =
        SandboxContainerCommand.create(
            "es-sbx-1",
            specWithMount(
                new SandboxRuntimeSpec.WritableMount(
                    "/var/lib/executespec/sandboxes/s1/work", "/workspace")),
            ROOT);
    String joined = String.join(" ", argv);

    assertThat(argv).contains("--read-only");
    assertThat(joined).contains("--tmpfs /tmp:rw,noexec,nosuid,size=67108864");
    assertThat(joined).contains("--volume /var/lib/executespec/sandboxes/s1/work:/workspace:rw");

    // Exactly one bind mount: nothing else is writable.
    assertThat(argv.stream().filter("--volume"::equals)).hasSize(1);
  }

  @Test
  void neverMountsTheDockerSocketOrAnyPathOutsideTheSandboxRoot() {
    for (String outside :
        List.of(
            "/var/run/docker.sock",
            "/var/run",
            "/etc/shadow",
            "/root/.aws",
            "/Users/someone/.ssh",
            "/var/lib/executespec/other-tenant",
            "/")) {
      assertThatThrownBy(
              () ->
                  SandboxContainerCommand.create(
                      "es-sbx-1",
                      specWithMount(new SandboxRuntimeSpec.WritableMount(outside, "/mnt")),
                      ROOT))
          .as("host path %s must be refused", outside)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void traversalAndRelativeMountPathsAreRefused() {
    assertThatThrownBy(
            () ->
                SandboxContainerCommand.create(
                    "es-sbx-1",
                    specWithMount(
                        new SandboxRuntimeSpec.WritableMount(
                            "/var/lib/executespec/sandboxes/../../../etc", "/mnt")),
                    ROOT))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                SandboxContainerCommand.create(
                    "es-sbx-1",
                    specWithMount(new SandboxRuntimeSpec.WritableMount("relative/path", "/mnt")),
                    ROOT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void containerNamesThatCouldCarryArgumentsAreRefused() {
    for (String unsafe :
        List.of("es sbx", "-es-sbx", "es;rm -rf /", "es$(id)", "", "a".repeat(70))) {
      assertThatThrownBy(() -> SandboxContainerCommand.create(unsafe, spec(), ROOT))
          .as("container name %s", unsafe)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void commandIsAnArgumentVectorNotAShellString() {
    List<String> argv = SandboxContainerCommand.create("es-sbx-1", spec(), ROOT);
    assertThat(argv).doesNotContain("sh", "-c", "/bin/sh", "bash");
  }

  @Test
  void teardownIsForcefulAndRemovesAnonymousVolumes() {
    assertThat(SandboxContainerCommand.destroy("es-sbx-1"))
        .containsExactly("docker", "rm", "--force", "--volumes", "es-sbx-1");
  }

  @Test
  void wallClockCeilingIsExposedBecauseNoContainerFlagExpressesIt() {
    assertThat(SandboxContainerCommand.limitsExceededTimeout(spec())).isEqualTo(900L);
    assertThat(String.join(" ", SandboxContainerCommand.create("es-sbx-1", spec(), ROOT)))
        .endsWith("sleep 900");
  }

  @Test
  void onlyDenyAllNetworkAndNoHostSecretsCanBeSpecified() {
    assertThatThrownBy(
            () ->
                new SandboxRuntimeSpec(
                    "img",
                    "/workspace",
                    List.of(),
                    limits(),
                    SandboxNetworkPolicy.ALLOWLIST_PROXY,
                    SandboxSecretPolicy.NO_HOST_SECRETS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("egress proxy");
  }

  @Test
  void everyResourceCeilingIsMandatoryAndPositive() {
    assertThatThrownBy(
            () ->
                new SandboxRuntimeSpec.SandboxRuntimeLimits(
                    0, 1024, 8, Duration.ofMinutes(1), 1024))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SandboxRuntimeSpec.SandboxRuntimeLimits(1, 0, 8, Duration.ofMinutes(1), 1024))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SandboxRuntimeSpec.SandboxRuntimeLimits(1, 1024, 0, Duration.ofMinutes(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SandboxRuntimeSpec.SandboxRuntimeLimits(1, 1024, 8, Duration.ZERO, 1024))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void configDigestChangesWhenEnforcedPolicyChanges() {
    String baseline = spec().configDigest();
    assertThat(spec().configDigest()).as("digest is stable").isEqualTo(baseline);

    SandboxRuntimeSpec weaker =
        new SandboxRuntimeSpec(
            "img",
            "/workspace",
            List.of(),
            new SandboxRuntimeSpec.SandboxRuntimeLimits(
                8, 1024L * 1024 * 1024, 128, Duration.ofMinutes(15), 64L * 1024 * 1024),
            SandboxNetworkPolicy.DENY_ALL,
            SandboxSecretPolicy.NO_HOST_SECRETS);
    assertThat(weaker.configDigest())
        .as("raising the CPU ceiling must change the audited digest")
        .isNotEqualTo(baseline);
  }

  private static SandboxRuntimeSpec spec() {
    return new SandboxRuntimeSpec(
        "img",
        "/workspace",
        List.of(),
        limits(),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS);
  }

  private static SandboxRuntimeSpec specWithMount(SandboxRuntimeSpec.WritableMount mount) {
    return new SandboxRuntimeSpec(
        "img",
        "/workspace",
        List.of(mount),
        limits(),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS);
  }

  private static SandboxRuntimeSpec.SandboxRuntimeLimits limits() {
    return new SandboxRuntimeSpec.SandboxRuntimeLimits(
        2.0, 1024L * 1024 * 1024, 128, Duration.ofMinutes(15), 64L * 1024 * 1024);
  }
}
