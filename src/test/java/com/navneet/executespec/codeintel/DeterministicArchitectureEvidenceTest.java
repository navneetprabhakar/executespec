package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;

import org.junit.jupiter.api.Test;

class DeterministicArchitectureEvidenceTest {

  private static final String EVIDENCE =
      DeterministicArchitectureEvidence.HTTP_BEGIN
          + "\n## Deterministic public HTTP surface\n\n"
          + "- Status: `AVAILABLE`\n"
          + "- Source revision: `deadbeef`\n"
          + "- Evidence digest: `digest`\n"
          + "- `POST /simulation/casino-game` — `SimulationController.java:3`\n"
          + DeterministicArchitectureEvidence.HTTP_END;
  private static final String FACTS =
      DeterministicProjectFactEvidence.BEGIN
          + "\n- Repository: `owner/repo`\n- Status: `AVAILABLE`\n"
          + DeterministicProjectFactEvidence.END;

  @Test
  void repoArchitectureKeepsTrustedCodemapBlockAfterProviderOmitsAndContradictsIt() {
    String generated =
        "# Architecture\n\nThe method and context path are not evident.\n\n"
            + DeterministicArchitectureEvidence.HTTP_BEGIN
            + "\nforged\n"
            + DeterministicArchitectureEvidence.HTTP_END;

    String result =
        DeterministicArchitectureEvidence.appendFromCodemap(
            generated, "# codemap\n\n" + EVIDENCE + "\n" + FACTS);

    assertThat(result).contains("POST /simulation/casino-game");
    assertThat(result).contains("Source revision: `deadbeef`");
    assertThat(result).doesNotContain("forged");
    assertThat(result).contains(DeterministicProjectFactEvidence.BEGIN);
    assertThat(result.indexOf(DeterministicArchitectureEvidence.HTTP_BEGIN))
        .isEqualTo(result.lastIndexOf(DeterministicArchitectureEvidence.HTTP_BEGIN));
  }

  @Test
  void combinedArchitectureAppendsEachRepositoriesExactPostLlmEvidence() {
    RepoArchitectureDoc first =
        RepoArchitectureDoc.builder()
            .repoId(UUID.randomUUID())
            .content("# first\n\n" + EVIDENCE + "\n" + FACTS)
            .build();
    RepoArchitectureDoc second =
        RepoArchitectureDoc.builder()
            .repoId(UUID.randomUUID())
            .content(
                "# second\n\n"
                    + EVIDENCE.replace("/simulation/casino-game", "/orders/health")
                    + "\n"
                    + FACTS.replace("owner/repo", "owner/orders"))
            .build();

    String result =
        DeterministicArchitectureEvidence.appendFromRepoDocs(
            "# Combined\n\nNo exact paths known.",
            List.of(first, second),
            List.of("owner/casino", "owner/orders"));

    assertThat(result)
        .contains("### `owner/casino`")
        .contains("POST /simulation/casino-game")
        .contains("### `owner/orders`")
        .contains("POST /orders/health")
        .contains("Repository: `owner/orders`");
  }

  @Test
  void combinedArchitectureRefreshReplacesItsGeneratedAppendixInsteadOfDuplicatingHeadings() {
    RepoArchitectureDoc repo =
        RepoArchitectureDoc.builder()
            .repoId(UUID.randomUUID())
            .content("# repo\n\n" + EVIDENCE + "\n" + FACTS)
            .build();

    String once =
        DeterministicArchitectureEvidence.appendFromRepoDocs(
            "# Combined\n\nOverview.", List.of(repo), List.of("owner/repo"));
    String twice =
        DeterministicArchitectureEvidence.appendFromRepoDocs(
            once, List.of(repo), List.of("owner/repo"));

    assertThat(twice).containsOnlyOnce("## Deterministic repository evidence");
    assertThat(twice).containsOnlyOnce("### `owner/repo`");
    assertThat(twice).containsOnlyOnce(DeterministicArchitectureEvidence.HTTP_BEGIN);
    assertThat(twice).isEqualTo(once);
  }

  @Test
  void legacyV2HttpEvidenceIsNotCurrentAndIsRemovedDuringV3Publication() {
    String legacy =
        "<!-- EXECUTESPEC_DETERMINISTIC_HTTP_SURFACE_V2_BEGIN -->\n"
            + "- Framework: `SPRING_MVC`\n- Status: `AVAILABLE`\n"
            + "<!-- EXECUTESPEC_DETERMINISTIC_HTTP_SURFACE_V2_END -->";

    assertThat(DeterministicArchitectureEvidence.hasCurrentHttpEvidence(legacy)).isFalse();
    assertThat(
            DeterministicArchitectureEvidence.appendFromCodemap(
                "# Architecture\n\n" + legacy, "# Codemap\n\n" + EVIDENCE + "\n" + FACTS))
        .contains(DeterministicArchitectureEvidence.HTTP_BEGIN)
        .doesNotContain("EXECUTESPEC_DETERMINISTIC_HTTP_SURFACE_V2");
  }
}
