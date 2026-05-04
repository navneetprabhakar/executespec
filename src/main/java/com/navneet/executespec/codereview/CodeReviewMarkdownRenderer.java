package com.navneet.executespec.codereview;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.navneet.executespec.codereview.entity.CodeReviewIssue;
import com.navneet.executespec.codereview.entity.CodeReviewScan;

/**
 * Renders a {@link CodeReviewScan} + its issues as a single markdown document. Treated the same way
 * {@link com.navneet.executespec.codeintel.entity.RepoArchitectureDoc} treats its markdown — the
 * chat surface + a download endpoint hand it to the user verbatim.
 *
 * <p>Layout: header → summary → issues grouped by severity (CRITICAL → MAJOR → MINOR → INFO), each
 * issue with its file/line/category/source/suggestedFix → footer with cost + tokens. Pure
 * formatting, no LLM call, no DB queries.
 */
final class CodeReviewMarkdownRenderer {

  private CodeReviewMarkdownRenderer() {}

  static String render(
      CodeReviewScan scan,
      CodeReviewAgent.AgentResult agentResult,
      List<CodeReviewIssue> persisted,
      int droppedCount) {
    StringBuilder md = new StringBuilder();
    md.append("# Code review — ")
        .append(scan.getRepoFullName())
        .append(" @ ")
        .append(shortSha(scan.getScannedSha()))
        .append("\n\n");

    md.append("_Scanned ")
        .append(scan.getStartedAt())
        .append(" · ")
        .append(humanDuration(scan.getDurationMs()))
        .append(" · provider=")
        .append(safe(agentResult.provider()))
        .append(" · model=")
        .append(safe(agentResult.model()))
        .append("_\n\n");

    md.append("## Summary\n\n");
    md.append(safe(agentResult.summary())).append("\n\n");

    Map<String, Long> bySev =
        persisted.stream()
            .collect(Collectors.groupingBy(CodeReviewIssue::getSeverity, Collectors.counting()));
    md.append("**Counts:** ")
        .append(bySev.getOrDefault("CRITICAL", 0L))
        .append(" CRITICAL · ")
        .append(bySev.getOrDefault("MAJOR", 0L))
        .append(" MAJOR · ")
        .append(bySev.getOrDefault("MINOR", 0L))
        .append(" MINOR · ")
        .append(bySev.getOrDefault("INFO", 0L))
        .append(" INFO · ")
        .append(droppedCount)
        .append(" static findings dropped as false positives.\n\n");

    // Group issues by severity in our canonical order.
    String[] order = {"CRITICAL", "MAJOR", "MINOR", "INFO"};
    for (String sev : order) {
      List<CodeReviewIssue> bucket =
          persisted.stream().filter(i -> sev.equals(i.getSeverity())).toList();
      if (bucket.isEmpty()) continue;
      md.append("## ").append(sev).append("\n\n");
      Map<String, List<CodeReviewIssue>> byFile = new TreeMap<>();
      for (CodeReviewIssue i : bucket) {
        byFile.computeIfAbsent(i.getFilePath(), k -> new java.util.ArrayList<>()).add(i);
      }
      for (Map.Entry<String, List<CodeReviewIssue>> entry : byFile.entrySet()) {
        md.append("### `").append(entry.getKey()).append("`\n\n");
        for (CodeReviewIssue i : entry.getValue()) {
          md.append("- **L").append(i.getLineStart());
          if (i.getLineEnd() != null && !i.getLineEnd().equals(i.getLineStart())) {
            md.append("–").append(i.getLineEnd());
          }
          md.append("** · ")
              .append(safe(i.getCategory()))
              .append(" · _")
              .append(safe(i.getSource()))
              .append("_");
          if (i.getRuleId() != null && !i.getRuleId().isBlank()) {
            md.append(" · `").append(i.getRuleId()).append("`");
          }
          md.append("\n  ").append(safe(i.getMessage())).append("\n");
          if (i.getSuggestedFix() != null && !i.getSuggestedFix().isBlank()) {
            md.append("\n  **Suggested fix:**\n\n  ```\n  ");
            md.append(i.getSuggestedFix().replace("\n", "\n  "));
            md.append("\n  ```\n");
          }
          md.append("\n");
        }
      }
    }

    if (persisted.isEmpty()) {
      md.append("_No issues found — clean scan._\n\n");
    }

    if (!agentResult.droppedStaticFindings().isEmpty()) {
      md.append("## Dropped static findings (false positives)\n\n");
      for (CodeReviewAgent.DroppedStatic d : agentResult.droppedStaticFindings()) {
        md.append("- `")
            .append(safe(d.ruleId()))
            .append("` at `")
            .append(safe(d.filePath()))
            .append(':')
            .append(d.lineStart())
            .append("` — ")
            .append(safe(d.reason()))
            .append('\n');
      }
      md.append('\n');
    }

    md.append("---\n\n");
    md.append("_Cost: $")
        .append(format2(scan.getCostUsd()))
        .append(" · ")
        .append(scan.getTotalInputTokens())
        .append(" input · ")
        .append(scan.getTotalOutputTokens())
        .append(" output tokens_\n");
    return md.toString();
  }

  static String safe(String s) {
    return s == null ? "" : s;
  }

  static String shortSha(String sha) {
    if (sha == null) return "(none)";
    return sha.length() <= 7 ? sha : sha.substring(0, 7);
  }

  static String humanDuration(Long ms) {
    if (ms == null) return "—";
    Duration d = Duration.ofMillis(ms);
    if (d.toSeconds() < 60) return d.toSeconds() + "s";
    return d.toMinutesPart() + "m" + d.toSecondsPart() + "s";
  }

  static String format2(Double v) {
    if (v == null) return "0.00";
    return String.format(java.util.Locale.ROOT, "%.2f", v);
  }
}
