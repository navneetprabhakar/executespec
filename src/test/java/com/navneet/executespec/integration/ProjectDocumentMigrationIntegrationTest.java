package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.navneet.executespec.project.rag.DeterministicDocumentMalwareScanner;
import com.navneet.executespec.project.rag.DocumentMalwareScanner;
import com.navneet.executespec.project.rag.DocumentTypeInspector;
import com.navneet.executespec.project.rag.ProjectDocumentProperties;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V287 -> V288 binary-authority, tenant, cascade, and restart rehearsal. */
@Tag("integration")
@Testcontainers
class ProjectDocumentMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("project_document_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradePreservesLegacyRowsAndNewBinarySourcesSurviveRestartWithoutTenantBleed() {
    String schema = "project_document_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String legacyId = UUID.randomUUID().toString();
    String firstId = UUID.randomUUID().toString();
    String secondId = UUID.randomUUID().toString();
    byte[] approvedBytes = new byte[] {'U', 'T', 'F', '-', '8', 0x0A, 0x00, (byte) 0xFF};

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("287")).migrate();
      seedTenants(jdbc);
      jdbc.update(
          """
          INSERT INTO project_document
            (id, project_id, path_relative, source, filename, mime_type, size_bytes, status)
          VALUES
            (?, 'project-doc-a', 'docs/legacy.txt', 'USER_UPLOAD', 'legacy.txt',
             'text/plain', 6, 'UPLOADED')
          """,
          legacyId);

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("288"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      var legacy =
          jdbc.queryForMap(
              "SELECT processing_attempt, cancellation_requested, error_code,"
                  + " processing_lease_token, processing_lease_owner"
                  + " FROM project_document WHERE id=?",
              legacyId);
      assertThat(((Number) legacy.get("processing_attempt")).intValue()).isZero();
      assertThat(legacy.get("cancellation_requested")).isEqualTo(false);
      assertThat(legacy.get("error_code")).isNull();
      assertThat(((Number) legacy.get("processing_lease_token")).longValue()).isZero();
      assertThat(legacy.get("processing_lease_owner")).isNull();

      // New storage identity is unrelated to the duplicate display filename. Content bytes are
      // byte-exact (including NUL/non-UTF8) because V288 no longer stores them through String.
      insertDocument(jdbc, firstId, "project-doc-a", approvedBytes.length);
      insertDocument(jdbc, secondId, "project-doc-b", approvedBytes.length);
      jdbc.update(
          "INSERT INTO project_document_blob(document_id, content_bytes) VALUES (?, ?)",
          firstId,
          approvedBytes);
      jdbc.update(
          "INSERT INTO project_document_blob(document_id, content_bytes) VALUES (?, ?)",
          secondId,
          "tenant-b".getBytes(StandardCharsets.UTF_8));

      assertThat(
              jdbc.queryForObject(
                  "SELECT content_bytes FROM project_document_blob WHERE document_id=?",
                  byte[].class,
                  firstId))
          .containsExactly(approvedBytes);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM project_document WHERE project_id='project-doc-a'"
                      + " AND deleted_at IS NULL",
                  Long.class))
          .isEqualTo(2L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM project_document WHERE project_id='project-doc-b'"
                      + " AND deleted_at IS NULL",
                  Long.class))
          .isEqualTo(1L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COALESCE(SUM(size_bytes),0) FROM project_document"
                      + " WHERE project_id='project-doc-a' AND deleted_at IS NULL",
                  Long.class))
          .isEqualTo(14L);

      // JVM A claims token 1, expires, and JVM B takes token 2. Every publication uses the exact
      // owner+token+unexpired CAS contract, so A cannot write status/chunks after takeover.
      assertThat(
              jdbc.update(
                  "UPDATE project_document SET status='EXTRACTING', processing_lease_owner='jvm-a',"
                      + " processing_lease_token=processing_lease_token+1,"
                      + " processing_lease_expires_at=NOW()+INTERVAL '5 minutes' WHERE id=?",
                  firstId))
          .isEqualTo(1);
      jdbc.update(
          "UPDATE project_document SET processing_lease_expires_at=NOW()-INTERVAL '1 second'"
              + " WHERE id=?",
          firstId);
      assertThat(
              jdbc.update(
                  "UPDATE project_document SET processing_lease_owner='jvm-b',"
                      + " processing_lease_token=processing_lease_token+1,"
                      + " processing_lease_expires_at=NOW()+INTERVAL '5 minutes'"
                      + " WHERE id=? AND processing_lease_expires_at < NOW()",
                  firstId))
          .isEqualTo(1);
      assertThat(
              jdbc.update(
                  "UPDATE project_document SET status='READY' WHERE id=?"
                      + " AND processing_lease_owner='jvm-a' AND processing_lease_token=1"
                      + " AND processing_lease_expires_at > NOW()",
                  firstId))
          .isZero();
      assertThat(
              jdbc.update(
                  "UPDATE project_document SET status='READY', processing_lease_owner=NULL,"
                      + " processing_lease_expires_at=NULL WHERE id=?"
                      + " AND processing_lease_owner='jvm-b' AND processing_lease_token=2"
                      + " AND processing_lease_expires_at > NOW()",
                  firstId))
          .isEqualTo(1);

      // A fresh Flyway instance models restart: no replayed DDL and binary authority remains.
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT content_bytes FROM project_document_blob WHERE document_id=?",
                  byte[].class,
                  firstId))
          .containsExactly(approvedBytes);

      jdbc.update("DELETE FROM project_document WHERE id=?", firstId);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM project_document_blob WHERE document_id=?",
                  Long.class,
                  firstId))
          .isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void admissionComponentsRejectEicarAndDeriveTextTypeFromBytes() {
    ProjectDocumentProperties limits = new ProjectDocumentProperties();
    var inspected =
        new DocumentTypeInspector()
            .inspect("brownfield design notes".getBytes(StandardCharsets.UTF_8), limits);
    assertThat(inspected.mimeType()).isEqualTo("text/plain");

    var verdict =
        new DeterministicDocumentMalwareScanner()
            .scan("EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII));
    assertThat(verdict.verdict()).isEqualTo(DocumentMalwareScanner.ScanResult.Verdict.MALICIOUS);
  }

  private static void insertDocument(
      JdbcTemplate jdbc, String id, String projectId, int sizeBytes) {
    jdbc.update(
        """
        INSERT INTO project_document
          (id, project_id, path_relative, source, filename, mime_type, size_bytes, sha256,
           status, page_count, extracted_chars, estimated_tokens)
        VALUES
          (?, ?, ?, 'USER_UPLOAD', 'same-name.txt', 'text/plain', ?, ?,
           'UPLOADED', 1, 20, 5)
        """,
        id,
        projectId,
        "docs/.uploads/" + id + ".bin",
        sizeBytes,
        "a".repeat(64));
  }

  private static void seedTenants(JdbcTemplate jdbc) {
    jdbc.update(
        """
        INSERT INTO org (id, name, domain_patterns, created_by_user_id)
        VALUES ('org-doc', 'Document Org', 'example.com', 'user-doc')
        """);
    jdbc.update(
        """
        INSERT INTO app_user
          (id, email, first_name, last_name, password_hash, status, org_id)
        VALUES
          ('user-doc', 'doc@example.com', 'Doc', 'Owner', 'hash', 'ACTIVE', 'org-doc')
        """);
    jdbc.update(
        """
        INSERT INTO project (id, org_id, name, created_by)
        VALUES
          ('project-doc-a', 'org-doc', 'Project A', 'user-doc'),
          ('project-doc-b', 'org-doc', 'Project B', 'user-doc')
        """);
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var config =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true);
    if (target != null) config.target(target);
    return config.load();
  }
}
