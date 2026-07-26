package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RR-327 — proves the strict runtime never removes a container it cannot prove it owns.
 *
 * <p>The container engine is replaced wholesale by scripting {@link
 * ContainerStrictV1Runtime#exec(List, int)}, which is the single place this class starts a process.
 * These tests assert the fencing decision, not the behaviour of a live daemon: the two-JVM takeover
 * against real containers remains an unexecuted item on the security matrix.
 */
class ContainerStrictV1RuntimeFenceTest {

  private static final UUID SANDBOX_ID = UUID.randomUUID();
  private static final String CONTAINER = "es-sbx-" + SANDBOX_ID.toString().replace("-", "");

  private ScriptedRuntime runtime;

  @BeforeEach
  void setUp() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)).thenReturn(true);
    runtime = new ScriptedRuntime(new SandboxProperties(), flags);
  }

  @Test
  void destroysOnlyAContainerCarryingThisSandboxsExactFencingLabels() {
    runtime.onInspectFence(0, SANDBOX_ID + "|3");

    runtime.destroy(sandbox(3L));

    assertThat(runtime.issued("rm")).as("an exactly-matching container is removed").hasSize(1);
  }

  /**
   * The RR-327 race itself: the container name is derived from the sandbox id and is stable across
   * a takeover, so a name match is not an ownership proof. A different runtime generation must not
   * be removed by a process still holding the old one.
   */
  @Test
  void refusesToDestroyAContainerFromADifferentRuntimeGeneration() {
    runtime.onInspectFence(0, SANDBOX_ID + "|9");

    assertThatThrownBy(() -> runtime.destroy(sandbox(3L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refusing to destroy");
    assertThat(runtime.issued("rm")).isEmpty();
  }

  @Test
  void refusesToDestroyAForeignOrUnlabelledContainerThatAnswersToTheSameName() {
    for (String observed : List.of("", "|", UUID.randomUUID() + "|3", "<no value>|<no value>")) {
      runtime = new ScriptedRuntime(new SandboxProperties(), alwaysOnFlags());
      runtime.onInspectFence(0, observed);

      assertThatThrownBy(() -> runtime.destroy(sandbox(3L)))
          .as("fence %s must not be treated as ownership", observed)
          .isInstanceOf(IllegalStateException.class);
      assertThat(runtime.issued("rm")).isEmpty();
    }
  }

  /**
   * An engine that cannot be read proves nothing, and "nothing proven" must not authorise a delete.
   */
  @Test
  void refusesToDestroyWhenTheContainerEngineCannotBeRead() {
    runtime.onInspectFence(1, "Cannot connect to the Docker daemon at unix:///var/run/docker.sock");

    assertThatThrownBy(() -> runtime.destroy(sandbox(3L)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(runtime.issued("rm")).isEmpty();
  }

  @Test
  void anAbsentContainerIsACleanIdempotentTeardown() {
    runtime.onInspectFence(1, "Error: No such object: " + CONTAINER);

    runtime.destroy(sandbox(3L));

    assertThat(runtime.issued("rm")).as("nothing to remove, and no failure raised").isEmpty();
  }

  @Test
  void refusesToReattachToARunningContainerThatIsNotThisSandboxesOwn() {
    runtime.onInspectState(0, "true");
    runtime.onInspectFence(0, UUID.randomUUID() + "|3");

    assertThatThrownBy(() -> runtime.provision(sandbox(3L), spec()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refusing to reattach");
    assertThat(runtime.issued("run")).isEmpty();
  }

  @Test
  void aRunningContainerWithForeignLabelsIsNotThisSandboxBeingAlive() {
    runtime.onInspectState(0, "true");
    runtime.onInspectFence(0, UUID.randomUUID() + "|3");

    assertThat(runtime.alive(sandbox(3L))).isFalse();
  }

  private static FeatureFlagService alwaysOnFlags() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(RunSandboxRuntimeDispatcher.FLAG_CONTAINER_STRICT_V1)).thenReturn(true);
    return flags;
  }

  private static SandboxRuntimeSpec spec() {
    return new SandboxRuntimeSpec(
        "img",
        "/workspace",
        List.of(),
        new SandboxRuntimeSpec.SandboxRuntimeLimits(
            2.0, 1024L * 1024 * 1024, 128, Duration.ofMinutes(15), 64L * 1024 * 1024),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS);
  }

  private static RunSandbox sandbox(long runtimeEpoch) {
    return new RunSandbox(
        SANDBOX_ID,
        "run-1",
        "project-1",
        null,
        "user-1",
        "org-1",
        "abc1234",
        "[]",
        SandboxState.PROVISIONING,
        SandboxIsolationProfile.CONTAINER_STRICT_V1,
        1,
        "jvm-a",
        1L,
        Instant.now().plusSeconds(60),
        runtimeEpoch,
        Instant.now(),
        null,
        null,
        spec().configDigest(),
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS,
        Instant.now().plusSeconds(3600),
        null,
        null,
        null,
        null,
        null,
        0,
        null,
        SandboxRetentionState.RETAINED,
        null,
        "idem-1",
        Instant.now(),
        null,
        null,
        null,
        null);
  }

  /** The container engine, replaced by a script. Records every argv the runtime issues. */
  private static final class ScriptedRuntime extends ContainerStrictV1Runtime {

    private final List<List<String>> issued = new ArrayList<>();
    private final Map<String, ExecResult> responses = new LinkedHashMap<>();

    ScriptedRuntime(SandboxProperties properties, FeatureFlagService featureFlags) {
      // The fence tests never reach the run-workspace mount path (they reattach to a running
      // container), so any workspace root suffices here.
      super(properties, featureFlags, Path.of(System.getProperty("java.io.tmpdir")));
      responses.put("version", new ExecResult(0, "27.0.0"));
      responses.put("state", new ExecResult(1, "Error: No such object"));
      responses.put("digest", new ExecResult(0, "img@sha256:deadbeef"));
    }

    void onInspectFence(int exitCode, String output) {
      responses.put("fence", new ExecResult(exitCode, output));
    }

    void onInspectState(int exitCode, String output) {
      responses.put("state", new ExecResult(exitCode, output));
    }

    List<List<String>> issued(String dockerVerb) {
      return issued.stream()
          .filter(argv -> argv.size() > 1 && argv.get(1).equals(dockerVerb))
          .toList();
    }

    @Override
    ExecResult exec(List<String> argv, int timeoutSeconds) {
      issued.add(argv);
      String joined = String.join(" ", argv);
      if (joined.contains("version")) {
        return responses.get("version");
      }
      if (joined.contains(SandboxContainerCommand.LABEL_SANDBOX_ID)) {
        return responses.getOrDefault("fence", new ExecResult(1, "Error: No such object"));
      }
      if (joined.contains(".State.Running")) {
        return responses.get("state");
      }
      if (joined.contains("RepoDigests")) {
        return responses.get("digest");
      }
      return new ExecResult(0, "");
    }
  }
}
