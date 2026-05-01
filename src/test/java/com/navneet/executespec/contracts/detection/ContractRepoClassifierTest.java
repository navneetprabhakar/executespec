package com.navneet.executespec.contracts.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContractRepoClassifier} (E2.5). Drives every branch of the heuristic decision
 * tree without needing a real repo.
 */
class ContractRepoClassifierTest {

  private final ContractRepoClassifier classifier = new ContractRepoClassifier();

  @Test
  void confidentYes_whenNameMatchesAndDensityHigh() {
    ContractRepoClassifier.Verdict v =
        classifier.classify(
            "acme/payments-contracts",
            List.of(
                "README.md",
                "src/payment.proto",
                "src/wallet.proto",
                "src/order.proto",
                ".gitignore"));
    assertThat(v.isContractRepo()).isTrue();
    assertThat(v.confidence()).isEqualTo(ContractRepoClassifier.Verdict.Confidence.CONFIDENT);
    assertThat(v.reasons()).anyMatch(r -> r.contains("name matches"));
    assertThat(v.reasons()).anyMatch(r -> r.contains("100%"));
  }

  @Test
  void confidentNo_whenApplicationCodePresent_evenWithMatchingName() {
    ContractRepoClassifier.Verdict v =
        classifier.classify(
            "acme/payments-contracts",
            List.of(
                "README.md",
                "pom.xml", // app-code marker
                "src/main/java/Main.java",
                "src/payment.proto"));
    assertThat(v.isContractRepo()).isFalse();
    assertThat(v.confidence()).isEqualTo(ContractRepoClassifier.Verdict.Confidence.CONFIDENT);
    assertThat(v.reasons()).anyMatch(r -> r.contains("application-code markers"));
  }

  @Test
  void ambiguous_whenOnlyDensityHigh_butNameDoesNotMatch() {
    ContractRepoClassifier.Verdict v =
        classifier.classify(
            "acme/widget",
            List.of(
                "schemas/user.schema.json",
                "schemas/order.schema.json",
                "schemas/payment.schema.json"));
    assertThat(v.isContractRepo()).isTrue();
    assertThat(v.confidence()).isEqualTo(ContractRepoClassifier.Verdict.Confidence.AMBIGUOUS);
  }

  @Test
  void ambiguous_whenOnlyNameMatches_butFewContracts() {
    ContractRepoClassifier.Verdict v =
        classifier.classify(
            "acme/contract-stuff",
            List.of("README.md", "docs/intro.md", "scripts/deploy.sh", "src/payment.proto"));
    // Density falls below 0.7 because docs/intro.md + scripts/deploy.sh are
    // counted as interesting files.
    assertThat(v.isContractRepo()).isTrue();
    assertThat(v.confidence()).isEqualTo(ContractRepoClassifier.Verdict.Confidence.AMBIGUOUS);
  }

  @Test
  void confidentNo_whenNeitherSignal() {
    ContractRepoClassifier.Verdict v =
        classifier.classify(
            "acme/widget-web", List.of("src/Main.java", "src/Util.java", "README.md"));
    assertThat(v.isContractRepo()).isFalse();
    assertThat(v.confidence()).isEqualTo(ContractRepoClassifier.Verdict.Confidence.CONFIDENT);
  }

  @Test
  void nameMatchesContractsHint_requiresWordBoundary() {
    // "specification" should NOT match "spec" — boundary check.
    assertThat(classifier.nameMatchesContractsHint("acme/specification-engine")).isFalse();
    assertThat(classifier.nameMatchesContractsHint("acme/api-contracts")).isTrue();
    assertThat(classifier.nameMatchesContractsHint("acme/event-schemas")).isTrue();
    assertThat(classifier.nameMatchesContractsHint("acme/proto")).isTrue();
    assertThat(classifier.nameMatchesContractsHint("acme/protocol-buffers")).isFalse();
  }

  @Test
  void hasApplicationCode_caseInsensitive_matchesBasenameOnly() {
    assertThat(classifier.hasApplicationCode(List.of("Pom.xml"))).isTrue();
    assertThat(classifier.hasApplicationCode(List.of("nested/pom.xml"))).isTrue();
    assertThat(classifier.hasApplicationCode(List.of("docs/pom.xml.md"))).isFalse();
  }

  @Test
  void contractDensity_zeroForEmptyFileList() {
    assertThat(classifier.contractDensity(List.of())).isEqualTo(0.0);
  }

  @Test
  void contractDensity_excludesBoringConfigFromDenominator() {
    // README.md, LICENSE, .gitignore should NOT inflate the denominator.
    double density =
        classifier.contractDensity(List.of("README.md", "LICENSE", ".gitignore", "schema.proto"));
    assertThat(density).isEqualTo(1.0);
  }

  @Test
  void verdict_handlesEmptyOrNullInputs_gracefully() {
    ContractRepoClassifier.Verdict v1 = classifier.classify(null, List.of());
    assertThat(v1.isContractRepo()).isFalse();
    assertThat(v1.confidence()).isEqualTo(ContractRepoClassifier.Verdict.Confidence.CONFIDENT);

    ContractRepoClassifier.Verdict v2 = classifier.classify("acme/foo", null);
    assertThat(v2.isContractRepo()).isFalse();
  }
}
