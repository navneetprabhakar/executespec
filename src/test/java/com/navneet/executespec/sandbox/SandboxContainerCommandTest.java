package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the CONTAINER_STRICT_V1 argument vector.
 *
 * <p>These assertions prove what is passed to the container engine. They do NOT prove what the
 * resulting container can or cannot do; that requires the live security matrix, which has not been
 * executed. See the class javadoc on {@link ContainerStrictV1Runtime}.
 */
class SandboxContainerCommandTest {

  private static final String SANDBOX_ID = "s1";
  private static final long EPOCH = 7L;

  /**
   * RR-328 — mount admission now proves containment on disk, so the root and every admitted mount
   * must be real. A test that asserted over invented paths could not tell a lexical check from a
   * real one, which is how the symlink escape survived.
   */
  @TempDir Path tempRoot;

  /** Stands in for anything on the host that is not a sandbox workspace. */
  @TempDir Path hostElsewhere;

  private Path root;
  private Path work;

  @BeforeEach
  void setUp() throws IOException {
    root = tempRoot.toRealPath();
    work = Files.createDirectories(root.resolve(SANDBOX_ID).resolve("work"));
  }

  @Test
  void strictProfileAppliesEveryDeclaredHardeningFlag() {
    List<String> argv = SandboxContainerCommand.create("es-sbx-1", spec(), root, SANDBOX_ID, EPOCH);
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
    String joined =
        String.join(
            " ", SandboxContainerCommand.create("es-sbx-1", spec(), root, SANDBOX_ID, EPOCH));
    assertThat(joined).contains("--memory 1073741824b");
    assertThat(joined).contains("--memory-swap 1073741824b");
  }

  @Test
  void theOnlyWritableSurfacesAreTheCappedTmpfsAndExplicitMounts() {
    List<String> argv =
        SandboxContainerCommand.create(
            "es-sbx-1",
            specWithMount(new SandboxRuntimeSpec.WritableMount(work.toString(), "/workspace")),
            root,
            SANDBOX_ID,
            EPOCH);
    String joined = String.join(" ", argv);

    assertThat(argv).contains("--read-only");
    assertThat(joined).contains("--tmpfs /tmp:rw,noexec,nosuid,size=67108864");
    assertThat(joined).contains("--volume " + work + ":/workspace:rw");

    // Exactly one bind mount: nothing else is writable.
    assertThat(argv.stream().filter("--volume"::equals)).hasSize(1);
  }

  @Test
  void neverMountsTheDockerSocketOrAnyPathOutsideTheSandboxRoot() throws IOException {
    Files.createDirectories(root.resolve("other-tenant").resolve("work"));

    for (String outside :
        List.of(
            "/var/run/docker.sock",
            "/var/run",
            "/etc/shadow",
            "/root/.aws",
            "/Users/someone/.ssh",
            // RR-821 — these two are INSIDE the configured global root and were once admitted:
            // the root that holds every sandbox, and a sibling tenant's workspace.
            root.toString(),
            root.resolve("other-tenant").resolve("work").toString(),
            "/")) {
      assertThatThrownBy(() -> create(outside))
          .as("host path %s must be refused", outside)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void traversalAndRelativeMountPathsAreRefused() {
    assertThatThrownBy(() -> create(root.resolve(SANDBOX_ID) + "/../../../etc"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> create("relative/path")).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * RR-328 — the escape the previous lexical check could not see. Every one of these paths passes a
   * {@code startsWith(sandboxRoot)} test; each one makes Docker bind something else read-write into
   * a container the customer's code controls.
   */
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void symbolicLinksInsideTheSandboxRootCannotSmuggleTheirTargetIntoTheContainer()
      throws IOException {
    Path outside = Files.createDirectories(hostElsewhere.resolve("secrets")).toRealPath();
    Path sandboxRoot = root.resolve(SANDBOX_ID);

    // 1. The mount itself is a link to a host directory.
    Path linkedMount = Files.createSymbolicLink(sandboxRoot.resolve("escape"), outside);

    // 2. The mount is real, but an intermediate component on the way to it is a link.
    Path linkedParent = Files.createSymbolicLink(sandboxRoot.resolve("via"), outside);
    Path throughLinkedParent = Files.createDirectories(outside.resolve("target"));

    for (String escape :
        List.of(
            linkedMount.toString(),
            linkedParent.resolve("target").toString(),
            // Same target, reached lexically from inside the root.
            sandboxRoot.resolve("via").resolve(throughLinkedParent.getFileName()).toString())) {
      assertThatThrownBy(() -> create(escape))
          .as("symlinked host path %s must be refused, not resolved", escape)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  /**
   * A bind path Docker would have to create is created root-owned and never passed through
   * workspace preparation, so "not there yet" is a refusal rather than a mount.
   */
  @Test
  void aMountThatDoesNotAlreadyExistAsARealDirectoryIsRefused() throws IOException {
    assertThatThrownBy(() -> create(root.resolve(SANDBOX_ID).resolve("not-created-yet").toString()))
        .isInstanceOf(IllegalArgumentException.class);

    Path file = Files.createFile(root.resolve(SANDBOX_ID).resolve("a-file"));
    assertThatThrownBy(() -> create(file.toString())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyContainerCarriesItsSandboxIdAndRuntimeEpochAsFencingLabels() {
    List<String> argv = SandboxContainerCommand.create("es-sbx-1", spec(), root, SANDBOX_ID, EPOCH);

    assertThat(argv)
        .containsSequence("--label", SandboxContainerCommand.LABEL_SANDBOX_ID + "=" + SANDBOX_ID)
        .containsSequence("--label", SandboxContainerCommand.LABEL_SANDBOX_EPOCH + "=" + EPOCH);
  }

  @Test
  void theFenceProbeReadsBothLabelsAndToleratesAnUnlabelledContainer() {
    List<String> argv = SandboxContainerCommand.inspectFence("es-sbx-1");

    assertThat(argv).startsWith("docker", "inspect", "--format").endsWith("es-sbx-1");
    assertThat(argv.get(3))
        .contains(SandboxContainerCommand.LABEL_SANDBOX_ID)
        .contains(SandboxContainerCommand.LABEL_SANDBOX_EPOCH)
        .as("a container with no labels must yield empty output, not a template error")
        .startsWith("{{with .Config.Labels}}");
    assertThat(SandboxContainerCommand.expectedFence(SANDBOX_ID, EPOCH)).isEqualTo("s1|7");
  }

  private List<String> create(String hostPath) {
    return SandboxContainerCommand.create(
        "es-sbx-1",
        specWithMount(new SandboxRuntimeSpec.WritableMount(hostPath, "/mnt")),
        root,
        SANDBOX_ID,
        EPOCH);
  }

  @Test
  void containerNamesThatCouldCarryArgumentsAreRefused() {
    for (String unsafe :
        List.of("es sbx", "-es-sbx", "es;rm -rf /", "es$(id)", "", "a".repeat(70))) {
      assertThatThrownBy(
              () -> SandboxContainerCommand.create(unsafe, spec(), root, SANDBOX_ID, EPOCH))
          .as("container name %s", unsafe)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void commandIsAnArgumentVectorNotAShellString() {
    List<String> argv = SandboxContainerCommand.create("es-sbx-1", spec(), root, SANDBOX_ID, EPOCH);
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
    assertThat(
            String.join(
                " ", SandboxContainerCommand.create("es-sbx-1", spec(), root, SANDBOX_ID, EPOCH)))
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
