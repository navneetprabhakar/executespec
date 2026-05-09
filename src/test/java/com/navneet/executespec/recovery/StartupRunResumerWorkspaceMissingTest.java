package com.navneet.executespec.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * B-22 (audit, 2026-05-09) regression coverage. The salvage path used to mark interrupted runs
 * COMPLETE based purely on the per-task terminal-status check, even when the workspace dir had
 * been wiped during the interruption. This left the user with a "complete" run whose preview /
 * chat / file downloads all 404'd. The hardened resumer adds {@code workspaceMissing(runId)} as
 * a precheck.
 */
@DisplayName("StartupRunResumer — workspace-gone precheck")
class StartupRunResumerWorkspaceMissingTest {

  @TempDir Path tempDir;

  private StartupRunResumer resumer;

  @BeforeEach
  void setUp() {
    // Construct without Spring — Lombok @RequiredArgsConstructor takes 4 args; pass nulls for
    // collaborators we don't exercise (the test only calls the private workspaceMissing helper).
    resumer = new StartupRunResumer(null, null, null, null);
    ReflectionTestUtils.setField(resumer, "workspaceRoot", tempDir.toString());
  }

  @Test
  @DisplayName("returns true when run dir doesn't exist")
  void workspaceGone() throws Exception {
    Method m = StartupRunResumer.class.getDeclaredMethod("workspaceMissing", String.class);
    m.setAccessible(true);
    boolean missing = (Boolean) m.invoke(resumer, "ghost-run");
    assertThat(missing).isTrue();
  }

  @Test
  @DisplayName("returns false when run dir exists")
  void workspacePresent() throws Exception {
    Files.createDirectories(tempDir.resolve("alive-run"));
    Method m = StartupRunResumer.class.getDeclaredMethod("workspaceMissing", String.class);
    m.setAccessible(true);
    boolean missing = (Boolean) m.invoke(resumer, "alive-run");
    assertThat(missing).isFalse();
  }

  @Test
  @DisplayName("returns false (degrade gracefully) when agent.workspace is unset")
  void noWorkspaceRootConfigured() throws Exception {
    ReflectionTestUtils.setField(resumer, "workspaceRoot", "");
    Method m = StartupRunResumer.class.getDeclaredMethod("workspaceMissing", String.class);
    m.setAccessible(true);
    boolean missing = (Boolean) m.invoke(resumer, "any-run");
    assertThat(missing).isFalse();
  }
}
