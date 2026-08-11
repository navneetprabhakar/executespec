package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DeploymentPointerProtocolTest {

  @Test
  void activationIsReconciledByExactTupleBeforeCasPublicationAndRuntimeIsProcessBound()
      throws Exception {
    String workflow = Files.readString(Path.of(".github/workflows/deploy.yml"));
    String publisher = Files.readString(Path.of("scripts/publish-release-pointer.sh"));

    int deploy = workflow.indexOf("- name: Deploy JAR to GCE");
    int reconcile = workflow.indexOf("- name: Reconcile host release candidate");
    int health = workflow.indexOf("- name: Health check");
    int processAttest = workflow.indexOf("- name: Verify running JVM release identity");
    int publish = workflow.indexOf("- name: Publish verified live release pointer");

    assertThat(deploy).isGreaterThanOrEqualTo(0);
    assertThat(reconcile).isGreaterThan(deploy);
    assertThat(health).isGreaterThan(reconcile);
    assertThat(processAttest).isGreaterThan(health);
    assertThat(publish).isGreaterThan(processAttest);
    assertThat(workflow)
        .contains("id: activate_release")
        .contains("continue-on-error: true")
        .contains("if: always()")
        .contains("source_sha=${{ env.TARGET_SHA }}")
        .contains("artifact_sha256=${{ env.JAR_SHA256 }}")
        .contains("attempt_id=${{ env.DEPLOY_ATTEMPT_ID }}")
        .contains("/opt/app/RUNNING_RELEASE")
        .contains("bash scripts/publish-release-pointer.sh")
        .contains("/proc/\\$PID/environ")
        .doesNotContain("Revert CURRENT_SHA on failed deploy")
        .doesNotContain("CURRENT_SHA restored to");
    assertThat(publisher)
        .contains("x-goog-if-generation-match:$MATCH_GENERATION")
        .contains("refusing stale pointer write");
  }

  @Test
  void localDeploymentUsesTheSameCandidateReconciliationProtocol() throws Exception {
    String script = Files.readString(Path.of("scripts/deploy-local.sh"));

    int upload = script.indexOf("# ── 2. Upload immutable candidate");
    int activate = script.indexOf("# ── 4. Deploy:");
    int reconcile = script.indexOf("# The SSH response can be lost");
    int health = script.indexOf("# ── 5. Wait + health check");
    int processAttest = script.indexOf("EXPECTED_PROCESS=", health);
    int publish = script.indexOf("bash scripts/publish-release-pointer.sh", processAttest);

    assertThat(upload).isGreaterThanOrEqualTo(0);
    assertThat(activate).isGreaterThan(upload);
    assertThat(reconcile).isGreaterThan(activate);
    assertThat(health).isGreaterThan(reconcile);
    assertThat(processAttest).isGreaterThan(health);
    assertThat(publish).isGreaterThan(processAttest);
    assertThat(script.substring(upload, activate)).doesNotContain("cp - \"$BUCKET/CURRENT_SHA\"");
    assertThat(script)
        .contains("RUNNING_RELEASE")
        .contains("artifact_sha256=$JAR_SHA256")
        .contains("attempt_id=$DEPLOY_ATTEMPT_ID")
        .contains("/proc/\\$PID/environ");
  }
}
