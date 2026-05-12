package com.navneet.executespec.adr;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E3.13 — composes the {@code {{PROJECT_ADRS}}} prompt fragment for planner / worker / reviewer.
 * Only ACCEPTED ADRs flow into context per plan §5.11.
 *
 * <p>Mirror of the contract-injection threshold pattern from E2.11: - ≤ {@code
 * adrs.injection.full-body-threshold} (default 15) → inline every ACCEPTED ADR's full body - >
 * threshold → compact one-liner index.
 *
 * <p><strong>Audit 2026-05-12 review item L #66:</strong> the {@code getAdr(id)} planner tool is
 * NOT wired yet, so when a project has > {@code fullBodyThreshold} ACCEPTED ADRs the planner only
 * sees the index. The prompt language below has been adjusted to stop promising tool retrieval.
 * Wiring a real {@code GetAdrTool} (returns ADR body by id, like {@code GetContractTool}) is a
 * follow-up; see {@code tool/} package for the contract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdrInjectionService {

  private final AdrRepository adrRepo;

  @Value("${adrs.injection.full-body-threshold:15}")
  private int fullBodyThreshold;

  public Result compose(String projectId) {
    List<Adr> accepted = adrRepo.findAcceptedForProject(projectId);
    if (accepted.isEmpty()) return new Result(Mode.EMPTY, "", List.of(), 0);
    if (accepted.size() <= fullBodyThreshold) return composeFullBody(accepted);
    return composeIndexOnly(accepted);
  }

  Result composeFullBody(List<Adr> adrs) {
    StringBuilder sb = new StringBuilder();
    sb.append("=== PROJECT_ADRS ===\n");
    sb.append(adrs.size())
        .append(" accepted ADR(s) for this project. ")
        .append("Treat each as a binding architectural decision.\n\n");
    List<UUID> ids = new java.util.ArrayList<>();
    for (Adr a : adrs) {
      ids.add(a.getId());
      sb.append("--- ADR ")
          .append(String.format("%04d", a.getAdrIdInRepo()))
          .append(": ")
          .append(a.getTitle())
          .append(" (decided ")
          .append(a.getDecisionDate())
          .append(") ---\n");
      sb.append(a.getBody() == null ? "(empty body)" : a.getBody()).append("\n\n");
    }
    return new Result(Mode.FULL_BODY, sb.toString(), ids, adrs.size());
  }

  Result composeIndexOnly(List<Adr> adrs) {
    StringBuilder sb = new StringBuilder();
    sb.append("=== PROJECT_ADRS_INDEX ===\n");
    sb.append("Project has ")
        .append(adrs.size())
        .append(" accepted ADRs. Below is the title + first-line teaser for each.")
        .append(
            " (Full bodies exceed the inline budget; the getAdr tool is not yet wired — if a"
                + " decision matters for this plan, mention it in the spec and the operator can"
                + " inline the body.)\n\n");
    List<UUID> ids = new java.util.ArrayList<>();
    for (Adr a : adrs) {
      ids.add(a.getId());
      sb.append("[")
          .append(String.format("%04d", a.getAdrIdInRepo()))
          .append("] ")
          .append(a.getTitle())
          .append(" (decided ")
          .append(a.getDecisionDate())
          .append(")\n");
      // First-line teaser from body for index entry
      if (a.getBody() != null) {
        String firstNonHeading = firstNonHeadingLine(a.getBody());
        if (firstNonHeading != null) {
          sb.append("  ").append(truncate(firstNonHeading, 200)).append("\n");
        }
      }
    }
    return new Result(Mode.INDEX_ONLY, sb.toString(), ids, adrs.size());
  }

  public int fullBodyThreshold() {
    return fullBodyThreshold;
  }

  static String firstNonHeadingLine(String body) {
    for (String line : body.split("\\r?\\n")) {
      String t = line.trim();
      if (t.isEmpty() || t.startsWith("#")) continue;
      return t;
    }
    return null;
  }

  static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  public enum Mode {
    EMPTY,
    FULL_BODY,
    INDEX_ONLY
  }

  public record Result(Mode mode, String block, List<UUID> adrIds, int totalAdrs) {}
}
