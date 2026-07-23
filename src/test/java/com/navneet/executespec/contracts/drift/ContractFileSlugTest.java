package com.navneet.executespec.contracts.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RR-525 regression — contract names are source/customer-derived strings. The produced filename
 * must collapse to a single safe path segment before it reaches {@code ExecutespecFolderWriter}.
 */
class ContractFileSlugTest {

  @Test
  void collapsesATraversalContractNameToOneSafeSegment() {
    String slug =
        ContractDriftController.safeContractFileSlug(
            "../../../.github/workflows/release", "8f1c-uuid");

    assertThat(slug).isEqualTo("github-workflows-release");
    assertThat(slug).doesNotContain("/").doesNotContain("\\").doesNotContain("..");
  }

  @Test
  void fallsBackToTheContractIdWhenNothingSafeSurvives() {
    assertThat(ContractDriftController.safeContractFileSlug("../..", "8f1c-uuid"))
        .isEqualTo("contract-8f1c-uuid");
    assertThat(ContractDriftController.safeContractFileSlug(null, "8f1c-uuid"))
        .isEqualTo("contract-8f1c-uuid");
    assertThat(ContractDriftController.safeContractFileSlug("   ", "8f1c-uuid"))
        .isEqualTo("contract-8f1c-uuid");
  }

  @Test
  void preservesAnOrdinaryContractName() {
    assertThat(ContractDriftController.safeContractFileSlug("orders-api.v2", "id"))
        .isEqualTo("orders-api.v2");
  }

  @Test
  void boundsAnAbsurdlyLongName() {
    String slug = ContractDriftController.safeContractFileSlug("a".repeat(500), "id");
    assertThat(slug).hasSize(100);
  }
}
