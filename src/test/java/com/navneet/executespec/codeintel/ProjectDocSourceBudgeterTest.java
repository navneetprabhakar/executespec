package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ProjectDocSourceBudgeterTest {

  @Test
  void retainsAtomicEvidenceByteCompleteWhenItFits() {
    String atomic =
        ExportedFunctionEvidence.BEGIN + "\nexact\n" + ExportedFunctionEvidence.END + "\n";

    String result =
        ProjectDocSourceBudgeter.budget(
            List.of(new ProjectDocSourceBudgeter.Section("# Exact", atomic, true)), 1_000);

    assertThat(result).contains(atomic).doesNotContain("atomic evidence omitted");
  }

  @Test
  void omitsAtomicEvidenceWholeWhenItDoesNotFit() {
    String atomic =
        ExportedFunctionEvidence.BEGIN
            + "\n"
            + "exact-line\n".repeat(100)
            + ExportedFunctionEvidence.END
            + "\n";

    String result =
        ProjectDocSourceBudgeter.budget(
            List.of(
                new ProjectDocSourceBudgeter.Section("# Ordinary", "ordinary\n".repeat(20)),
                new ProjectDocSourceBudgeter.Section("# Exact", atomic, true)),
            500);

    assertThat(result)
        .hasSizeLessThanOrEqualTo(500)
        .contains("# Ordinary", "# Exact", "atomic evidence omitted")
        .doesNotContain(ExportedFunctionEvidence.BEGIN, ExportedFunctionEvidence.END);
  }

  @Test
  void redistributesOmittedAtomicCapacityToMandatorySection() {
    String mandatory = "mandatory-evidence\n".repeat(20);
    String atomic = "exact-optional\n".repeat(100);

    String result =
        ProjectDocSourceBudgeter.budget(
            List.of(
                new ProjectDocSourceBudgeter.Section("# Mandatory", mandatory),
                new ProjectDocSourceBudgeter.Section("# Optional", atomic, true)),
            700);

    assertThat(result)
        .contains(mandatory, "atomic evidence omitted")
        .doesNotContain("section evidence truncated at a complete line");
  }
}
