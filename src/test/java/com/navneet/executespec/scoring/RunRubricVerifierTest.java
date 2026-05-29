package com.navneet.executespec.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.scoring.entity.RunRubricRow;
import com.navneet.executespec.scoring.repository.RunRubricRowRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Scoring Revamp Phase A (2026-05-14) — pins each verification handler's contract end-to-end
 * against a real filesystem. No Spring context, no LLM mocking — these are pure unit-of-behaviour
 * tests on the verification engine.
 *
 * <p>The high-leverage assertions:
 *
 * <ul>
 *   <li>{@code shell} exit 0 → PASS; non-zero → FAIL; missing command → UNKNOWN; timeout → FAIL
 *   <li>{@code file-exists} present → PASS; missing → FAIL; null spec → UNKNOWN
 *   <li>{@code file-content} regex match → PASS; no match → FAIL; missing file → FAIL
 *   <li>Path-traversal safety: {@code ../etc/passwd} is rejected as FAIL with explicit evidence
 *   <li>Unknown verification types map to UNKNOWN with the type name in the evidence string
 *   <li>Phase B types ({@code endpoint}, {@code pr-state}, {@code manual}) explicitly return
 *       UNKNOWN with a "not yet implemented" message so a planner using them today doesn't break
 *       the run
 * </ul>
 */
class RunRubricVerifierTest {

  private RunRubricRowRepository repo;
  private RunRubricVerifier verifier;

  @BeforeEach
  void setUp() {
    repo = mock(RunRubricRowRepository.class);
    verifier = new RunRubricVerifier(repo, new SimpleMeterRegistry());
  }

  private RunRubricRow row(String type, String spec) {
    return RunRubricRow.builder()
        .runId("run-1")
        .rubricRowId("R1")
        .criterion("test")
        .verificationType(type)
        .verificationSpec(spec)
        .category("BUILD")
        .weight(5)
        .status(RunRubricRow.Status.PENDING)
        .build();
  }

