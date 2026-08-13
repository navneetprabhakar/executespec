package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Source-level prohibition against reintroducing best-effort BUILD commit/publication paths. */
class PublicationPreparationArchitectureTest {

  private static final Path SOURCE = Path.of("src/main/java/com/navneet/executespec");

  @Test
  void sharedBuildUsesPreparedObjectsAndJournalsEveryIrreversibleGitBoundary() throws Exception {
    String orchestrator = read("integration/RunOrchestrator.java");
    assertThat(orchestrator)
        .doesNotContain("private final WorkspaceGitCommitService ")
        .contains("LegacyWorkspaceGitCommitService")
        .contains("BuildProtocolVersion.LEGACY_V1");

    String scm = read("integration/scm/ScmPushService.java");
    assertThat(scm)
        .contains("repositoryProvisionDispatchService.provision(")
        .contains("publicationDispatchService.push(")
        .contains("gitTool.pushPreparedCommit(")
        .contains("PreparedRepository::provisionRequired");
    assertThat(read("tool/GitTool.java"))
        .contains("pushPreparedCommit(")
        .contains("preparedCommitId + \":\" + destination");
  }

  @Test
  void preparationIsGenerationBoundNonExecutingAndRecoveryNeverInventsSuccess() throws Exception {
    String preparation = read("integration/scm/PublicationPreparationService.java");
    assertThat(preparation)
        .contains("RunWorkspaceAuthorityService.Authority.PUBLICATION_PREPARE")
        .contains("gitObjects.prepareCommit(")
        .contains("PUBLICATION_PREPARED_OBJECT_IDENTITY_MISMATCH")
        .doesNotContain("ProcessBuilder")
        .doesNotContain("git add")
        .doesNotContain("git commit");

    String recovery = read("integration/scm/PublicationRecoveryService.java");
    assertThat(recovery).doesNotContain("PUBLISHED").doesNotContain("PROVISIONED");

    String effects = read("integration/scm/PublicationExternalEffectStore.java");
    assertThat(effects)
        .contains("SET state='FAILED'")
        .contains("WHERE state='ADMITTED' AND admitted_at < ?")
        .contains("SET state='INDETERMINATE'")
        .contains("WHERE state='DISPATCHED' AND updated_at < ?")
        .contains("authority.owner_token=? AND authority.authority_type='PUBLICATION'")
        .contains("authority.lease_expires_at >= NOW()")
        .contains("!\"FAILED\".equals(prior.getFirst().state())");
  }

  private static String read(String relative) throws Exception {
    return Files.readString(SOURCE.resolve(relative));
  }
}
