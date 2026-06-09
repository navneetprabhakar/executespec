package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W4.2 (P6) — pins the background-job engine over real short-lived host processes: start → status →
 * tail → exit; kill a long job; run-end cleanup kills + de-registers; tail truncation; unknown-job
 * handling. Skipped where {@code /bin/sh} is absent (non-Unix CI).
 */
@DisplayName("JobRegistry (W4.2 — background command jobs)")
class JobRegistryTest {

  @TempDir Path ws;
  private final JobRegistry reg = new JobRegistry();

  private ExecutionContext ctx(String runId) {
    return new ExecutionContext(runId, "T1", ws);
  }

  private static boolean shAvailable() {
    return Files.isExecutable(Path.of("/bin/sh"));
  }

  @Test
  @DisplayName("start → runs, captures output, reaches exited status with rc")
  void startPollTail() throws Exception {
    assumeTrue(shAvailable(), "/bin/sh not available");
    JobRegistry.Job job = reg.start(ctx("run-a"), "echo hello-bg; sleep 0.2");
    assertThat(job.jobId()).startsWith("job_");
    job.process().waitFor(5, TimeUnit.SECONDS);
    assertThat(reg.status(job.jobId())).contains("exited").contains("rc=0");
    assertThat(reg.tail(job.jobId(), 1000)).contains("hello-bg");
  }

  @Test
  @DisplayName("kill terminates a long-running job")
  void killLong() throws Exception {
    assumeTrue(shAvailable(), "/bin/sh not available");
    JobRegistry.Job job = reg.start(ctx("run-b"), "sleep 30");
    assertThat(reg.status(job.jobId())).contains("running");
    reg.kill(job.jobId());
    job.process().waitFor(5, TimeUnit.SECONDS);
    assertThat(job.process().isAlive()).isFalse();
  }

  @Test
  @DisplayName("cleanup(runId) kills live jobs of that run + de-registers them")
  void cleanup() throws Exception {
    assumeTrue(shAvailable(), "/bin/sh not available");
    JobRegistry.Job job = reg.start(ctx("run-c"), "sleep 30");
    assertThat(reg.cleanup("run-c")).isEqualTo(1);
    assertThat(reg.get(job.jobId())).isEmpty();
    job.process().waitFor(5, TimeUnit.SECONDS);
    assertThat(job.process().isAlive()).isFalse();
    assertThat(reg.cleanup("run-c")).isZero(); // idempotent
  }

  @Test
  @DisplayName("unknown job ids return a clear message, not an exception")
  void unknownJob() {
    assertThat(reg.status("nope")).contains("unknown job");
    assertThat(reg.tail("nope", 100)).contains("unknown job");
    assertThat(reg.kill("nope")).contains("unknown job");
    assertThat(reg.get("nope")).isEmpty();
  }

  @Test
  @DisplayName("tailFile returns the whole small file, truncates a big one")
  void tailFile() throws Exception {
    Path f = ws.resolve("log.txt");
    Files.writeString(f, "small");
    assertThat(JobRegistry.tailFile(f, 1000)).isEqualTo("small");
    Files.writeString(f, "Z".repeat(10_000));
    String tail = JobRegistry.tailFile(f, 100);
    assertThat(tail).contains("truncated");
    assertThat(tail.length()).isLessThan(200);
  }
}
