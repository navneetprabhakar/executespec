package com.navneet.executespec.smoketest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link WorkspaceTestRunner}'s most important contract: when there is no recognized test
 * runner, it returns empty so the scorer falls back to LLM coverage rather than fabricating a
 * count. The resolver short-circuits before the sandbox or containers are consulted, so this needs
 * no toolchain and no Spring context. The multi-framework parse path is pinned separately by {@link
 * TestOutputParserTest}. RL-2026-05-001.
 */
class WorkspaceTestRunnerTest {

  @Test
  void noRecognizedRunner_returnsEmpty(@TempDir Path emptyWorkspace) {
    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(new TestCommandResolver(), null, null, null);
    assertTrue(runner.runTests("run-1", emptyWorkspace).isEmpty());
  }

  @Test
  void nullWorkspace_returnsEmpty() {
    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(new TestCommandResolver(), null, null, null);
    assertTrue(runner.runTests("run-1", null).isEmpty());
  }
}
