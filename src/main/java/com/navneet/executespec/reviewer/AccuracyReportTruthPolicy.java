package com.navneet.executespec.reviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.navneet.executespec.reviewer.model.AccuracyReport;

/** Deterministic consistency rules for the LLM-produced accuracy report. */
final class AccuracyReportTruthPolicy {

  private static final Pattern NEGATIVE_EVIDENCE =
      Pattern.compile(
          "(?i).*(\\bmissing\\b|\\bnot implemented\\b|\\bdoes not\\b|\\bdoesn't\\b|"
              + "\\baccepts? no\\b|\\bhas no\\b|\\blacks?\\b|\\bwithout\\b|"
              + "\\brather than\\b|\\bnot enforced\\b|\\bnot supported\\b).*");

  private static final List<String> EVIDENCE_LIMITATIONS =
      List.of(
          "not in the snippet",
          "not shown in the snippet",
          "not included in the evidence",
          "could not inspect",
          "unable to inspect",
          "need to see",
          "evidence unavailable",
          "file was not read",
          "file not provided");

  private AccuracyReportTruthPolicy() {}

  /**
   * Reclassifies UNKNOWN entries that actually admit a defect and prevents incomplete requirement
   * assessments from receiving an impossible A grade. Genuine UNKNOWN remains distinct from a
   * defect, but it still caps the claim below A: unavailable evidence cannot prove complete spec
   * adherence.
   */
  static void enforceConsistency(AccuracyReport report) {
    if (report == null) return;
    reclassifyContradictoryUnknowns(report);
    int missing =
        report.getMissingRequirements() == null ? 0 : report.getMissingRequirements().size();
    int partial =
        report.getPartialRequirements() == null ? 0 : report.getPartialRequirements().size();
    int unknown =
        report.getUnknownRequirements() == null ? 0 : report.getUnknownRequirements().size();
    if (missing > 0) {
      report.setSpecAdherenceScore(Math.min(69, report.getSpecAdherenceScore()));
      report.setOverallAccuracyGrade(gradeFromScore(report.getSpecAdherenceScore()));
    } else if (partial > 0) {
      report.setSpecAdherenceScore(Math.min(79, report.getSpecAdherenceScore()));
      report.setOverallAccuracyGrade(gradeFromScore(report.getSpecAdherenceScore()));
    } else if (unknown > 0) {
      report.setSpecAdherenceScore(Math.min(89, report.getSpecAdherenceScore()));
      report.setOverallAccuracyGrade(gradeFromScore(report.getSpecAdherenceScore()));
    }
  }

  private static void reclassifyContradictoryUnknowns(AccuracyReport report) {
    if (report.getUnknownRequirements() == null) return;
    List<String> unknown = new ArrayList<>();
    List<String> contradicted = new ArrayList<>();
    for (String requirement : report.getUnknownRequirements()) {
      if (isContradictoryUnknown(requirement)) contradicted.add(requirement);
      else if (requirement != null && !requirement.isBlank()) unknown.add(requirement);
    }
    if (contradicted.isEmpty()) return;

    List<String> partial =
        report.getPartialRequirements() == null
            ? new ArrayList<>()
            : new ArrayList<>(report.getPartialRequirements());
    partial.addAll(contradicted);
    report.setUnknownRequirements(List.copyOf(unknown));
    report.setPartialRequirements(List.copyOf(partial));
  }

  private static boolean isContradictoryUnknown(String requirement) {
    if (requirement == null || requirement.isBlank()) return false;
    String lower = requirement.toLowerCase(Locale.ROOT);
    if (EVIDENCE_LIMITATIONS.stream().anyMatch(lower::contains)) return false;
    return NEGATIVE_EVIDENCE.matcher(requirement).matches();
  }

  private static String gradeFromScore(int score) {
    if (score >= 90) return "A";
    if (score >= 80) return "B";
    if (score >= 70) return "C";
    if (score >= 60) return "D";
    return "F";
  }
}
