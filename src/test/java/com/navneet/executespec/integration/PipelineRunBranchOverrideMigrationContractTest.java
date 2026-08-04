package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class PipelineRunBranchOverrideMigrationContractTest {

  @Test
  void migrationAndEntityPersistTheExactExistingBranchTarget() throws Exception {
    String migration =
        Files.readString(
            Path.of("src/main/resources/db/migration/V373__persist_run_branch_override.sql"));

    assertThat(migration).contains("ADD COLUMN IF NOT EXISTS branch_override VARCHAR(200)");
    assertThat(migration).contains("ADD COLUMN IF NOT EXISTS checkout_branch VARCHAR(200)");
    assertThat(migration)
        .contains("ADD COLUMN IF NOT EXISTS publication_repo_full_name VARCHAR(255)");
    assertThat(migration).contains("ADD COLUMN IF NOT EXISTS publication_pr_number INTEGER");
    assertThat(migration).contains("ADD COLUMN IF NOT EXISTS publication_head_sha VARCHAR(40)");
    assertThat(migration).contains("chk_pipeline_run_publication_pr_identity");
    assertThat(migration).contains("checkout_branch IS NOT NULL");
    assertThat(migration).contains("checkout_branch = branch_override");
    assertThat(migration).contains("publication_pr_number > 0");
    assertThat(migration).contains("publication_head_sha ~ '^[0-9a-fA-F]{40}$'");
    assertThat(migration).contains("trg_pipeline_run_branch_intent_immutable");
    assertThat(PipelineRun.class.getDeclaredField("branchOverride")).isNotNull();
    assertThat(PipelineRun.class.getDeclaredField("checkoutBranch")).isNotNull();
    assertThat(PipelineRun.class.getDeclaredField("publicationRepoFullName")).isNotNull();
    assertThat(PipelineRun.class.getDeclaredField("publicationPrNumber")).isNotNull();
    assertThat(PipelineRun.class.getDeclaredField("publicationHeadSha")).isNotNull();
  }
}
