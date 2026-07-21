package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V289 -> V290 semantic-provenance and lexical-null-vector rehearsal. */
@Tag("integration")
@Testcontainers
class ProjectEmbeddingMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("project_embedding_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeBackfillsHistoricalTruthAndAllowsLexicalChunksWithoutFakeVectors() {
    String schema = "project_embedding_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String semanticDocument = UUID.randomUUID().toString();
    String lexicalDocument = UUID.randomUUID().toString();

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("289")).migrate();
      insertDocument(jdbc, semanticDocument, "READY");
      jdbc.update(
          "INSERT INTO project_document_chunk"
              + " (id,document_id,chunk_index,content,token_count,embedding)"
              + " VALUES (?,?,0,'semantic historical content',4,?::vector)",
          UUID.randomUUID().toString(),
          semanticDocument,
          vector(0.1f));

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("290"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      var historical =
          jdbc.queryForMap(
              "SELECT retrieval_mode, embedding_provider, embedding_model"
                  + " FROM project_document WHERE id=?",
              semanticDocument);
      assertThat(historical.get("retrieval_mode")).isEqualTo("SEMANTIC");
      assertThat(historical.get("embedding_provider")).isEqualTo("openai");
      assertThat(historical.get("embedding_model")).isEqualTo("text-embedding-3-small");

      insertDocument(jdbc, lexicalDocument, "LEXICAL_READY");
      jdbc.update(
          "UPDATE project_document SET retrieval_mode='LEXICAL' WHERE id=?", lexicalDocument);
      assertThat(
              jdbc.update(
                  "INSERT INTO project_document_chunk"
                      + " (id,document_id,chunk_index,content,token_count,embedding)"
                      + " VALUES (?,?,0,'lexical deepseek content',4,NULL)",
                  UUID.randomUUID().toString(),
                  lexicalDocument))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM project_document_chunk c JOIN project_document d"
                      + " ON d.id=c.document_id WHERE d.retrieval_mode='LEXICAL'"
                      + " AND c.embedding IS NULL",
                  Long.class))
          .isEqualTo(1L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM project_document_chunk c JOIN project_document d"
                      + " ON d.id=c.document_id WHERE d.retrieval_mode='SEMANTIC'"
                      + " AND c.embedding IS NOT NULL",
                  Long.class))
          .isEqualTo(1L);

      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static void insertDocument(JdbcTemplate jdbc, String id, String status) {
    jdbc.update(
        "INSERT INTO project_document"
            + " (id,project_id,path_relative,source,filename,mime_type,size_bytes,status)"
            + " VALUES (?,'project-1',?,'USER_UPLOAD','notes.txt','text/plain',12,?)",
        id,
        "docs/.uploads/" + id + ".bin",
        status);
  }

  private static String vector(float value) {
    StringBuilder vector = new StringBuilder("[");
    for (int i = 0; i < 1536; i++) {
      if (i > 0) vector.append(',');
      vector.append(value);
    }
    return vector.append(']').toString();
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
