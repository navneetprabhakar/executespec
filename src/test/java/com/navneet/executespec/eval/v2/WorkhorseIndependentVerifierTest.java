package com.navneet.executespec.eval.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.engine.WorkerResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkhorseIndependentVerifierTest {

  @TempDir Path tempDir;

  @Test
  void recomputesFixArtifactsEvenWhenWorkerCompletionClaimIsWrong() throws Exception {
    Files.writeString(tempDir.resolve("numbers.py"), "def is_even(n):\n    return n % 2 == 0\n");
    Files.writeString(
        tempDir.resolve("test_numbers.py"),
        "assert is_even(4) is True\nassert is_even(3) is False\n");

    var result =
        new WorkhorseIndependentVerifier()
            .verify(
                "workhorse-fix-bug",
                tempDir,
                WorkerResult.builder()
                    .outcome(WorkerResult.Outcome.ERROR)
                    .toolCallLog(List.of())
                    .build());

    assertThat(result.artifactScore()).isEqualTo(100);
    assertThat(result.workerClaimedCompletion()).isFalse();
    assertThat(result.findings()).isEmpty();
  }
}
