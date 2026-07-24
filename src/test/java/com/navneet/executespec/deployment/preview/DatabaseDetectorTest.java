package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseDetectorTest {

  private final DatabaseDetector detector = new DatabaseDetector();

  @Test
  void fastApiSqlAlchemyWithSqliteUrlDoesNotInjectPostgres(@TempDir Path workspace)
      throws Exception {
    Files.writeString(workspace.resolve("requirements.txt"), "fastapi\nsqlalchemy>=2\n");
    Files.createDirectories(workspace.resolve("app"));
    Files.writeString(
        workspace.resolve("app/database.py"),
        "DATABASE_URL = os.getenv('SQLITE_URL', 'sqlite:///./jobs.db')\n");

    assertThat(detector.detect(workspace)).isNull();
  }

  @Test
  void genericSqlAlchemyWithoutEmbeddedEvidenceKeepsPostgresFallback(@TempDir Path workspace)
      throws Exception {
    Files.writeString(workspace.resolve("requirements.txt"), "fastapi\nsqlalchemy>=2\n");

    assertThat(detector.detect(workspace)).isEqualTo(DatabaseType.POSTGRESQL);
  }
}
