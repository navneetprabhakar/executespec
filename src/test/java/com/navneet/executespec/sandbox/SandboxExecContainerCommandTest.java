package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The exec argument vector.
 *
 * <p>Asserted directly, because the two properties that matter are properties of the argv and not
 * of a running container: the exec must not be able to regain privilege that {@code create}
 * dropped, and the customer's command must sit strictly after the container name so nothing in it
 * can be read as a flag.
 */
class SandboxExecContainerCommandTest {

  private static final Duration MINUTE = Duration.ofMinutes(1);

  @Test
  void theCommandFollowsTheContainerAndTheIdentityIsPinnedUnprivileged() {
    List<String> argv =
        SandboxContainerCommand.exec(
            "es-sbx-1",
            new SandboxExecRequest(List.of("mvn", "-q", "verify"), "/workspace", MINUTE));

    assertThat(argv).startsWith("docker", "exec");
    assertThat(argv).containsSequence("--user", SandboxContainerCommand.NON_ROOT_USER);
    assertThat(argv).containsSequence("--workdir", "/workspace");
    assertThat(argv).containsSequence("es-sbx-1", "mvn", "-q", "verify");
    assertThat(argv.indexOf("es-sbx-1")).isEqualTo(argv.size() - 4);
  }

  @Test
  void execNeverRegainsWhatCreateDropped() {
    List<String> argv =
        SandboxContainerCommand.exec("es-sbx-1", SandboxExecRequest.of(List.of("ls"), MINUTE));

    // An exec that can ask for privilege, root, or a host environment undoes the container's
    // hardening from the inside, which is the one way a correct `create` still yields no sandbox.
    assertThat(argv).doesNotContain("--privileged");
    assertThat(argv).doesNotContain("root", "0:0");
    assertThat(argv).containsSequence("--user", SandboxContainerCommand.NON_ROOT_USER);
    assertThat(argv).doesNotContain("--env");
    assertThat(argv).doesNotContain("--env-file");
    assertThat(argv).doesNotContain("--tty");
    assertThat(argv).doesNotContain("--detach");
  }

  @Test
  void anAttackerControlledArgumentCannotBecomeAnOption() {
    // Everything after the container name is the command. A value that looks like a docker flag is
    // therefore an argument to the program, not an option to the engine.
    List<String> argv =
        SandboxContainerCommand.exec(
            "es-sbx-1", SandboxExecRequest.of(List.of("echo", "--privileged"), MINUTE));

    assertThat(argv.subList(0, argv.indexOf("es-sbx-1"))).doesNotContain("--privileged");
    assertThat(argv.getLast()).isEqualTo("--privileged");
  }

  @Test
  void anOmittedWorkdirEmitsNoWorkdirFlagRatherThanGuessingOne() {
    List<String> argv =
        SandboxContainerCommand.exec("es-sbx-1", SandboxExecRequest.of(List.of("ls"), MINUTE));
    assertThat(argv).doesNotContain("--workdir");
  }

  @Test
  void anUnsafeContainerNameIsRefused() {
    assertThatThrownBy(
            () ->
                SandboxContainerCommand.exec(
                    "es sbx; rm -rf /", SandboxExecRequest.of(List.of("ls"), MINUTE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SandboxContainerCommand.exec(null, SandboxExecRequest.of(List.of("ls"), MINUTE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theResultingVectorIsImmutable() {
    List<String> argv =
        SandboxContainerCommand.exec("es-sbx-1", SandboxExecRequest.of(List.of("ls"), MINUTE));
    assertThatThrownBy(() -> argv.add("--privileged"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
