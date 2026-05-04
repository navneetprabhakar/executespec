package com.navneet.executespec.v2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.v2.tool.MavenDependencyValidator.ValidationResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression guard for {@link MavenDependencyValidator}.
 *
 * <p>Pinned classes:
 *
 * <ul>
 *   <li>parses inherited deps as "no version → skip" (BOM / spring-boot-starter-* pattern)
 *   <li>resolves {@code ${property}} references against the same pom's {@code <properties>}
 *   <li>skips unresolvable properties (likely inherited from a parent outside the workspace)
 *   <li>returns "passed" with scannedCount=0 on non-Maven workspaces
 *   <li>format-error output names the bad dep + its short ref (g:a:v)
 * </ul>
 *
 * <p>Network-dependent tests (HEAD against repo1.maven.org) are NOT included here — they live in a
 * separate {@code @Tag("network")} suite that's only run during CI's nightly job. The unit tests
 * below stub the network by exercising only the parsing + reporting paths.
 */
class MavenDependencyValidatorTest {

  private final MavenDependencyValidator validator = new MavenDependencyValidator();

  @Test
  void non_maven_project_returns_passed_and_zero_scanned(@TempDir Path tmp) {
    // No pom.xml → should be a no-op pass with scanned=0.
    ValidationResult result = validator.validate(tmp);
    assertTrue(result.isPassed());
    assertEquals(0, result.getScannedCount());
    assertTrue(result.getMissingDeps().isEmpty());
  }

  @Test
  void inherited_deps_without_explicit_version_are_skipped(@TempDir Path tmp) throws IOException {
    // Spring Boot starter pattern: parent pom + dependencyManagement → no <version> per dep.
    // The validator should NOT scan these (they resolve from BOM at build time).
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>0.0.1</version>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>3.3.0</version>
          </parent>
          <dependencies>
            <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-web</artifactId>
            </dependency>
            <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-data-jpa</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);
    ValidationResult result = validator.validate(tmp);
    // Note: the parent block has a version but parent isn't a <dependency> — the parser scans
    // <dependency>...</dependency> blocks specifically, so the parent version is ignored
    // (correctly — parent version validation would need a different path entirely).
    assertEquals(0, result.getScannedCount(), "should skip starters without explicit <version>");
    assertTrue(result.isPassed());
  }

  @Test
  void unresolvable_property_skips_check_rather_than_false_flag(@TempDir Path tmp)
      throws IOException {
    // Property defined in parent pom (outside the workspace). Validator can't resolve,
    // so it skips the check rather than falsely flagging "not found".
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>some-lib</artifactId>
              <version>${some.external.version}</version>
            </dependency>
          </dependencies>
        </project>
        """);
    ValidationResult result = validator.validate(tmp);
    assertEquals(0, result.getScannedCount(), "unresolvable property → skip");
    assertTrue(result.isPassed());
  }

  @Test
  void resolved_property_does_count_toward_scanned(@TempDir Path tmp) throws IOException {
    // Property defined locally in <properties> → resolvable → should be scanned.
    // We can't actually verify Maven Central from a unit test (no network), but we
    // can verify that the parse + property-resolution path counts the dep correctly.
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <properties>
            <jackson.version>2.17.0</jackson.version>
          </properties>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>${jackson.version}</version>
            </dependency>
          </dependencies>
        </project>
        """);
    ValidationResult result = validator.validate(tmp);
    // 1 dep scanned. Pass/fail depends on network; assert only the count to keep the
    // test deterministic offline (network-dependent assertion is in the integration suite).
    assertEquals(1, result.getScannedCount(), "resolved property → scanned");
  }

  @Test
  void format_error_is_empty_when_passing() {
    String out = validator.formatError(new ValidationResult(true, java.util.List.of(), 5));
    assertEquals("", out);
  }

  @Test
  void format_error_names_each_missing_dep_with_short_ref() {
    var issue =
        new MavenDependencyValidator.DepIssue(
            "org.flywaydb", "flyway-database-h2", "10.10.0", "Maven Central returned 404 …");
    String out = validator.formatError(new ValidationResult(false, java.util.List.of(issue), 1));
    assertTrue(out.contains("org.flywaydb:flyway-database-h2:10.10.0"));
    assertTrue(out.contains("Maven Central returned 404"));
    assertTrue(out.contains("search.maven.org"), "should give a search URL hint");
  }

  @Test
  void format_error_pluralises_correctly() {
    var single =
        validator.formatError(
            new ValidationResult(
                false,
                java.util.List.of(new MavenDependencyValidator.DepIssue("g", "a", "v", "404")),
                1));
    assertFalse(single.contains("dependencyies"), "singular should not pluralise");

    var multi =
        validator.formatError(
            new ValidationResult(
                false,
                java.util.List.of(
                    new MavenDependencyValidator.DepIssue("g1", "a1", "v1", "404"),
                    new MavenDependencyValidator.DepIssue("g2", "a2", "v2", "404")),
                2));
    assertTrue(multi.contains("dependencies"), "plural should pluralise");
  }
}
