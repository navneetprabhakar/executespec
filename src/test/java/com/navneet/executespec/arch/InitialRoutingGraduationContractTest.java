package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class InitialRoutingGraduationContractTest {

  @Test
  void migrationEnablesOnlyTheEvidenceGatedInitialRoute() throws Exception {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/migration/V267__enable_initial_worker_routing.sql"));
    assertThat(sql).contains("execution.initial-routing.enabled").contains("flag_value = 'true'");
    assertThat(sql).doesNotContain("INSERT INTO");
  }
}
