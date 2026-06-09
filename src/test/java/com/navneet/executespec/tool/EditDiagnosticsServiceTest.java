package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B1 (RL-2026-06, vix-leverage W1.1) — pins post-edit syntax diagnostics: the pure parser/dispatch,
 * the flag + language gates (no subprocess when off/unsupported), and the real host-{@code python3}
 * path (broken .py → diagnostic; clean .py → empty). Subprocess cases are skipped where python3 is
 * absent so the suite stays green in a minimal CI image.
 */
@DisplayName("EditDiagnosticsService (B1 — post-edit syntax diagnostics)")
class EditDiagnosticsServiceTest {

  @TempDir Path workspace;
  private FeatureFlagService flags;
  private EditDiagnosticsService svc;

  @BeforeEach
  void setUp() {
    flags = mock(FeatureFlagService.class);
    svc = new EditDiagnosticsService(flags, null, null); // no container stores → host python3
  }

  private ExecutionContext ctx() {
    return new ExecutionContext("run-b1", "T1", workspace);
  }

  private void flagOn() {
    when(flags.isEnabled(EditDiagnosticsService.FLAG)).thenReturn(true);
  }

  @Test
  @DisplayName("formatPyCompile extracts the File/line locus + the *Error message")
  void formatExtracts() {
    String out =
        "  File \"app.py\", line 12\n    def f(:\n          ^\nSyntaxError: invalid syntax\n";
    String d = EditDiagnosticsService.formatPyCompile(out, "app.py");
    assertThat(d).contains("app.py").contains("line 12").contains("SyntaxError: invalid syntax");
  }

  @Test
  @DisplayName("formatPyCompile on blank/clean output → empty string")
  void formatClean() {
    assertThat(EditDiagnosticsService.formatPyCompile("", "app.py")).isEmpty();
    assertThat(EditDiagnosticsService.formatPyCompile("\n\n", "app.py")).isEmpty();
  }

  @Test
  @DisplayName("languageOf: .py → python, others → null (unsupported skipped)")
  void langDispatch() {
    assertThat(EditDiagnosticsService.languageOf("a/b/c.py")).isEqualTo("python");
    assertThat(EditDiagnosticsService.languageOf("Foo.java")).isNull();
    assertThat(EditDiagnosticsService.languageOf("x.ts")).isNull();
  }

  @Test
  @DisplayName("flag OFF → empty (byte-identical to today; no subprocess)")
  void flagOff() {
    when(flags.isEnabled(EditDiagnosticsService.FLAG)).thenReturn(false);
    assertThat(svc.diagnose(ctx(), "anything.py")).isEmpty();
  }

  @Test
  @DisplayName("non-python file → empty even when flag ON")
  void nonPython() {
    flagOn();
    assertThat(svc.diagnose(ctx(), "Foo.java")).isEmpty();
  }

  @Test
  @DisplayName("flag ON + broken .py → diagnostic naming the file")
  void brokenPy() throws IOException {
    assumeTrue(pythonAvailable(), "python3 not on PATH");
    flagOn();
    Files.writeString(workspace.resolve("bad.py"), "def f(:\n    pass\n");
    Optional<String> d = svc.diagnose(ctx(), "bad.py");
    assertThat(d).isPresent();
    assertThat(d.get()).contains("bad.py").containsIgnoringCase("error");
  }

  @Test
  @DisplayName("flag ON + valid .py → empty (clean compile)")
  void cleanPy() throws IOException {
    assumeTrue(pythonAvailable(), "python3 not on PATH");
    flagOn();
    Files.writeString(workspace.resolve("ok.py"), "def f():\n    return 1\n");
    assertThat(svc.diagnose(ctx(), "ok.py")).isEmpty();
  }

  private static boolean pythonAvailable() {
    try {
      Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
