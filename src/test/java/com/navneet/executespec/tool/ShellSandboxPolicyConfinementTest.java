package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.security.guardrail.GuardrailLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Executable-origin (RR-833), workspace-confinement (RR-118) and Docker-escape (RR-120) rules.
 *
 * <p>These pin behaviour the older {@code ShellSandboxPolicyTest} never covered: it asserted that
 * {@code ./compile.sh} is blocked, but never that a worker-written file NAMED like an approved tool
 * is blocked — which was the actual bypass.
 */
@DisplayName("ShellSandboxPolicy — confinement")
class ShellSandboxPolicyConfinementTest {

  private final ShellSandboxPolicy policy = new ShellSandboxPolicy();

  @Nested
  @DisplayName("RR-833 — executable origin")
  class ExecutableOrigin {

    @Test
    @DisplayName("a worker-written ./git impostor is blocked")
    void relativeImpostorBlocked() {
      var result = policy.validate("./git status", "/ws");
      assertThat(result.permitted()).isFalse();
      assertThat(result.reason()).contains("path-qualified");
    }

    @Test
    @DisplayName("an absolute impostor outside a trusted bin dir is blocked")
    void absoluteImpostorBlocked() {
      assertThat(policy.validate("/tmp/npm install", "/ws").permitted()).isFalse();
      assertThat(policy.validate("/ws/node build.js", "/ws").permitted()).isFalse();
      assertThat(policy.validate("bin/java -version", "/ws").permitted()).isFalse();
      assertThat(policy.validate("../cat file.txt", "/ws").permitted()).isFalse();
    }

    @Test
    @DisplayName("real system executables keep working")
    void trustedSystemPathsAllowed() {
      assertThat(policy.validate("/usr/bin/mvn compile", "/ws").permitted()).isTrue();
      assertThat(policy.validate("/bin/ls -la", "/ws").permitted()).isTrue();
      assertThat(policy.validate("/opt/homebrew/bin/node app.js", "/ws").permitted()).isTrue();
    }

    @Test
    @DisplayName("governed build wrappers and local Python venv tools keep working")
    void governedWorkspaceExecutables() {
      assertThat(policy.validate("./gradlew -q assemble -x test", "/ws").permitted()).isTrue();
      assertThat(policy.validate("./mvnw -q compile", "/ws").permitted()).isTrue();
      assertThat(policy.validate(".venv/bin/python -m pytest", "/ws").permitted()).isTrue();
      assertThat(policy.validate("./.venv/bin/pip3.12 --version", "/ws").permitted()).isTrue();
      assertThat(policy.validate("venv/bin/python3.12 -m compileall .", "/ws").permitted())
          .isTrue();
      assertThat(policy.validate("./compile.sh", "/ws").permitted()).isFalse();
      assertThat(policy.validate("./executable --help", "/ws").permitted()).isFalse();
      assertThat(policy.validate(".venv/bin/git status", "/ws").permitted()).isFalse();
      assertThat(policy.validate("backend/.venv/bin/pip install fastapi", "/ws").permitted())
          .isFalse();
    }

    @Test
    @DisplayName("an impostor is blocked at every guardrail level")
    void blockedAtEveryLevel() {
      for (GuardrailLevel level : GuardrailLevel.values()) {
        assertThat(policy.validate("./npm run build", "/ws", level).permitted())
            .as("level %s", level)
            .isFalse();
      }
    }
  }

  @Nested
  @DisplayName("RR-118 — workspace confinement")
  class WorkspaceConfinement {

    @Test
    @DisplayName("host credential and process reads are blocked")
    void hostReadsBlocked() {
      assertThat(policy.validate("cat /proc/self/environ", "/ws").permitted()).isFalse();
      assertThat(policy.validate("find / -maxdepth 2", "/ws").permitted()).isFalse();
      assertThat(policy.validate("cat /home/deploy/.ssh/id_rsa", "/ws").permitted()).isFalse();
      assertThat(policy.validate("cat ~/.aws/credentials", "/ws").permitted()).isFalse();
      assertThat(policy.validate("cp /root/token.txt out.txt", "/ws").permitted()).isFalse();
    }

    @Test
    @DisplayName("a sibling run's workspace is blocked")
    void siblingWorkspaceBlocked() {
      var result =
          policy.validate(
              "cp /opt/app/workspace/other-run/secret.txt .", "/opt/app/workspace/this-run");
      assertThat(result.permitted()).isFalse();
    }

    @Test
    @DisplayName("the run's own absolute workspace path stays allowed")
    void ownWorkspaceAllowed() {
      assertThat(
              policy
                  .validate(
                      "chmod +x /opt/app/workspace/abc-123/compile.sh",
                      "/opt/app/workspace/abc-123")
                  .permitted())
          .isTrue();
      assertThat(
              policy
                  .validate("cat /opt/app/workspace/abc-123/pom.xml", "/opt/app/workspace/abc-123")
                  .permitted())
          .isTrue();
    }

    @Test
    @DisplayName("ordinary relative work and allowlisted roots are untouched")
    void relativeAndAllowlistedRootsAllowed() {
      assertThat(policy.validate("mvn -q -DskipTests compile", "/ws").permitted()).isTrue();
      assertThat(policy.validate("npm run build --if-present", "/ws").permitted()).isTrue();
      assertThat(policy.validate("go build ./...", "/ws").permitted()).isTrue();
      assertThat(policy.validate("python3 -m compileall -q .", "/ws").permitted()).isTrue();
      assertThat(policy.validate("cat out.txt > /dev/null", "/ws").permitted()).isTrue();
      assertThat(policy.validate("cp report.txt /tmp/report.txt", "/ws").permitted()).isTrue();
      // Cleanroom-container workdirs — WorkspaceTestRunner validates container commands too.
      assertThat(policy.validate("python3 -m pytest /testbed/tests", "/ws").permitted()).isTrue();
    }
  }

  @Nested
  @DisplayName("RR-120 — docker")
  class Docker {

    @Test
    @DisplayName("docker compose is blocked (a compose file declares its own escape)")
    void composeBlocked() {
      var result = policy.validate("docker compose up -d", "/ws");
      assertThat(result.permitted()).isFalse();
      assertThat(result.reason()).contains("compose");
    }

    @Test
    @DisplayName("read-only docker subcommands still pass")
    void readOnlySubcommandsAllowed() {
      assertThat(policy.validate("docker ps", "/ws").permitted()).isTrue();
      assertThat(policy.validate("docker images", "/ws").permitted()).isTrue();
    }
  }
}
