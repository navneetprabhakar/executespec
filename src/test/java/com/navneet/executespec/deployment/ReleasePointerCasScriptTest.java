package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleasePointerCasScriptTest {

  private static final String OLD = "a".repeat(40);
  private static final String TARGET = "b".repeat(40);

  @TempDir Path temporary;
  private Path state;
  private Path generation;
  private Path fakeBin;

  @BeforeEach
  void fakeGsutil() throws Exception {
    state = temporary.resolve("pointer");
    generation = temporary.resolve("generation");
    fakeBin = temporary.resolve("bin");
    Files.createDirectory(fakeBin);
    Path gsutil = fakeBin.resolve("gsutil");
    Files.writeString(
        gsutil,
        """
        #!/usr/bin/env bash
        set -euo pipefail
        case "$1" in
          cat) cat "$STATE" ;;
          stat) printf 'Generation: %s\n' "$(cat "$GENERATION")" ;;
          -h)
            while [ "$1" != "cp" ]; do shift; done
            shift
            read -r value || true
            printf '%s' "$value" > "$STATE"
            current="$(cat "$GENERATION")"
            printf '%s' "$((current + 1))" > "$GENERATION"
            if [ "${FAIL_AFTER_WRITE:-0}" = "1" ] && [ ! -e "$FAILED_ONCE" ]; then
              touch "$FAILED_ONCE"
              exit 42
            fi
            ;;
          *) exit 64 ;;
        esac
        """);
    Files.setPosixFilePermissions(gsutil, PosixFilePermissions.fromString("rwx------"));
  }

  @Test
  void publishesOnlyTheExpectedGenerationAndReplayIsIdempotent() throws Exception {
    reset(OLD, "7");

    Result first = run(Map.of());
    Result replay = run(Map.of());

    assertThat(first.exitCode()).as(first.output()).isZero();
    assertThat(Files.readString(state)).isEqualTo(TARGET);
    assertThat(replay.exitCode()).as(replay.output()).isZero();
    assertThat(Files.readString(generation)).isEqualTo("8");
  }

  @Test
  void staleWriterCannotOverwriteAChangedPointer() throws Exception {
    reset("c".repeat(40), "8");

    Result result = run(Map.of());

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("refusing stale pointer write");
    assertThat(Files.readString(state)).isEqualTo("c".repeat(40));
  }

  @Test
  void lostWriteResponseConvergesOnReplayWithoutASecondMutation() throws Exception {
    reset(OLD, "7");

    Result lost = run(Map.of("FAIL_AFTER_WRITE", "1"));
    Result replay = run(Map.of("FAIL_AFTER_WRITE", "1"));

    assertThat(lost.exitCode()).isEqualTo(42);
    assertThat(Files.readString(state)).isEqualTo(TARGET);
    assertThat(replay.exitCode()).as(replay.output()).isZero();
    assertThat(Files.readString(generation)).isEqualTo("8");
  }

  private void reset(String sha, String pointerGeneration) throws Exception {
    Files.writeString(state, sha);
    Files.writeString(generation, pointerGeneration);
    Files.deleteIfExists(temporary.resolve("failed-once"));
  }

  private Result run(Map<String, String> overrides) throws Exception {
    ProcessBuilder process = new ProcessBuilder("bash", "scripts/publish-release-pointer.sh");
    process.redirectErrorStream(true);
    process.environment().put("PATH", fakeBin + ":" + process.environment().get("PATH"));
    process.environment().put("STATE", state.toString());
    process.environment().put("GENERATION", generation.toString());
    process.environment().put("FAILED_ONCE", temporary.resolve("failed-once").toString());
    process.environment().put("CURRENT_SHA_BUCKET", "gs://fixture/CURRENT_SHA");
    process.environment().put("PREVIOUS_CURRENT_SHA", OLD);
    process.environment().put("PREVIOUS_CURRENT_GENERATION", "7");
    process.environment().put("TARGET_SHA", TARGET);
    process.environment().putAll(overrides);
    Process running = process.start();
    String output = new String(running.getInputStream().readAllBytes());
    return new Result(running.waitFor(), output);
  }

  private record Result(int exitCode, String output) {}
}
