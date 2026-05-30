package com.navneet.executespec.assessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pilot R4 — heuristic feature assessment. Surveys the project's linked repos,
 * complexity-classifies the user request, and emits an {@link AssessmentResult} the planner can
 * consume + the UI can display before plan approval.
 *
 * <p>Deterministic for now (no LLM call) — keeps the assessment phase free of cost and latency.
 * Upgrade path: swap the body of {@link #assess} for a PLANNING-tier LLM call once we have signal
 * that the heuristic is too coarse.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureAssessmentService {

  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final ProjectArchitectureService archService;
  private final ProjectCodeMapRepository codeMapRepo;
  private final FeatureFlagService featureFlags;
  private final ObjectMapper objectMapper;
  private final RunCostTracker costTracker;

  @Autowired(required = false)
  @Qualifier("anthropicChatModel") private AnthropicChatModel anthropicModel;

  // B2 (audit 2026-05-29) — the assessment LLM call records its cost against the run via
  // RunCostTracker.record so the (flag-gated) path no longer escapes cost attribution. Label
  // mirrors the anthropicChatModel bean's configured model for accurate pricing.
  @Value("${spring.ai.anthropic.chat.options.model:claude-haiku-4-5-20251001}")
  private String anthropicAssessModel;

  private static final String FLAG_LLM_ENABLED = "assessment.llm.enabled";
  private static final int MAX_CODEMAP_CHARS = 8_000;

  public AssessmentResult assess(String runId, String projectId, String userRequest) {
    List<String> repoNames = repoNamesFor(projectId);

    // LLM path — when feature flag is on AND we have arch context AND the
    // anthropic bean is available. Any failure quietly falls through to
    // the heuristic so the user never blocks on assessment.
    if (featureFlags.isEnabled(FLAG_LLM_ENABLED)
        && anthropicModel != null
        && projectId != null
        && !repoNames.isEmpty()) {
      try {
        AssessmentResult llm = assessViaLlm(runId, projectId, userRequest, repoNames);
        if (llm != null) return llm;
      } catch (Exception e) {
        log.warn(
            "LLM assessment failed for project {}, falling back: {}", projectId, e.getMessage());
      }
    }

    return heuristicAssess(projectId, userRequest, repoNames);
  }

  private AssessmentResult heuristicAssess(
      String projectId, String userRequest, List<String> repoNames) {
    String complexity = classifyComplexity(userRequest);
    List<String> risks = inferRisks(userRequest);
    List<String> crossRepoDeps =
        projectId != null ? deriveCrossRepoDeps(projectId, repoNames) : List.of();
    String summary = buildSummary(userRequest, repoNames, complexity);
    return new AssessmentResult(
        summary, complexity, List.of(), List.of(), risks, crossRepoDeps, repoNames);
  }

  private AssessmentResult assessViaLlm(
      String runId, String projectId, String userRequest, List<String> repoNames) throws Exception {
    ProjectArchitectureDoc arch = archService.latest(projectId).orElse(null);
    String archMd = arch == null ? "" : arch.getContent();
    String annotations =
        arch == null || arch.getUserAnnotations() == null ? "" : arch.getUserAnnotations();
    String codeMapsJoined = joinedCodeMaps(projectId);

    String system =
        """
        You are a principal engineer assessing a feature request against an existing codebase.
        Return ONE valid JSON object matching this schema, no prose, no markdown fences:
        {
          "summary": "2 sentences max",
          "complexity": "S | M | L",
          "filesAffected": ["repo-relative paths likely to be modified"],
          "newFiles": ["repo-relative paths likely to be created"],
          "risks": ["short bullet"],
          "crossRepoDeps": ["short bullet"]
        }
        Use the architecture doc + codemaps as ground truth. Be terse.
        """;

    String user =
        """
        ## User request
        %s

        ## Repos in scope
        %s

        ## Architecture document
        %s

        ## User annotations
        %s

        ## Code maps (truncated to %d chars)
        %s
        """
            .formatted(
                userRequest == null ? "" : userRequest,
                String.join(", ", repoNames),
                truncate(archMd, 4000),
                truncate(annotations, 1500),
                MAX_CODEMAP_CHARS,
                truncate(codeMapsJoined, MAX_CODEMAP_CHARS));

    Prompt prompt = new Prompt(java.util.List.of(new SystemMessage(system), new UserMessage(user)));
    ChatResponse resp = anthropicModel.call(prompt);
    if (runId != null) {
      costTracker.record(runId, "anthropic", anthropicAssessModel, resp);
    }
    String text = resp.getResult().getOutput().getText();
    if (text == null || text.isBlank()) return null;

    // Strict JSON parse — strip any leading/trailing whitespace or fences just in case.
    String json = text.trim();
    int braceStart = json.indexOf('{');
    int braceEnd = json.lastIndexOf('}');
    if (braceStart < 0 || braceEnd <= braceStart) return null;
    json = json.substring(braceStart, braceEnd + 1);

    var parsed = objectMapper.readValue(json, LlmAssessment.class);
    return new AssessmentResult(
        parsed.summary == null ? "" : parsed.summary,
        normalizeComplexity(parsed.complexity),
        parsed.filesAffected == null ? List.of() : parsed.filesAffected,
        parsed.newFiles == null ? List.of() : parsed.newFiles,
        parsed.risks == null ? List.of() : parsed.risks,
        parsed.crossRepoDeps == null ? List.of() : parsed.crossRepoDeps,
        repoNames);
  }

  private List<String> repoNamesFor(String projectId) {
    List<String> out = new ArrayList<>();
    if (projectId == null) return out;
    for (ProjectRepoLink l : linkRepo.findByProjectId(projectId)) {
      repoRepo.findById(l.getRepoId()).map(ProjectRepo::getGithubRepoFullName).ifPresent(out::add);
    }
    return out;
  }

  private String joinedCodeMaps(String projectId) {
    StringBuilder sb = new StringBuilder();
    for (ProjectCodeMap cm : codeMapRepo.findByProjectId(projectId)) {
      sb.append("\n\n").append(cm.getContent());
      if (sb.length() > MAX_CODEMAP_CHARS) break;
    }
    return sb.toString();
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "\n…(truncated)";
  }

  private static String normalizeComplexity(String c) {
    if (c == null) return "M";
    String upper = c.trim().toUpperCase();
    return (upper.equals("S") || upper.equals("M") || upper.equals("L")) ? upper : "M";
  }

  /** Jackson POJO for the LLM's JSON output. */
  private static class LlmAssessment {
    public String summary;
    public String complexity;
    public List<String> filesAffected;
    public List<String> newFiles;
    public List<String> risks;
    public List<String> crossRepoDeps;
  }

  /** Convenience: serialise {@link #assess} output to JSON for persistence. */
  public String assessAsJson(String runId, String projectId, String userRequest) {
    try {
      return objectMapper.writeValueAsString(assess(runId, projectId, userRequest));
    } catch (Exception e) {
      log.warn(
          "Failed to serialise AssessmentResult for project {}: {}", projectId, e.getMessage());
      return null;
    }
  }

  private static String classifyComplexity(String userRequest) {
    if (userRequest == null) return "S";
    int len = userRequest.length();
    if (len < 200) return "S";
    if (len < 800) return "M";
    return "L";
  }

  private static List<String> inferRisks(String userRequest) {
    if (userRequest == null) return List.of();
    String lower = userRequest.toLowerCase();
    List<String> risks = new ArrayList<>();
    if (lower.contains("auth") || lower.contains("login") || lower.contains("password")) {
      risks.add("Authentication touched — review session/token handling carefully");
    }
    if (lower.contains("payment") || lower.contains("billing") || lower.contains("invoice")) {
      risks.add("Money path — double-check rounding, currency, and webhook idempotency");
    }
    if (lower.contains("migration") || lower.contains("schema") || lower.contains("alter table")) {
      risks.add("Schema change — verify backwards compatibility before deploy");
    }
    if (lower.contains("delete") || lower.contains("drop") || lower.contains("purge")) {
      risks.add("Destructive operation — confirm soft-delete semantics if user data is involved");
    }
    return risks;
  }

  private List<String> deriveCrossRepoDeps(String projectId, List<String> repoNames) {
    Optional<ProjectArchitectureDoc> doc = archService.latest(projectId);
    if (doc.isEmpty()) return List.of();
    // Lightweight: surface annotations as "human-declared cross-cutting context" lines.
    String annotations = doc.get().getUserAnnotations();
    if (annotations == null || annotations.isBlank()) {
      return repoNames.size() > 1
          ? List.of("Multi-repo work — confirm contracts between " + String.join(", ", repoNames))
          : List.of();
    }
    List<String> lines = new ArrayList<>();
    for (String line : annotations.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) lines.add(trimmed);
    }
    return lines;
  }

  private static String buildSummary(
      String userRequest, List<String> repoNames, String complexity) {
    StringBuilder sb = new StringBuilder();
    sb.append("Complexity: ").append(complexity).append(". ");
    if (!repoNames.isEmpty()) {
      sb.append("Touches ")
          .append(repoNames.size())
          .append(" repo(s): ")
          .append(String.join(", ", repoNames))
          .append(". ");
    }
    if (userRequest != null && userRequest.length() > 120) {
      sb.append("Request: ").append(userRequest, 0, 120).append("…");
    } else if (userRequest != null) {
      sb.append("Request: ").append(userRequest);
    }
    return sb.toString();
  }
}
