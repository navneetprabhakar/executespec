package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The structural contract of a sandbox command: a vector, bounded, with a reachable ceiling. */
class SandboxExecRequestTest {

  private static final Duration MINUTE = Duration.ofMinutes(1);

  @Test
  void anOrdinaryVectorIsAcceptedAndDefensivelyCopied() {
    List<String> mutable = new ArrayList<>(List.of("mvn", "-q", "verify"));
    SandboxExecRequest request = new SandboxExecRequest(mutable, "/workspace", MINUTE);

    mutable.set(0, "rm");
    assertThat(request.argv()).containsExactly("mvn", "-q", "verify");
    assertThat(request.programLabel()).isEqualTo("mvn");
    assertThatThrownBy(() -> request.argv().add("--extra"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aShellIsPossibleOnlyByNamingItInTheVector() {
    // Not a prohibition — some callers genuinely need shell semantics. The point is that the shell
    // is visible in the argv rather than being an invisible default applied to a customer string.
    SandboxExecRequest request =
        SandboxExecRequest.of(List.of("sh", "-c", "make build && make test"), MINUTE);
    assertThat(request.argv()).startsWith("sh", "-c");
  }

  @Test
  void anEmptyOrNullVectorIsRefused() {
    assertThatThrownBy(() -> SandboxExecRequest.of(List.of(), MINUTE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SandboxExecRequest.of(null, MINUTE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullArgumentIsRefusedRatherThanPassedThrough() {
    assertThatThrownBy(() -> SandboxExecRequest.of(Arrays.asList("ls", null), MINUTE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anArgumentContainingNulIsRefusedBecauseItTruncatesAtTheSyscall() {
    assertThatThrownBy(() -> SandboxExecRequest.of(List.of("ls", "safe" + '\0' + "tail"), MINUTE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NUL");
  }

  @Test
  void shellMetacharactersAreOrdinaryArgumentText() {
    // The whole reason a vector is required: no layer joins these into a command line, so they
    // reach the program as literal argument bytes and mean nothing to any shell.
    SandboxExecRequest request =
        SandboxExecRequest.of(List.of("echo", "; rm -rf / #", "$(whoami)", "`id`"), MINUTE);
    assertThat(request.argv()).containsExactly("echo", "; rm -rf / #", "$(whoami)", "`id`");
  }

  @Test
  void aProgramThatLooksLikeAnOptionIsRefused() {
    // So the contract does not rest on the container CLI's option-interspersal behaviour.
    assertThatThrownBy(() -> SandboxExecRequest.of(List.of("--privileged", "sh"), MINUTE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anUnboundedVectorOrArgumentIsRefused() {
    assertThatThrownBy(() -> SandboxExecRequest.of(Collections.nCopies(600, "echo"), MINUTE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SandboxExecRequest.of(List.of("echo", "x".repeat(9000)), MINUTE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aWorkdirMustBeAbsoluteAndFreeOfTraversal() {
    assertThatThrownBy(() -> new SandboxExecRequest(List.of("ls"), "relative", MINUTE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SandboxExecRequest(List.of("ls"), "/workspace/../etc", MINUTE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new SandboxExecRequest(List.of("ls"), null, MINUTE).workdir()).isNull();
  }

  @Test
  void thereIsNoUnboundedOrAbsentTimeout() {
    assertThatThrownBy(() -> SandboxExecRequest.of(List.of("ls"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SandboxExecRequest.of(List.of("ls"), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SandboxExecRequest.of(List.of("ls"), SandboxExecRequest.MAX_TIMEOUT.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ceiling");
  }
}
