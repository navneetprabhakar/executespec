package com.navneet.executespec.project.techdebt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.finding.Finding;
import com.navneet.executespec.finding.FindingPatchSubmitter;
import com.navneet.executespec.finding.FindingType;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 2 (FindingPatchSubmitter rollout) — dispatches a patch-mode run that fixes every issue of
 * one category across the project's linked repos.
 *
 * <p>Categories follow ReviewerAgent's contract (LOGIC / SECURITY / TEST / STYLE / PERFORMANCE /
 * ARCHITECTURE / WIRING). One patch per category keeps each PR coherent — reviewers can grade
 * "fixed all the SECURITY findings" independently of "fixed all the STYLE findings."
 *
 * <p>Issue source: every {@link ValidationReport} stores an {@code issuesJson} array with {@code
 * category, severity, file, description}. We merge issues across the project's report history
 * (within the same lookback window {@link TechDebtService} uses), filter by category, and embed the
 * file + description list in the user prompt so the patch agent has concrete targets to read +
 * edit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TechDebtFixService {

  /** Hard cap on issues embedded in the prompt — keeps the user message under ~6KB. */
  private static final int MAX_ISSUES_IN_PROMPT = 30;

  private final ValidationReportRepository reportRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ObjectMapper objectMapper;
  private final FindingPatchSubmitter submitter;

  public FindingPatchSubmitter.SubmitResult dispatchFixForCategory(
      String projectId, String categoryRaw, AppUser user) {

    String category = categoryRaw == null ? "" : categoryRaw.trim().toUpperCase(Locale.ROOT);
    if (category.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category is required");
    }

    List<Map<String, Object>> issues = collectIssuesForCategory(projectId, category);
    if (issues.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "No " + category + " issues found in this project's recent validation reports.");
    }

    List<UUID> selectedRepoIds = resolveProjectRepoIds(projectId);
    if (selectedRepoIds.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Project has no linked repos — link one under Settings → Repos before dispatching.");
    }

    String prompt = buildPrompt(category, issues, selectedRepoIds.size());

    Finding finding =
        new Finding(
            FindingType.TECH_DEBT,
            // Combine projectId + category so dedupe keys correctly per category.
            // Replace `:` to keep the type/id separator unambiguous (the constructor
            // rejects raw ids with ':').
            (projectId + "|" + category).replace(':', '_'),
            projectId,
            selectedRepoIds,
            prompt,
            // No branchOverride — tech-debt patches land on a fresh
            // executespec/<runShort> branch + open a new PR per category.
            null,
            user.getId(),
            null);

    FindingPatchSubmitter.SubmitResult result = submitter.submit(finding);
    log.info(
        "TechDebtFix dispatch project={} category={} issues={} repos={} -> run={} ({})",
        projectId,
        category,
        issues.size(),
        selectedRepoIds.size(),
        result.runId(),
        result.outcome());
    return result;
  }

  // ── Issue aggregation ────────────────────────────────────────────────────

  private List<Map<String, Object>> collectIssuesForCategory(String projectId, String category) {
    List<ValidationReport> reports = reportRepo.findAllByProjectId(projectId);
    List<Map<String, Object>> matching = new ArrayList<>();
    for (ValidationReport r : reports) {
      String json = r.getIssuesJson();
      if (json == null || json.isBlank() || "[]".equals(json.trim())) continue;
      try {
        List<Map<String, Object>> issues =
            objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        for (Map<String, Object> issue : issues) {
          Object cat = issue.get("category");
          if (cat instanceof String s && s.trim().toUpperCase(Locale.ROOT).equals(category)) {
            matching.add(issue);
          }
        }
      } catch (Exception parseFailure) {
        // Older reports occasionally have non-array shapes; skip silently — TechDebtService
        // logs them in its own aggregator.
      }
    }
    // De-dup on (file, description) so re-running the same review three times doesn't
    // inflate the issue count.
    Map<String, Map<String, Object>> dedup = new HashMap<>();
    for (Map<String, Object> issue : matching) {
      String key =
          String.valueOf(issue.get("file")) + "::" + String.valueOf(issue.get("description"));
      dedup.putIfAbsent(key, issue);
    }
    return new ArrayList<>(dedup.values());
  }

  private List<UUID> resolveProjectRepoIds(String projectId) {
    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    List<UUID> out = new ArrayList<>(links.size());
    for (ProjectRepoLink l : links) out.add(l.getRepoId());
    return out;
  }

  // ── Prompt synthesis ─────────────────────────────────────────────────────

  private String buildPrompt(String category, List<Map<String, Object>> issues, int repoCount) {
    int total = issues.size();
    List<Map<String, Object>> shown =
        issues.size() > MAX_ISSUES_IN_PROMPT ? issues.subList(0, MAX_ISSUES_IN_PROMPT) : issues;

    StringBuilder sb = new StringBuilder();
    sb.append("Fix the ").append(total).append(" ").append(category);
    sb.append(" issue").append(total == 1 ? "" : "s");
    sb.append(" listed below. The project has ")
        .append(repoCount)
        .append(" linked repo")
        .append(repoCount == 1 ? "" : "s");
    sb.append(" cloned into the workspace; each issue's `file` is rooted at one of them.\n\n");

    sb.append("# Issues\n\n");
    int n = 1;
    for (Map<String, Object> issue : shown) {
      String file = String.valueOf(issue.getOrDefault("file", "<unknown>"));
      String severity = String.valueOf(issue.getOrDefault("severity", ""));
      String desc = String.valueOf(issue.getOrDefault("description", ""));
      sb.append(n++).append(". `").append(file).append("`");
      if (!severity.isBlank()) sb.append(" — ").append(severity);
      sb.append("\n   ").append(desc.replace("\n", "\n   ")).append("\n\n");
    }
    if (total > MAX_ISSUES_IN_PROMPT) {
      sb.append("…")
          .append(total - MAX_ISSUES_IN_PROMPT)
          .append(" more issues in this category — focus on the ones above first; ")
          .append("a follow-up run can sweep the long tail.\n\n");
    }

    sb.append("# How to work\n\n");
    sb.append("1. Group the issues by file. Read each file once; address every issue in it\n");
    sb.append("   in the same edit pass to keep the diff coherent.\n");
    sb.append("2. Apply minimum-change fixes. If an issue's description is ambiguous,\n");
    sb.append("   prefer the most conservative interpretation.\n");
    sb.append("3. Run the project's tests via `runTests` (when applicable) to confirm no\n");
    sb.append("   regression. Skip if the project has no test infrastructure for this layer.\n");
    sb.append("4. Commit will be pushed automatically to a fresh `executespec/<runShort>`\n");
    sb.append("   branch on completion + a draft PR opened.\n");
    sb.append("5. Call completeTask once every issue above has been addressed.\n\n");

    sb.append("# Constraints\n\n");
    sb.append("- One PR per category — do NOT mix in fixes for other categories.\n");
    sb.append("- Do NOT touch tests unless the issue description explicitly calls for it.\n");
    sb.append("- Do NOT reformat / reorder unrelated code in the same files.\n");
    return sb.toString();
  }
}