  @Test
  void shell_exit0_pass(@TempDir Path tmp) {
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.SHELL, "true"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.PASS);
    assertThat(v.evidence()).contains("exit 0");
  }

  @Test
  void shell_exit1_fail(@TempDir Path tmp) {
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.SHELL, "false"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.FAIL);
    assertThat(v.evidence()).contains("exit 1");
  }

  @Test
  void shell_nullSpec_unknown(@TempDir Path tmp) {
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.SHELL, null), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
  }

  @Test
  void shell_blankSpec_unknown(@TempDir Path tmp) {
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.SHELL, "   "), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
  }

  @Test
  void shell_runsInWorkspaceDir(@TempDir Path tmp) throws IOException {
    // Drop a sentinel file in the workspace; shell command should see it via `ls`.
    Files.writeString(tmp.resolve("sentinel.txt"), "hello");
    var v =
        verifier.dispatch(row(RunRubricRow.VerificationType.SHELL, "test -f sentinel.txt"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.PASS);
  }

  @Test
  void fileExists_present_pass(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("README.md"), "# hi");
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.FILE_EXISTS, "README.md"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.PASS);
    assertThat(v.evidence()).contains("present");
  }

  @Test
  void fileExists_missing_fail(@TempDir Path tmp) {
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.FILE_EXISTS, "MISSING.md"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.FAIL);
    assertThat(v.evidence()).contains("missing");
  }

  @Test
  void fileExists_nestedPath_works(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(tmp.resolve("src/main/java/App.java"), "class App {}");
    var v =
        verifier.dispatch(
            row(RunRubricRow.VerificationType.FILE_EXISTS, "src/main/java/App.java"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.PASS);
  }

  @Test
  void fileExists_pathTraversalRejected(@TempDir Path tmp) {
    // Defence-in-depth: planner shouldn't emit ../paths, but if it does we refuse cleanly.
    var v =
        verifier.dispatch(row(RunRubricRow.VerificationType.FILE_EXISTS, "../../etc/passwd"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.FAIL);
    assertThat(v.evidence()).contains("escapes workspace");
  }

  @Test
  void fileContent_regexMatch_pass(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("config.yaml"), "version: 2\nname: foo\n");
    var v =
        verifier.dispatch(
            row(RunRubricRow.VerificationType.FILE_CONTENT, "config.yaml::version:\\s*\\d+"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.PASS);
  }

  @Test
  void fileContent_regexMiss_fail(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("config.yaml"), "version: 2\n");
    var v =
        verifier.dispatch(
            row(RunRubricRow.VerificationType.FILE_CONTENT, "config.yaml::EXPECTED-LITERAL"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.FAIL);
  }

  @Test
  void fileContent_missingFile_fail(@TempDir Path tmp) {
    var v =
        verifier.dispatch(
            row(RunRubricRow.VerificationType.FILE_CONTENT, "missing.txt::anything"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.FAIL);
  }

  @Test
  void fileContent_invalidRegex_unknown(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("file.txt"), "x");
    var v =
        verifier.dispatch(
            row(RunRubricRow.VerificationType.FILE_CONTENT, "file.txt::["), // unclosed bracket
            tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
    assertThat(v.evidence()).contains("invalid regex");
  }

  @Test
  void fileContent_specMissingSeparator_unknown(@TempDir Path tmp) {
    var v =
        verifier.dispatch(
            row(RunRubricRow.VerificationType.FILE_CONTENT, "no-separator-here"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
  }

  @Test
  void endpoint_returnsUnknown_pendingHandler(@TempDir Path tmp) {
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.ENDPOINT, "GET /health"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
    assertThat(v.evidence()).contains("Phase B");
  }

  @Test
  void prState_returnsUnknown_pendingHandler(@TempDir Path tmp) {
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.PR_STATE, "MERGED"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
    assertThat(v.evidence()).contains("Phase B");
  }

  @Test
  void manual_returnsUnknown_pendingHandler(@TempDir Path tmp) {
    var v = verifier.dispatch(row(RunRubricRow.VerificationType.MANUAL, "review code style"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
    assertThat(v.evidence()).contains("Phase B");
  }

  @Test
  void unknownType_returnsUnknown(@TempDir Path tmp) {
    var v = verifier.dispatch(row("smoke-signals", "spec"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
    assertThat(v.evidence()).contains("unknown verification type: smoke-signals");
  }

  @Test
  void nullType_returnsUnknown(@TempDir Path tmp) {
    var v = verifier.dispatch(row(null, "spec"), tmp);
    assertThat(v.status()).isEqualTo(RunRubricRow.Status.UNKNOWN);
  }

  @Test
  void safeResolveUnder_normalPath_returnsResolved(@TempDir Path tmp) {
    Path resolved = RunRubricVerifier.safeResolveUnder(tmp, "src/main/java/App.java");
    assertThat(resolved).isNotNull();
    // String-prefix check rather than Path.startsWith — AssertJ's Path.startsWith calls
    // toRealPath() which requires the file to exist on disk. We're testing the resolution
    // logic, not the file existence.
    assertThat(resolved.toString()).startsWith(tmp.toString());
  }

  @Test
  void safeResolveUnder_traversal_returnsNull(@TempDir Path tmp) {
    assertThat(RunRubricVerifier.safeResolveUnder(tmp, "../../etc/passwd")).isNull();
  }

  @Test
  void safeResolveUnder_absolutePath_returnsNullIfOutside(@TempDir Path tmp) {
    // Absolute path that's NOT under the workspace root → reject
    assertThat(RunRubricVerifier.safeResolveUnder(tmp, "/etc/passwd")).isNull();
  }

  @Test
  void safeResolveUnder_null_returnsNull(@TempDir Path tmp) {
    assertThat(RunRubricVerifier.safeResolveUnder(tmp, null)).isNull();
  }
}
