package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TaskRoutePersistenceMigrationContractTest {

  @Test
  void v357PersistsTheHistoricalTaskRouteExplanation() throws Exception {
    String sql =
        Files.readString(
                Path.of(
                    "src/main/resources/db/migration/"
                        + "V357__persist_task_route_explanation.sql"))
            .replaceAll("\\s+", " ")
            .trim();

    assertThat(sql)
        .contains("ADD COLUMN IF NOT EXISTS routing_tier VARCHAR(24)")
        .contains("ADD COLUMN IF NOT EXISTS routing_task_category VARCHAR(48)")
        .contains("ADD COLUMN IF NOT EXISTS routing_source VARCHAR(48)")
        .contains("never inferred on replay");
  }
}
