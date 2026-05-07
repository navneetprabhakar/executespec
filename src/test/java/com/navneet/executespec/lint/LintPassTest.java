package com.navneet.executespec.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link LintPass}. Tests verify:
 *
 * <ol>
 *   <li>Detection logic — which formatters are picked from filesystem markers.
 *   <li>Skip semantics — empty / missing workspace returns skipped report.
 *   <li>Report shape — summary, changedAnything, skipped flags.
 *   <li>Tool-failure containment — a missing binary fails the tool, not the run.
 * </ol>
 *
 * <p>Tests deliberately avoid asserting that the actual formatter ran successfully — CI hosts may
 * or may not have prettier / mvn / black / gofmt / cargo on PATH. The contract under test is that
 * LintPass detects the tool, dispatches it, captures the outcome, and never throws.
 */
@DisplayName("LintPass")
class LintPassTest {

  private final LintPass lintPass = new LintPass();

  @Test
  @DisplayName("missing workspace → skipped report")
  void missingWorkspaceSkipped(@TempDir Path tmp) {
    Path nonexistent = tmp.resolve("does-not-exist");
    LintPass.LintReport report = lintPass.run(nonexistent);
    assertTrue(report.skipped());
    assertEquals("workspace missing", report.skipReason());
    assertFalse(report.changedAnything());
    assertEquals(0, report.totalFilesChanged());
    assertEquals("skipped (workspace missing)", report.summary());
  }

  @Test
  @DisplayName("null workspace → skipped report")
  void nullWorkspaceSkipped() {
    LintPass.LintReport report = lintPass.run(null);
    assertTrue(report.skipped());
  }

  @Test
  @DisplayName("empty directory → skipped (no formatter detected)")
  void emptyWorkspaceSkipped(@TempDir Path tmp) {
    LintPass.LintReport report = lintPass.run(tmp);
    assertTrue(report.skipped());
    assertEquals("no formatter detected", report.skipReason());
    assertTrue(report.results().isEmpty());
  }

  @Test
  @DisplayName("workspace with package.json → prettier detected (run attempt recorded)")
  void packageJsonDetectsPrettier(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("package.json"), "{\"name\":\"x\",\"version\":\"1.0.0\"}");
    LintPass.LintReport report = lintPass.run(tmp);
    // Detection happened (not skipped), prettier is in the result list — outcome
    // depends on whether `npx` is on the test host's PATH.
    assertFalse(report.skipped());
    assertTrue(
        report.results().stream().anyMatch(r -> r.tool() == LintPass.Tool.PRETTIER),
        "expected PRETTIER in results, got: " + toolNames(report.results()));
  }

  @Test
  @DisplayName("workspace with pom.xml → spotless-maven detected")
  void pomDetectsSpotlessMaven(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("pom.xml"), "<project/>");
    LintPass.LintReport report = lintPass.run(tmp);
    assertFalse(report.skipped());
    assertTrue(report.results().stream().anyMatch(r -> r.tool() == LintPass.Tool.SPOTLESS_MAVEN));
  }

  @Test
  @DisplayName("workspace with go.mod → gofmt detected")
  void goModDetectsGofmt(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("go.mod"), "module example.com/x\n");
    LintPass.LintReport report = lintPass.run(tmp);
    assertFalse(report.skipped());
    assertTrue(report.results().stream().anyMatch(r -> r.tool() == LintPass.Tool.GOFMT));
  }

  @Test
  @DisplayName("workspace with Cargo.toml → rustfmt detected")
  void cargoTomlDetectsRustfmt(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("Cargo.toml"), "[package]\nname = \"x\"\n");
    LintPass.LintReport report = lintPass.run(tmp);
    assertFalse(report.skipped());
    assertTrue(report.results().stream().anyMatch(r -> r.tool() == LintPass.Tool.RUSTFMT));
  }

  @Test
  @DisplayName("workspace with pyproject.toml → black detected")
  void pyprojectDetectsBlack(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("pyproject.toml"), "[tool.poetry]\nname = \"x\"\n");
    LintPass.LintReport report = lintPass.run(tmp);
    assertFalse(report.skipped());
    assertTrue(report.results().stream().anyMatch(r -> r.tool() == LintPass.Tool.BLACK));
  }

  @Test
  @DisplayName("workspace with multiple markers → multiple tools detected")
  void multipleMarkersDetectAllTools(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("package.json"), "{\"name\":\"x\"}");
    Files.writeString(tmp.resolve("pom.xml"), "<project/>");
    Files.writeString(tmp.resolve("go.mod"), "module x\n");
    LintPass.LintReport report = lintPass.run(tmp);
    assertFalse(report.skipped());
    long detected = report.results().stream().map(LintPass.ToolResult::tool).distinct().count();
    assertTrue(detected >= 3, "expected ≥3 tools, got " + detected);
  }

  @Test
  @DisplayName("LintReport.skipped() factory builds inert report")
  void skippedFactory() {
    LintPass.LintReport r = LintPass.LintReport.skipped("test reason");
    assertTrue(r.skipped());
    assertEquals("test reason", r.skipReason());
    assertEquals(0, r.totalFilesChanged());
    assertFalse(r.changedAnything());
    assertEquals("skipped (test reason)", r.summary());
  }

  @Test
  @DisplayName("ToolResult records elapsed duration + filesChanged + error")
  void toolResultShape() {
    LintPass.ToolResult ok =
        new LintPass.ToolResult(LintPass.Tool.PRETTIER, true, null, Duration.ofMillis(120), 5);
    assertTrue(ok.success());
    assertEquals(5, ok.filesChanged());
    assertEquals(120, ok.elapsed().toMillis());
    LintPass.ToolResult fail =
        new LintPass.ToolResult(
            LintPass.Tool.SPOTLESS_MAVEN, false, "exit 1", Duration.ofMillis(50), 0);
    assertFalse(fail.success());
    assertEquals("exit 1", fail.error());
  }

  @Test
  @DisplayName("summary lists only successful tools")
  void summaryListsOnlySuccessfulTools() {
    var ok = new LintPass.ToolResult(LintPass.Tool.PRETTIER, true, null, Duration.ofMillis(200), 3);
    var fail =
        new LintPass.ToolResult(
            LintPass.Tool.SPOTLESS_MAVEN, false, "exit 1", Duration.ofMillis(50), 0);
    var report = new LintPass.LintReport(List.of(ok, fail), 3, false, null);
    String s = report.summary();
    assertNotNull(s);
    assertTrue(s.contains("prettier"), "summary should list prettier: " + s);
    assertFalse(s.contains("spotless-maven"), "summary should omit failed tool: " + s);
    assertTrue(s.contains("3 file"), "summary should report file count: " + s);
  }

  @Test
  @DisplayName("changedAnything() true only when totalFilesChanged > 0 + not skipped")
  void changedAnythingFlag() {
    var changed = new LintPass.LintReport(List.of(), 5, false, null);
    var unchanged = new LintPass.LintReport(List.of(), 0, false, null);
    var skipped = LintPass.LintReport.skipped("test");
    assertTrue(changed.changedAnything());
    assertFalse(unchanged.changedAnything());
    assertFalse(skipped.changedAnything());
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private static String toolNames(List<LintPass.ToolResult> results) {
    StringBuilder sb = new StringBuilder("[");
    for (LintPass.ToolResult r : results) {
      if (sb.length() > 1) sb.append(", ");
      sb.append(r.tool());
    }
    return sb.append(']').toString();
  }
}
