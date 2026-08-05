package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ProjectSetupOperationalGroundingTest {

  @Test
  void assemblesOnlyExactProjectedCommandsForTheLiveNodeShape() {
    String evidence =
        envelope(
            "navneetprabhakar/catalog-litmus",
            source(
                    "package.json",
                    "{\"engines\":{\"node\":\">=20\"},"
                        + "\"scripts\":{\"start\":\"node src/catalog.js\","
                        + "\"test\":\"node --test\",\"check\":\"node --check src/catalog.js\"}}")
                + derived("npm start")
                + derived("npm test")
                + derived("npm run check"));

    String grounded =
        ProjectSetupOperationalGrounding.assemble(
            "Catalog Reliability Litmus", evidence, Set.of("navneetprabhakar/catalog-litmus"));

    assertThat(grounded)
        .startsWith("# Setting up Catalog Reliability Litmus")
        .contains(
            "Node.js `>=20`",
            "git clone https://github.com/navneetprabhakar/catalog-litmus.git",
            "```bash\nnpm start\n```",
            "```bash\nnpm test\n```",
            "```bash\nnpm run check\n```")
        .doesNotContain("npm install", "npm ci", "localhost", "healthz", "DATABASE_URL");
    assertThat(
            ProjectGeneratedDocValidator.validate(
                com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType.SETUP,
                grounded,
                Set.of("navneetprabhakar/catalog-litmus"),
                evidence))
        .isEmpty();
    assertThat(
            ProjectSetupOperationalGrounding.isCanonical(
                grounded,
                "Catalog Reliability Litmus",
                evidence,
                Set.of("navneetprabhakar/catalog-litmus")))
        .isTrue();
  }

  @Test
  void emptyProjectionEmitsUnknownsAndNoNonCloneCommand() {
    String evidence = envelope("owner/repo", "- No exact setup command source was selected.\n");

    String grounded =
        ProjectSetupOperationalGrounding.assemble("Sample", evidence, Set.of("owner/repo"));

    assertThat(grounded)
        .contains("No test, check, or start command is established")
        .contains("git clone https://github.com/owner/repo.git")
        .doesNotContain("npm ", "make ", "docker ", "curl ");
    assertThat(
            ProjectGeneratedDocValidator.validate(
                com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType.SETUP,
                grounded,
                Set.of("owner/repo"),
                evidence))
        .isEmpty();
  }

  @Test
  void multiRepoCommandsRemainScopedAndOrderingIsStable() {
    String evidence =
        envelope(
                "owner/repo-b",
                source("package.json", "{\"scripts\":{\"test\":\"b\"}}") + derived("npm test"))
            + envelope(
                "owner/repo-a", source("Makefile", "check:\n\ttrue") + derived("make check"));

    String grounded =
        ProjectSetupOperationalGrounding.assemble(
            "Sample", evidence, Set.of("owner/repo-b", "owner/repo-a"));

    assertThat(grounded.indexOf("### `owner/repo-a`"))
        .isLessThan(grounded.indexOf("### `owner/repo-b`"));
    assertThat(grounded).contains("```bash\nmake check\n```", "```bash\nnpm test\n```");
    assertThat(
            ProjectGeneratedDocValidator.validate(
                com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType.SETUP,
                grounded,
                Set.of("owner/repo-a", "owner/repo-b"),
                evidence))
        .isEmpty();
  }

  @Test
  void emptyCommandProjectionIsDetectedOnlyForValidAuthorityWithRepositories() {
    String emptyProjection =
        envelope("owner/repo", "- No exact setup command source was selected.\n");
    String projectedCommand =
        envelope(
            "owner/repo",
            source("package.json", "{\"scripts\":{\"test\":\"node --test\"}}")
                + derived("npm test"));

    assertThat(
            ProjectSetupOperationalGrounding.hasEmptyCommandProjection(
                emptyProjection, Set.of("owner/repo")))
        .isTrue();
    // A source excerpt alone (no derived command) still projects nothing.
    assertThat(
            ProjectSetupOperationalGrounding.hasEmptyCommandProjection(
                envelope("owner/repo", source("README.md", "documentation prose only")),
                Set.of("owner/repo")))
        .isTrue();
    assertThat(
            ProjectSetupOperationalGrounding.hasEmptyCommandProjection(
                projectedCommand, Set.of("owner/repo")))
        .isFalse();
    // Malformed/incomplete evidence must not be classified as an empty projection: the ordinary
    // fail-closed generation path owns surfacing its exact error.
    assertThat(
            ProjectSetupOperationalGrounding.hasEmptyCommandProjection(
                "malformed", Set.of("owner/repo")))
        .isFalse();
    assertThat(
            ProjectSetupOperationalGrounding.hasEmptyCommandProjection(emptyProjection, Set.of()))
        .isFalse();
    // Multi-repo: one projected command anywhere keeps the provider lane.
    assertThat(
            ProjectSetupOperationalGrounding.hasEmptyCommandProjection(
                envelope("owner/repo-a", "- No exact setup command source was selected.\n")
                    + envelope(
                        "owner/repo-b",
                        source("Makefile", "check:\n\ttrue") + derived("make check")),
                Set.of("owner/repo-a", "owner/repo-b")))
        .isFalse();
  }

  @Test
  void malformedAuthorityFailsClosedAndCannotBeCanonical() {
    assertThatThrownBy(
            () ->
                ProjectSetupOperationalGrounding.assemble(
                    "Sample", "malformed", Set.of("owner/repo")))
        .isInstanceOf(ProjectDocGeneratorService.InvalidGeneratedDocumentException.class)
        .hasMessageContaining("setup evidence");
    assertThat(
            ProjectSetupOperationalGrounding.isCanonical(
                "# stale", "Sample", "malformed", Set.of("owner/repo")))
        .isFalse();
  }

  private static String envelope(String repository, String body) {
    return DeterministicProjectFactEvidence.SETUP_BEGIN
        + "\n- Evidence-Format: EXECUTESPEC_SETUP_SOURCES_V2\n"
        + "- Repository-Base64: "
        + b64(repository)
        + "\n- Source-Revision: "
        + "a".repeat(40)
        + "\n- Scm-Provider: GITHUB\n"
        + "- Source-Selection: COMPLETE\n"
        + body
        + DeterministicProjectFactEvidence.SETUP_END;
  }

  private static String source(String path, String content) {
    return "- Source-Path-Base64: "
        + b64(path)
        + "\n  Content-Base64: "
        + b64(content)
        + "\n  Truncated: false\n";
  }

  private static String derived(String command) {
    return "  Derived-Command-Base64: " + b64(command) + "\n";
  }

  private static String b64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
