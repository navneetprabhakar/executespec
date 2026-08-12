package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Source-level pin for the BUILD run-end physical verification boundary. */
class BuildVerificationCommandArchitectureTest {

  private static final Path SOURCE = Path.of("src/main/java/com/navneet/executespec");

  @Test
  void orchestratorCannotCallPathOrRunIdOnlyRunEndVerificationAdapters() throws Exception {
    String source = read("integration/RunOrchestrator.java");

    assertThat(source)
        .doesNotContain("cliSmokeRunner.run(runId")
        .doesNotContain("runBreakingChangeDetector.detect(runId")
        .doesNotContain("runStandardsEnforcement(runId")
        .doesNotContain("validationKickoff.generateValidationReport(\n              runId")
        .contains("acceptedVerification.execute(")
        .contains("cliSmokeRunner::run")
        .contains(
            "validationKickoff.generateValidationReport(\n                      disposableContext");
    assertThat(read("integration/RunEnterpriseVerificationService.java"))
        .contains("acceptedVerification.execute(")
        .contains("standards.runStandardsEnforcement(disposable")
        .contains("breakingChanges.detect(disposable");
  }

  @Test
  void generationBoundAdaptersUseCapabilityRouteNotOrdinaryRunRoute() throws Exception {
    assertThat(read("smoketest/CliSmokeRunner.java"))
        .contains("executeVerification(")
        .contains("VERIFICATION_SANDBOX_AUTHORITY_REQUIRED");
    assertThat(read("standards/enforcement/lint/LintProcessRunner.java"))
        .contains("executeVerification(")
        .contains("VERIFICATION_SANDBOX_AUTHORITY_REQUIRED");
    assertThat(read("contracts/breaking/CliBreakingChangeRunner.java"))
        .contains("executeVerification(")
        .contains("VERIFICATION_SANDBOX_AUTHORITY_REQUIRED");
    assertThat(read("smoketest/WorkspaceTestRunner.java"))
        .contains("executeVerificationShell(")
        .contains("verificationAuthority == null");
  }

  @Test
  void physicalVerificationDispatchIsJournaledBeforeRuntimeExecAndBoundToPassingReceipt()
      throws Exception {
    String router = read("sandboxroute/VerificationSandboxRoutingService.java");
    assertThat(router.indexOf("commandJournal.admit("))
        .isPositive()
        .isLessThan(router.indexOf("commandJournal.markDispatched("));
    assertThat(router.indexOf("commandJournal.markDispatched("))
        .isLessThan(router.indexOf("dispatcher.exec("));
    assertThat(router.indexOf("dispatcher.exec("))
        .isLessThan(router.indexOf("commandJournal.settle("));

    assertThat(read("workspacegeneration/WorkspaceGenerationLifecycle.java"))
        .contains("commandJournal.requireSuccessfulReceipts(")
        .contains(
            ".commandReceiptIds(\n"
                + "                WorkspaceGenerationEvidenceCodec.write(objectMapper,"
                + " commandReceiptIds))");
    assertThat(read("workspacegeneration/WorkspaceGenerationEvidenceCodec.java"))
        .contains("static String write(ObjectMapper objectMapper, Object value)")
        .contains("objectMapper.writeValueAsString(value)");
    assertThat(read("sandbox/SandboxCommandRecoveryService.java"))
        .contains("recoverStaleCommands")
        .doesNotContain("SUCCEEDED");
  }

  private static String read(String relative) throws Exception {
    return Files.readString(SOURCE.resolve(relative));
  }
}
