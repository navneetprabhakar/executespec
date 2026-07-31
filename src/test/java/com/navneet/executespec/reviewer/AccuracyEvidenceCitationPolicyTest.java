package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.tool.FileSystemTool;

import org.junit.jupiter.api.Test;

class AccuracyEvidenceCitationPolicyTest {

  @Test
  void keepsOnlyWorkspaceLocationsThatCanBeDeterministicallyReread() {
    FileSystemTool files = mock(FileSystemTool.class);
    when(files.readFileRange("run-1", "src/App.java", 2, 4))
        .thenReturn("[Lines 2-4 of 20]\n   2 | code");
    AccuracyReport report =
        AccuracyReport.builder()
            .evidenceLocations(
                List.of("src/App.java:2-4", "../secret:1", "/etc/passwd:1", "src/Missing.java:5"))
            .specEvidenceLocations(
                List.of("src/App.java:2-4", "../secret:1", "/etc/passwd:1", "src/Missing.java:5"))
            .implementedRequirements(List.of("Health route [src/App.java:2-4]"))
            .build();
    when(files.readFileRange("run-1", "src/Missing.java", 5, 5))
        .thenReturn("Cannot read src/Missing.java: missing");

    AccuracyEvidenceCitationPolicy.validate(files, "run-1", report);

    assertThat(report.getEvidenceStatus()).isEqualTo("CITED");
    assertThat(report.getEvidenceLocations()).containsExactly("src/App.java:2-4");
    assertThat(report.getSpecEvidenceStatus()).isEqualTo("CITED");
    assertThat(report.getEndpointEvidenceStatus()).isEqualTo("UNCITED");
    assertThat(report.getTestEvidenceStatus()).isEqualTo("UNCITED");
  }

  @Test
  void marksAnAssessmentUncitedWhenNoClaimCanBeBoundToSource() {
    FileSystemTool files = mock(FileSystemTool.class);
    AccuracyReport report =
        AccuracyReport.builder()
            .specEvidenceLocations(List.of("src/App.java:0-2", "src/App.java:10-500"))
            .build();

    AccuracyEvidenceCitationPolicy.validate(files, "run-1", report);

    assertThat(report.getEvidenceStatus()).isEqualTo("UNCITED");
    assertThat(report.getEvidenceLocations()).isEmpty();
  }

  @Test
  void oneDimensionCannotAuthenticateAnotherAndArtifactsAreIneligible() {
    FileSystemTool files = mock(FileSystemTool.class);
    when(files.readFileRange("run-1", "src/App.java", 2, 4))
        .thenReturn("[Lines 2-4 of 20]\n   2 | code");
    AccuracyReport report =
        AccuracyReport.builder()
            .specEvidenceLocations(List.of("README.md:1", ".agent-context/evidence.json:1"))
            .endpointEvidenceLocations(List.of("src/App.java:2-4"))
            .testEvidenceLocations(List.of("target/test.log:1"))
            .build();

    AccuracyEvidenceCitationPolicy.validate(files, "run-1", report);

    assertThat(report.hasCitedSpecEvidence()).isFalse();
    assertThat(report.hasCitedEndpointEvidence()).isTrue();
    assertThat(report.hasCitedTestEvidence()).isFalse();
    assertThat(report.getEvidenceLocations()).containsExactly("src/App.java:2-4");
  }
}
