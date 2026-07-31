package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Deliberately UNTAGGED boot smoke that runs in the default {@code mvn test} gate (and therefore in
 * PR CI), unlike the {@code @Tag("integration")} suite which only runs under {@code -Pintegration}.
 *
 * <p>It exists because of the 2026-07-31 dev outage: PR #301 shipped V369 with {@code CHAR(64)}
 * columns against a {@code VARCHAR(64)} entity mapping plus a Spring-unselectable {@code
 * StartupRunResumer} constructor. CI was green because nothing in the default gate ever ran Flyway
 * against real PostgreSQL and then built the Hibernate {@code SessionFactory} with {@code
 * ddl-auto=validate}. The dev VM did exactly that on boot and crash-looped.
 *
 * <p>This test is that missing boot: a real pgvector PostgreSQL container, every Flyway migration
 * from V1, and a full application-context boot on the {@code integration-test} profile (which pins
 * {@code ddl-auto: validate}, matching prod/dev). Context startup is the assertion — a
 * schema/entity mismatch or an uninstantiable bean fails here instead of after merge. Skips only
 * when Docker is unavailable; GitHub CI runners always have Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration-test")
class MigratedSchemaBootSmokeTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("executespec_boot_smoke")
          .withUsername("test")
          .withPassword("test");

  /**
   * The context boot starts a Redis message-listener container, so the smoke must not silently
   * depend on a developer-machine Redis: CI runners have Docker but no local Redis.
   */
  @Container
  @SuppressWarnings("resource")
  static final org.testcontainers.containers.GenericContainer<?> REDIS =
      new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    // The guarded behavior: Hibernate must VALIDATE the migrated schema exactly like prod/dev.
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.ai.anthropic.api-key", () -> "test-key-not-used");
    registry.add("spring.ai.openai.api-key", () -> "test-key-not-used");
    registry.add(
        "agent.workspace", () -> System.getProperty("java.io.tmpdir") + "/boot-smoke-workspace");
  }

  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired JdbcTemplate jdbc;

  @Test
  void fullyMigratedSchemaSurvivesHibernateValidationBoot() {
    // Reaching this method proves the boot: Flyway applied every migration and the
    // EntityManagerFactory was built with ddl-auto=validate over the complete entity set.
    assertThat(entityManagerFactory.isOpen()).isTrue();
  }

  @Test
  void flywayActuallyReachedTheSourceHeadVersion() throws IOException {
    // Guards against a silent Flyway no-op (e.g. misconfigured locations) making the
    // validation boot vacuous. The applied head must equal the highest V-file in source.
    String appliedHead =
        jdbc.queryForObject(
            "SELECT version FROM flyway_schema_history"
                + " WHERE success ORDER BY installed_rank DESC LIMIT 1",
            String.class);

    Pattern versionPattern = Pattern.compile("^V(\\d+)(?:_\\d+)?__.+\\.sql$");
    long sourceHead;
    try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
      sourceHead =
          files
              .map(p -> versionPattern.matcher(p.getFileName().toString()))
              .filter(Matcher::matches)
              .mapToLong(m -> Long.parseLong(m.group(1)))
              .max()
              .orElseThrow();
    }

    assertThat(appliedHead).isNotNull();
    assertThat(Long.parseLong(appliedHead.split("\\.")[0])).isEqualTo(sourceHead);
  }
}
