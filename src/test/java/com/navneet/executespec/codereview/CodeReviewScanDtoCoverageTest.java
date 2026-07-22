package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.codereview.entity.CodeReviewScan;

import org.junit.jupiter.api.Test;

/**
 * RR-1696 (persisted half of RR-1685) — the scan record persists total_files_scanned / total_loc
 * that a bounded walk can truncate. The controller DTO must carry the coverage marker so the
 * human-facing record does not state a possibly-truncated count as fact. A null (unrecorded)
 * coverage passes through so the reader can treat it as UNKNOWN rather than COMPLETE.
 */
class CodeReviewScanDtoCoverageTest {

  private static CodeReviewScan scanWithCoverage(String coverage) {
    return CodeReviewScan.builder()
        .projectId("p1")
        .repoFullName("owner/repo")
        .scannedSha("deadbeef")
        .status("COMPLETE")
        .triggerSource("MANUAL")
        .totalFilesScanned(42)
        .totalLoc(1000)
        .scanCoverage(coverage)
        .build();
  }

  @Test
  void dtoExposesTruncatedCoverage() {
    var dto =
        CodeReviewController.CodeReviewScanDto.from(scanWithCoverage("TRUNCATED_FILE_LIMIT"), true);
    assertThat(dto.scanCoverage()).isEqualTo("TRUNCATED_FILE_LIMIT");
  }

  @Test
  void dtoExposesCompleteCoverage() {
    var dto = CodeReviewController.CodeReviewScanDto.from(scanWithCoverage("COMPLETE"), true);
    assertThat(dto.scanCoverage()).isEqualTo("COMPLETE");
  }

  @Test
  void nullCoveragePassesThroughForLegacyRows() {
    var dto = CodeReviewController.CodeReviewScanDto.from(scanWithCoverage(null), true);
    assertThat(dto.scanCoverage()).isNull();
  }
}
