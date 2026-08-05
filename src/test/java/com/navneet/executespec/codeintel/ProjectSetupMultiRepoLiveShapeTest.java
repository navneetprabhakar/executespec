package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.Test;

/**
 * Reproduces the 2026-08-05 dev defect on project 7238a44d: three linked Node repositories with
 * package.json scripts made every deterministic SETUP assembly fail strict validation with "setup
 * command is not present in encoded exact-revision setup evidence". The encoded setup envelopes
 * here are built by the REAL production renderer ({@link DeterministicProjectFactEvidence#render}),
 * not hand-written fixtures.
 */
class ProjectSetupMultiRepoLiveShapeTest {

  private static final String API_REPO = "navneetprabhakar/inventory-api-lab-20260805";
  private static final String WEB_REPO = "navneetprabhakar/inventory-web-lab-20260805";
  private static final String RULES_REPO = "navneetprabhakar/inventory-rules-lab-20260804";

  private static final String API_PACKAGE_JSON =
      """
      {
        "name": "inventory-api-lab",
        "version": "1.0.0",
        "scripts": {"start": "node src/server.js", "test": "node --test"}
      }
      """;
  private static final String WEB_PACKAGE_JSON =
      """
      {
        "name": "inventory-web-lab",
        "version": "1.0.0",
        "scripts": {"test": "node --test"}
      }
      """;
  private static final String RULES_PACKAGE_JSON =
      """
      {
        "name": "inventory-rules-lab",
        "version": "1.0.0",
        "engines": {"node": ">=20"},
        "scripts": {
          "start": "node src/cli.js",
          "test": "node --test",
          "check": "node --check src/inventory.js && node --check src/cli.js"
        }
      }
      """;

  private static final Set<String> REPOSITORIES = Set.of(API_REPO, WEB_REPO, RULES_REPO);

  @Test
  void liveThreeRepoScriptsShapeAssemblesAndValidatesEndToEnd() {
    String evidence = liveEvidence();
    ProjectGeneratedDocValidator.SetupAuthority authority =
        ProjectGeneratedDocValidator.decodedSetupSources(evidence, REPOSITORIES);
    assertThat(authority.valid()).as(authority.error()).isTrue();
    // Non-empty projection: the zero-provider empty-projection lane must NOT apply here.
    assertThat(ProjectSetupOperationalGrounding.hasEmptyCommandProjection(evidence, REPOSITORIES))
        .isFalse();

    String assembled =
        ProjectSetupOperationalGrounding.assemble("Inventory Labs", evidence, REPOSITORIES);

    // Per-repo clone blocks plus only projected commands.
    assertThat(assembled)
        .contains(
            "git clone https://github.com/" + API_REPO + ".git",
            "git clone https://github.com/" + WEB_REPO + ".git",
            "git clone https://github.com/" + RULES_REPO + ".git",
            "```bash\nnpm test\n```",
            "```bash\nnpm start\n```",
            "```bash\nnpm run check\n```",
            "Node.js `>=20`")
        .doesNotContain("npm install", "npm ci", "node --check src/inventory.js");

    // The exact wiring generateOnce uses: deterministic assembly then the unchanged strict
    // validator. Before the fix this failed on the second projected command of the first
    // repository ("npm start" under inventory-api) with
    // "setup command is not present in encoded exact-revision setup evidence".
    Optional<String> failure =
        ProjectGeneratedDocValidator.validate(DocType.SETUP, assembled, REPOSITORIES, evidence);
    assertThat(failure).isEmpty();

    assertThat(
            ProjectSetupOperationalGrounding.isCanonical(
                assembled, "Inventory Labs", evidence, REPOSITORIES))
        .isTrue();
  }

  @Test
  void wrongRepositoryCommandStillFailsClosed() {
    String evidence = liveEvidence();
    String assembled =
        ProjectSetupOperationalGrounding.assemble("Inventory Labs", evidence, REPOSITORIES);
    // "npm run check" is authorized only for the rules repository. Presenting it under the web
    // repository (which has only a test script) must remain a strict-validation failure.
    String tampered =
        assembled.replace(
            "Test for `" + WEB_REPO + "`:\n\n```bash\nnpm test\n```",
            "Test for `" + WEB_REPO + "`:\n\n```bash\nnpm run check\n```");
    assertThat(tampered).isNotEqualTo(assembled);
    assertThat(
            ProjectGeneratedDocValidator.validate(DocType.SETUP, tampered, REPOSITORIES, evidence))
        .contains("setup command is not present in encoded exact-revision setup evidence");
  }

  @Test
  void repositoryUnattributableCommandStillFailsClosed() {
    String evidence = liveEvidence();
    String assembled =
        ProjectSetupOperationalGrounding.assemble("Inventory Labs", evidence, REPOSITORIES);
    // A command block whose preceding heading section names no repository has no attribution and
    // must stay rejected even though some repository authorizes the exact command.
    String tampered = assembled + "\n\nThen run:\n\n```bash\nnpm run check\n```\n";
    assertThat(
            ProjectGeneratedDocValidator.validate(DocType.SETUP, tampered, REPOSITORIES, evidence))
        .contains("setup command is not present in encoded exact-revision setup evidence");
  }

  private static String liveEvidence() {
    return "# Repository codemap: "
        + API_REPO
        + "\n\n"
        + renderedEvidence(API_REPO, "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", API_PACKAGE_JSON)
        + "\n# Repository codemap: "
        + WEB_REPO
        + "\n\n"
        + renderedEvidence(WEB_REPO, "b2c3d4e5f60718293a4b5c6d7e8f9012345678a1", WEB_PACKAGE_JSON)
        + "\n# Repository codemap: "
        + RULES_REPO
        + "\n\n"
        + renderedEvidence(
            RULES_REPO, "c3d4e5f60718293a4b5c6d7e8f9012345678a1b2", RULES_PACKAGE_JSON);
  }

  /** Runs the actual production evidence renderer over a mocked exact-revision file read. */
  private static String renderedEvidence(String repository, String revision, String packageJson) {
    GitHubRepoService github = mock(GitHubRepoService.class);
    when(github.readRepoFile(
            eq("https://github.com/" + repository), eq("package.json"), eq(revision), any()))
        .thenReturn(GitHubRepoService.RepoFileResult.success(packageJson));
    return DeterministicProjectFactEvidence.render(
        repository,
        revision,
        List.of("package.json", "src/server.js"),
        "test-pat",
        false,
        github,
        Map.of(),
        null);
  }
}
