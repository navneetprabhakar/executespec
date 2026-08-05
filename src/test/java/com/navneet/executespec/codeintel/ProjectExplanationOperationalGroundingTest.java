package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ProjectExplanationOperationalGroundingTest {

  @Test
  void replacesModelOperationsWithExactEvidenceBackedNodeWorkflow() {
    String model =
        """
        # Sample

        ## Repositories
        `owner/repo`

        ## High-level architecture
        Exact relationships are not established.

        ## Key data flows
        No directional flow is established.

        ## Where to contribute
        Inspect the exact revision.

        ## Verified local workflow
        Run `npm run invented`.

        ## Troubleshooting
        Delete the database and retry.
        """;

    String grounded =
        ProjectExplanationOperationalGrounding.append(model, nodeEvidence(), Set.of("owner/repo"));

    assertThat(grounded)
        .containsOnlyOnce("## Verified local workflow")
        .containsOnlyOnce("## Troubleshooting")
        .contains(
            "Prerequisite: Node.js `>=20`",
            "`npm ci`",
            "`npm test`",
            "`npm start`",
            "run that exact command from the named repository root",
            "inspect its emitted error")
        .contains("satisfies the evidenced `package.json` engine constraint")
        .doesNotContain("npm run invented", "Delete the database");
  }

  @Test
  void emitsExplicitUnknownsWhenNoSetupSourceWasSelected() {
    String grounded =
        ProjectExplanationOperationalGrounding.append(
            "# Sample\n\n## Repositories\n`owner/repo`", emptyEvidence(), Set.of("owner/repo"));

    assertThat(grounded)
        .contains("No install, test, run, or runtime prerequisite is established")
        .contains("No repository-specific troubleshooting remedy is established");
  }

  @Test
  void removesEveryCaseVariantAndDuplicateOperationalSectionBeforeGrounding() {
    String model =
        """
        # Sample

        ## VERIFIED LOCAL WORKFLOW
        Run `npm run invented-one`.

        ## Troubleshooting
        Delete the database.

        ## Verified local workflow
        Run `npm run invented-two`.

        ## TROUBLESHOOTING
        Disable authentication.
        """;

    String grounded =
        ProjectExplanationOperationalGrounding.append(model, nodeEvidence(), Set.of("owner/repo"));

    assertThat(grounded)
        .containsOnlyOnce("## Verified local workflow")
        .containsOnlyOnce("## Troubleshooting")
        .doesNotContain(
            "invented-one", "invented-two", "Delete the database", "Disable authentication")
        .contains("`npm ci`", "`npm test`", "`npm start`");
  }

  private static String nodeEvidence() {
    return envelope(
        source(
                "package.json",
                "{\"engines\":{\"node\":\">=20\"},"
                    + "\"scripts\":{\"test\":\"vitest run\",\"start\":\"vite\"}}")
            + "  Derived-Command-Base64: "
            + b64("npm test")
            + "\n  Derived-Command-Base64: "
            + b64("npm start")
            + "\n"
            + source("README.md", "npm ci"));
  }

  private static String emptyEvidence() {
    return envelope("- No exact setup command source was selected.\n");
  }

  private static String envelope(String body) {
    return DeterministicProjectFactEvidence.SETUP_BEGIN
        + "\n- Evidence-Format: EXECUTESPEC_SETUP_SOURCES_V2\n"
        + "- Repository-Base64: "
        + b64("owner/repo")
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

  private static String b64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
