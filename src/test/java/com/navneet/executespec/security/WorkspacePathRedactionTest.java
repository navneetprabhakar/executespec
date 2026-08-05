package com.navneet.executespec.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.config.AgentProperties;

import org.junit.jupiter.api.Test;

class WorkspacePathRedactionTest {

  private static WorkspacePathRedaction withWorkspace(String workspace) {
    AgentProperties props = new AgentProperties();
    props.setWorkspace(workspace);
    return new WorkspacePathRedaction(props);
  }

  @Test
  void linuxDeployRootIsRedactedFromAFailureReason() {
    WorkspacePathRedaction redaction = withWorkspace(null);

    String redacted =
        redaction.redact(
            "Build failed in /opt/app/workspace/x/y because the module could not compile");

    assertThat(redacted).doesNotContain("/opt/app/workspace/x/y");
    assertThat(redacted).contains("[workspace path redacted]");
    assertThat(redacted).contains("because the module could not compile");
  }

  @Test
  void configuredWorkspaceRootIsRedactedWhereverItLives() {
    WorkspacePathRedaction redaction = withWorkspace("/srv/executespec/workspaces");

    String redacted =
        redaction.redact("missing file /srv/executespec/workspaces/run-1/src/main.py");

    assertThat(redacted).doesNotContain("/srv/executespec/workspaces");
    assertThat(redacted).contains("[workspace path redacted]");
  }

  @Test
  void redactionInsideAJsonStringCannotSwallowTheClosingQuote() {
    WorkspacePathRedaction redaction = withWorkspace(null);

    String redacted =
        redaction.redact("{\"reason\":\"tests missing under /opt/app/workspace/x/y\",\"ok\":true}");

    assertThat(redacted)
        .isEqualTo("{\"reason\":\"tests missing under [workspace path redacted]\",\"ok\":true}");
  }

  @Test
  void knownUserAndTempRootsRemainCovered() {
    String redacted =
        WorkspacePathRedaction.redactKnownRoots(
            "at /Users/dev/project and /private/tmp/scratch and C:\\Users\\dev\\proj", "[gone]");

    assertThat(redacted).doesNotContain("/Users/dev", "/private/tmp/scratch", "C:\\Users\\dev");
    assertThat(redacted).contains("[gone]");
  }

  @Test
  void nullAndPathFreeTextPassThroughUnchanged() {
    WorkspacePathRedaction redaction = withWorkspace(null);

    assertThat(redaction.redact(null)).isNull();
    assertThat(redaction.redact("no paths here")).isEqualTo("no paths here");
  }
}
