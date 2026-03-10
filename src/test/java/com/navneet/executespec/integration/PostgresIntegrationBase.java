package com.navneet.executespec.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base for all Testcontainers integration tests.
 * Spins up a single PostgreSQL container reused across all subclasses.
 * Tagged "integration" so Maven Surefire excludes these from the default mvn test run.
 * Run explicitly with: mvn test -Dgroups=integration
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Testcontainers
public abstract class PostgresIntegrationBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("executespec_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway will run V1 + V2 migrations automatically
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        // Disable real AI calls in integration tests
        registry.add("spring.ai.anthropic.api-key", () -> "test-key-not-used");
        registry.add("spring.ai.openai.api-key", () -> "test-key-not-used");
        registry.add("agent.workspace", () -> System.getProperty("java.io.tmpdir") + "/it-workspace");
    }
}
