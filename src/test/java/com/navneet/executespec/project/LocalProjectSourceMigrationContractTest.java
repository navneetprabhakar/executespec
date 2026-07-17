package com.navneet.executespec.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class LocalProjectSourceMigrationContractTest {

  @Test
  void v278IsAdditivePathFreeAndBackfillsExistingGithubRows() throws Exception {
    String sql =
        Files.readString(
                Path.of("src/main/resources/db/migration/V278__add_local_project_sources.sql"))
            .toLowerCase(Locale.ROOT);

    assertThat(sql)
        .contains("source_type", "local_folder_id", "local_installation_id")
        .contains("source_lease_expires_at", "where source_type = 'scm'")
        .contains("github_repo_full_name drop not null")
        .contains("unique index if not exists ux_project_repo_user_local_folder")
        .doesNotContain("absolute_path", "local_path", "filesystem_path");
  }
}
