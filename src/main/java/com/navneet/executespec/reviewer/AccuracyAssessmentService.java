package com.navneet.executespec.reviewer;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.tool.FileSystemTool;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 3 of the review pipeline: Accuracy Assessment.
 *
 * <p>After code inspection (Phase 1) and auto-fix (Phase 2), this service makes a focused LLM call
 * that scores the generated code against the original user specification.
 *
 * <p>Accuracy dimensions measured:
 *
 * <ul>
 *   <li><b>Spec adherence (0-100)</b> — what % of explicitly stated requirements were implemented
 *   <li><b>Endpoint coverage</b> — for API specs, which HTTP endpoints are present vs missing
 *   <li><b>Schema accuracy</b> — do request/response payloads match the spec (HIGH/MEDIUM/LOW)
 *   <li><b>Test coverage</b> — NONE/PARTIAL/GOOD/COMPREHENSIVE relative to what spec implied
 *   <li><b>Non-functional coverage</b> — auth, pagination, rate limiting, error handling
 * </ul>
 *
 * <p>The assessment uses PLANNING tier (same as the reviewer) and runs with a short timeout — it
 * should complete in under 60 seconds. On any failure it returns a default conservative report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccuracyAssessmentService {

  private final FallbackLlmRouter fallbackLlmRouter;
  private final ModelResolver modelResolver;
  private final FileSystemTool fileSystemTool;
  private final StateStore stateStore;
  private final ObjectMapper mapper;

  /**
   * Strip leading/trailing markdown code fences from an LLM response. Inlined from the deleted V1
   * LlmResponseCleaner.
   */
  private static String stripMarkdownFences(String s) {
    if (s == null) return "";
    String t = s.trim();
    if (t.startsWith("```")) {
      int nl = t.indexOf('\n');
      if (nl > 0) t = t.substring(nl + 1);
      if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
    }
    return t.trim();
  }

  private static final String SYSTEM_PROMPT =
      """
You are a specification adherence auditor. Your ONLY job is to compare the ORIGINAL USER SPECIFICATION
against the FILE TREE, KEY SOURCE FILES, and REVIEW ISSUES to determine how accurately the generated
code implements what was asked for.

You are NOT re-reviewing the code quality — that was done already. You are measuring COMPLETENESS
and ACCURACY of the implementation relative to the specification.

OUTPUT FORMAT (strict JSON, no markdown fences):
{
  "specAdherenceScore": <0-100 integer>,
  "overallAccuracyGrade": "<A|B|C|D|F>",
  "gradingNotes": "<1-3 sentences explaining the grade>",
  "implementedRequirements": ["<requirement text>", ...],
  "missingRequirements": ["<requirement text>", ...],
  "partialRequirements": ["<requirement text that exists but is incomplete>", ...],
  "unknownRequirements": ["<requirement text where you have no evidence either way>", ...],
  "endpointCoverage": {
    "required": ["<METHOD /path>", ...],
    "implemented": ["<METHOD /path>", ...],
    "missing": ["<METHOD /path>", ...],
    "extra": ["<METHOD /path>", ...],
    "coveragePercent": <0-100>
  },
  "schemaAccuracy": "<HIGH|MEDIUM|LOW>",
  "testCoverageLevel": "<NONE|PARTIAL|GOOD|COMPREHENSIVE>",
  "nonFunctionalCoverage": "<COMPLETE|PARTIAL|MISSING>"
}

GRADING SCALE:
A = 90-100: All or nearly all requirements implemented correctly
B = 80-89: Most requirements implemented, minor gaps
C = 70-79: Core requirements met, some features missing
D = 60-69: Basic skeleton present, significant features absent
F = 0-59: Major portions of the spec are not implemented

UNKNOWN is NOT counted as a gap. Score and grade reflect implemented + missing + partial only —
absence of evidence is NOT evidence of absence. A spec with 8 IMPLEMENTED + 0 MISSING + 0 PARTIAL +
4 UNKNOWN should grade A, not D, because we have no evidence anything is wrong.

RULES — REQUIREMENT CLASSIFICATION (strict, four-way):

  IMPLEMENTED — a requirement is IMPLEMENTED only when you can point to a specific location in the
  KEY SOURCE FILES content that demonstrates the behaviour. Append the citation inline to each
  implementedRequirements string, e.g.
  "Slug length is 7 chars [src/services/shortener.ts:22-35]".
  A file name alone (without content you can read) is NOT sufficient — it's a hint, not proof.

  MISSING — reserved for NEGATIVE EVIDENCE only. A requirement is MISSING when the KEY SOURCE FILES
  content actively contradicts its presence:
    * the function/handler exists but its body is a stub (throw new Error("not implemented"),
      return None, pass, TODO comments, empty body)
    * the route handler exists but ignores the spec'd input or returns hardcoded data
    * the file containing the requirement IS in the snippet, you read it, and the behaviour is
      not there (no comparable function, no equivalent symbol, nothing that could plausibly
      satisfy the requirement)
  Do NOT mark MISSING simply because you didn't see the relevant code. Use UNKNOWN for that.

  PARTIAL — a requirement is PARTIAL if either:
    * a review issue explicitly flags it as incomplete (endpoint exists, validation logic missing)
    * the KEY SOURCE FILES show evidence the requirement is in progress: function signature
      present but only some branches handled, validation imported but not called, route mounted
      but no error handling, etc.

  UNKNOWN — the default when you have no evidence either way. Use UNKNOWN when:
    * no file in the KEY SOURCE FILES snippet matches the requirement's domain (e.g. you're
      looking for "rate limit" and the snippet has no auth/middleware/limiter file)
    * the relevant file IS in the FILE TREE but was NOT picked into the KEY SOURCE FILES snippet
    * the relevant file IS in the snippet but the truncation marker
      "... [truncated N chars in the middle; file is M chars total] ..." occurs at the location
      where the requirement would live
    * the requirement's vocabulary doesn't surface in any read file AND no review issue mentions it
  An UNKNOWN entry should include a short note explaining what you'd need to see to confirm,
  e.g. "Rate-limit policy — likely in middleware/, not in 6-file snippet".

  When in doubt between MISSING and UNKNOWN: choose UNKNOWN. False positives on MISSING are far
  more harmful (they tank the score and surface as critical issues to the user) than false
  positives on UNKNOWN (which are advisory).

ENDPOINT COVERAGE: extract ALL endpoints from the spec (e.g., "POST /api/users"), then check
KEY SOURCE FILES content for the route handler — not just file names. If you cannot read the
router file, leave coverage at the percent you can confirm and note the unread file in
gradingNotes; do NOT default to 0%.

SCORE COMPUTATION GUIDANCE:
- Base score primarily on the IMPLEMENTED count vs the IMPLEMENTED+MISSING+PARTIAL count.
  Formula heuristic: score ≈ 100 × (implemented + 0.5×partial) / max(1, implemented + missing + partial).
  UNKNOWN does not enter the denominator — it's neither credit nor penalty.
- README and documentation gaps count at most 5 points total toward specAdherenceScore.
- The specAdherenceScore must primarily reflect FUNCTIONAL compliance (endpoints, features,
  business logic).
- If spec has no explicit endpoints (non-API project), set endpointCoverage to null.
- schemaAccuracy = HIGH if spec defines schemas and code matches them; MEDIUM if minor
  deviations; LOW if major mismatches.
- testCoverageLevel = grade against what the spec REQUIRED, using these bands precisely. Do NOT
  default to PARTIAL out of caution — pick the band the evidence supports:
    * NONE — no test files exist, or tests are present but don't exercise any spec target.
    * PARTIAL — SOME spec-required test targets have a test, but at least one required target has
      NO test at all. (Use this only when a required target is genuinely untested.)
    * GOOD — EVERY spec-required test target has at least a happy-path test. If the spec says
      "tests covering each endpoint" and every endpoint has a passing-shape test, that is GOOD —
      not PARTIAL. This is the correct grade for a run that did what the spec's test ask required.
    * COMPREHENSIVE — every target has a happy-path test AND at least one error/edge-case test
      (e.g. 404/validation-failure paths), or the spec explicitly asked for thorough coverage and
      the suite delivers it.
  When the spec only implies tests loosely ("include a unit test"), one meaningful test = GOOD.
  Reserve PARTIAL for real gaps (a required endpoint/module with zero test), not for "could be
  more thorough."
- Return valid JSON. Do not wrap in markdown fences.
""";

  /**
   * Run the accuracy assessment for a completed run.
   *
   * @param runId run identifier (for token tracking and workspace access)
   * @param provider LLM provider name (e.g., "ANTHROPIC")
   * @param model resolved model for PLANNING tier
   * @param originalGoal the user's original specification text
   * @param issues review issues found in Phase 1 (already with fix results)
   * @return accuracy report, or a safe default on failure
   */
  public AccuracyReport assess(
      String runId,
      String provider,
      ResolvedModel model,
      String originalGoal,
      List<ReviewIssue> issues) {
    log.info(
        "[AccuracyAssessment] Starting assessment for run {} using {}", runId, model.getModel());

    try {
      String fileTree = getFileTree(runId);
      String keyFileSnippets = buildKeyFileSnippets(runId, fileTree);
      String issuesSummary = buildIssuesSummary(issues);
      String userMessage =
          buildAssessmentPrompt(originalGoal, fileTree, keyFileSnippets, issuesSummary);

      List<Message> messages = new ArrayList<>();
      messages.add(new SystemMessage(SYSTEM_PROMPT));
      messages.add(new UserMessage(userMessage));

      AnthropicChatOptions opts =
          "ANTHROPIC".equalsIgnoreCase(provider)
              ? AnthropicChatOptions.builder()
                  .temperature(0.0)
                  .cacheOptions(
                      AnthropicCacheOptions.builder()
                          .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                          .build())
                  .build()
              : null;
      Prompt prompt = opts != null ? new Prompt(messages, opts) : new Prompt(messages);

      FallbackLlmRouter.LlmResponse lr =
          fallbackLlmRouter.callWithFallbackTracked(
              provider,
              model.getModel(),
              prompt,
              runId,
              "accuracy-assessment",
              FallbackLlmRouter.FallbackMode.IMMEDIATE,
              0,
              false);

      ChatResponse response = lr.response();

      // Track tokens
      try {
        int inTok = response.getMetadata().getUsage().getPromptTokens().intValue();
        int outTok = response.getMetadata().getUsage().getCompletionTokens().intValue();
        stateStore.addTokensToRun(runId, inTok, outTok);
      } catch (Exception ex) {
        log.warn("[AccuracyAssessment] Could not extract token counts: {}", ex.getMessage());
      }

      String text = response.getResult().getOutput().getText();
      if (text == null || text.isBlank()) {
        log.warn("[AccuracyAssessment] Empty response from LLM — using default");
        return defaultReport(issues);
      }

      // Try to parse the accuracy report
      String cleaned = stripMarkdownFences(text).trim();
      if (!cleaned.startsWith("{")) {
        // Try to extract JSON object
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
          cleaned = cleaned.substring(start, end + 1);
        }
      }

      // First attempt: try parsing directly
      AccuracyReport report = null;
      try {
        report = mapper.readValue(cleaned, AccuracyReport.class);
      } catch (Exception e1) {
        log.warn("[AccuracyAssessment] First parse failed, attempting retry: {}", e1.getMessage());
        // Retry: ask model to fix its JSON
        messages.add(new AssistantMessage(text));
        messages.add(
            new UserMessage(
                "Your response was not valid JSON. Return ONLY a valid JSON object matching the"
                    + " required format. No markdown, no explanation."));
        try {
          FallbackLlmRouter.LlmResponse retryLr =
              fallbackLlmRouter.callWithFallbackTracked(
                  provider,
                  model.getModel(),
                  opts != null ? new Prompt(messages, opts) : new Prompt(messages),
                  runId,
                  "accuracy-assessment-retry",
                  FallbackLlmRouter.FallbackMode.IMMEDIATE,
                  1,
                  false);
          String retryText = retryLr.response().getResult().getOutput().getText();
          String retryCleaned = stripMarkdownFences(retryText).trim();
          report = mapper.readValue(retryCleaned, AccuracyReport.class);
        } catch (Exception e2) {
          log.warn("[AccuracyAssessment] Retry parse also failed: {}", e2.getMessage());
          return defaultReport(issues);
        }
      }

      // Clamp score to valid range
      if (report.getSpecAdherenceScore() < 0 || report.getSpecAdherenceScore() > 100) {
        report.setSpecAdherenceScore(Math.max(0, Math.min(100, report.getSpecAdherenceScore())));
      }

      // Ensure grade is consistent with score
      if (report.getOverallAccuracyGrade() == null || report.getOverallAccuracyGrade().isBlank()) {
        report.setOverallAccuracyGrade(gradeFromScore(report.getSpecAdherenceScore()));
      }

      // 2026-05-13 — Layer 4 execution-time filter (audit run 6024f510 RC-2). The accuracy
      // LLM marks "commit with message X" / "push to branch" / "verify by running X --check
      // exit 0" as MISSING because the post-run workspace snapshot has no commit log or
      // process output. Those steps DID happen (auto-push at run-end + buildProject smoke)
      // but they're not visible in static file evidence. Drop those requirements from
      // missingRequirements before they synthesize CRITICAL ReviewIssues and tank
      // verifiedScore. Runs against `repos/<name>/` subdirs hit this hardest because the
      // workspace evidence is even further from the post-push state.
      filterExecutionTimeRequirements(runId, report);

      // 2026-05-10 — Layer 3 grep-verify: programmatic anti-hallucination pass. The LLM
      // sometimes marks requirements as MISSING because the implementing file wasn't picked
      // into the 6/18-file KEY SOURCE FILES snippet, even with the new prompt nudging UNKNOWN
      // for absence-of-evidence. As a defense-in-depth check, walk each missingRequirements
      // entry, extract its distinctive tokens, and grep the workspace. If a sufficient
      // fraction of tokens hit, the requirement is likely implemented somewhere we didn't
      // read — move it to unknownRequirements (advisory) instead of leaving it as MISSING
      // (which would synthesize a CRITICAL ReviewIssue and tank the score).
      verifyMissingByGrep(runId, report);

      log.info(
          "[AccuracyAssessment] Complete for run {}: {} ({}/100) — {} missing, {} partial,"
              + " {} unknown",
          runId,
          report.getOverallAccuracyGrade(),
          report.getSpecAdherenceScore(),
          report.getMissingRequirements() != null ? report.getMissingRequirements().size() : 0,
          report.getPartialRequirements() != null ? report.getPartialRequirements().size() : 0,
          report.getUnknownRequirements() != null ? report.getUnknownRequirements().size() : 0);

      return report;

    } catch (Exception e) {
      log.error("[AccuracyAssessment] Unexpected failure for run {}: {}", runId, e.getMessage(), e);
      return defaultReport(issues);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Layer 4 — drop requirements whose evidence is execution-time / VCS-only.
  // The pipeline's post-run snapshot can't show whether `git commit` happened,
  // whether a push landed, or whether a `--check` invocation exited 0. Those
  // steps run AFTER the file-evidence snapshot used by the accuracy LLM, so
  // the LLM marks them MISSING even when they did happen. Audit run 6024f510
  // (Black formatting fix) verifiedScore tanked from a near-CLEAN ~85 to 43
  // because three execution-time requirements (commit message, push, --check
  // exit code) all dropped to MISSING and synthesized CRITICAL issues.
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Patterns that mark a requirement as execution-time / VCS — i.e. its truth cannot be established
   * from the post-run workspace snapshot the accuracy LLM sees. Conservative list: each pattern
   * matches the requirement as a whole (case-insensitive, regex). Adding a pattern here drops every
   * matching requirement out of the {@code missingRequirements} list before those become CRITICAL
   * ReviewIssues.
   */
  private static final java.util.List<java.util.regex.Pattern> EXECUTION_TIME_PATTERNS =
      java.util.List.of(
          // Git VCS verbs
          java.util.regex.Pattern.compile(
              "(?i).*\\b(commit|committing|committed)\\b.*\\bmessage\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\bpush(es|ed|ing)?\\b\\s+to\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\bpush(es|ed|ing)?\\b\\s+the\\s+.*branch\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\bopen\\s+(a\\s+)?pull\\s+request\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\b(open|create)\\s+(a\\s+)?PR\\b.*"),
          // Verify-by-running execution claims
          java.util.regex.Pattern.compile("(?i).*\\bverify\\b.*\\b(by\\s+)?running\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\bexits?\\b\\s+with\\s+code\\s*0\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\bexit\\s+code\\s*0\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\bconfirm(ing|ed)?\\b.*\\bexits?\\b.*"),
          // Reformatting / formatter execution (the file content is the evidence; the
          // "ran black" claim is not separately verifiable from a snapshot).
          java.util.regex.Pattern.compile("(?i).*\\brun\\s+black\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\bblack\\s+--check\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\brun\\s+prettier\\b.*"),
          java.util.regex.Pattern.compile("(?i).*\\brun\\s+(npm\\s+)?(lint|test)\\b.*"));

  /**
   * Drop missingRequirements entries that look like execution-time / VCS steps which aren't visible
   * in the workspace evidence the accuracy LLM was given. Mutates the report in-place. Audit
   * 2026-05-13 run 6024f510 RC-2.
   */
  private void filterExecutionTimeRequirements(String runId, AccuracyReport report) {
    java.util.List<String> missing = report.getMissingRequirements();
    if (missing == null || missing.isEmpty()) return;

    java.util.List<String> kept = new java.util.ArrayList<>();
    java.util.List<String> dropped = new java.util.ArrayList<>();
    for (String req : missing) {
      if (req == null || req.isBlank()) continue;
      boolean isExecutionTime = false;
      for (java.util.regex.Pattern p : EXECUTION_TIME_PATTERNS) {
        if (p.matcher(req).matches()) {
          isExecutionTime = true;
          break;
        }
      }
      if (isExecutionTime) {
        dropped.add(req);
      } else {
        kept.add(req);
      }
    }

    if (!dropped.isEmpty()) {
      log.info(
          "[AccuracyAssessment] run {}: dropped {} of {} missingRequirements as"
              + " execution-time/VCS (not verifiable from workspace snapshot): {}",
          runId,
          dropped.size(),
          missing.size(),
          dropped);
      report.setMissingRequirements(kept);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Layer 3 — grep verification of missing-requirement claims
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Stopwords filtered out of {@link #extractKeywords(String)}. Both common English ("must",
   * "should", "from") and "spec-shaped" filler ("requirement", "implement", "feature") that adds no
   * signal because every spec sentence contains them.
   */
  private static final java.util.Set<String> KEYWORD_STOPWORDS =
      java.util.Set.of(
          // common English
          "this",
          "that",
          "these",
          "those",
          "with",
          "from",
          "into",
          "onto",
          "upon",
          "have",
          "been",
          "must",
          "should",
          "would",
          "could",
          "shall",
          "will",
          "when",
          "while",
          "where",
          "what",
          "which",
          "such",
          "than",
          "then",
          "also",
          "each",
          "every",
          "some",
          "many",
          "more",
          "most",
          "other",
          "their",
          "there",
          "after",
          "before",
          "during",
          "between",
          "without",
          "across",
          "about",
          "above",
          "below",
          "again",
          "still",
          "however",
          "because",
          "since",
          // spec/code filler
          "spec",
          "specification",
          "feature",
          "functionality",
          "support",
          "supports",
          "supported",
          "implement",
          "implements",
          "implemented",
          "implementation",
          "requirement",
          "requirements",
          "required",
          "needs",
          "needed",
          "create",
          "creates",
          "created",
          "creating",
          "build",
          "builds",
          "built",
          "building",
          "allow",
          "allows",
          "allowed",
          "ensure",
          "ensures",
          "ensured",
          "provide",
          "provides",
          "provided",
          "return",
          "returns",
          "returned",
          "handle",
          "handles",
          "handled",
          "process",
          "processes",
          "processed",
          "value",
          "values",
          "data",
          "list",
          "item",
          "items",
          "type",
          "types",
          "name",
          "names",
          "result",
          "results");

  /**
   * Programmatic verification of {@code missingRequirements}. For each item, extract distinctive
   * keywords and grep the workspace. If ≥ ⌈K/2⌉ of K tokens have hits, demote the entry from
   * MISSING to UNKNOWN with a "[grep-verified]" marker — the file likely exists but wasn't in the
   * snippet we showed the LLM.
   *
   * <p>Mutates the report in-place. No-op when missingRequirements is null/empty or when {@link
   * FileSystemTool#searchFiles} is unavailable.
   */
  private void verifyMissingByGrep(String runId, AccuracyReport report) {
    java.util.List<String> missing = report.getMissingRequirements();
    if (missing == null || missing.isEmpty()) return;

    java.util.List<String> stillMissing = new java.util.ArrayList<>();
    java.util.List<String> verifiedToUnknown = new java.util.ArrayList<>();

    for (String req : missing) {
      java.util.Set<String> tokens = extractKeywords(req);
      if (tokens.isEmpty()) {
        stillMissing.add(req);
        continue;
      }
      int found = 0;
      for (String tok : tokens) {
        try {
          String result =
              fileSystemTool.searchFiles(runId, java.util.regex.Pattern.quote(tok), ".");
          if (result != null
              && !result.isBlank()
              && !"(no matches)".equals(result.trim())
              && !result.startsWith("Search failed")) {
            found++;
          }
        } catch (Exception ex) {
          // Don't let a single grep failure abort the whole verify pass.
          log.debug(
              "[AccuracyAssessment] grep-verify token search failed for run={} tok={}: {}",
              runId,
              tok,
              ex.getMessage());
        }
      }
      int threshold = Math.max(1, (tokens.size() + 1) / 2);
      if (found >= threshold) {
        verifiedToUnknown.add(
            req
                + " [grep-verified: "
                + found
                + "/"
                + tokens.size()
                + " tokens found in workspace]");
      } else {
        stillMissing.add(req);
      }
    }

    if (!verifiedToUnknown.isEmpty()) {
      log.info(
          "[AccuracyAssessment] grep-verify run {}: demoted {} of {} missing → unknown"
              + " (workspace had hits for the requirement's distinctive tokens)",
          runId,
          verifiedToUnknown.size(),
          missing.size());
      report.setMissingRequirements(stillMissing);

      java.util.List<String> existingUnknown =
          report.getUnknownRequirements() != null
              ? new java.util.ArrayList<>(report.getUnknownRequirements())
              : new java.util.ArrayList<>();
      existingUnknown.addAll(verifiedToUnknown);
      report.setUnknownRequirements(existingUnknown);
    }
  }

  /**
   * Tokenise a requirement string into the distinctive keywords we'll grep for. Strips inline
   * citations like {@code [src/foo.ts:22-35]}, lowercases, splits on non-word chars, drops
   * stopwords + numeric-only + tokens shorter than 4 chars. Caps at 6 tokens (first-N) so the
   * verify pass never balloons the per-call latency.
   */
  static java.util.Set<String> extractKeywords(String requirement) {
    if (requirement == null) return java.util.Set.of();
    // Drop inline citations / markers — they're either file paths (which the grep would match
    // against itself) or grep-verified markers from a prior pass.
    String cleaned = requirement.replaceAll("\\[[^\\]]+\\]", " ");
    String[] toks = cleaned.toLowerCase(java.util.Locale.ROOT).split("\\W+");
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
    for (String t : toks) {
      if (t.length() < 4) continue;
      if (KEYWORD_STOPWORDS.contains(t)) continue;
      if (t.matches("\\d+")) continue;
      out.add(t);
      if (out.size() >= 6) break;
    }
    return out;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Prompt builder
  // ──────────────────────────────────────────────────────────────────────────

  private String buildAssessmentPrompt(
      String originalGoal, String fileTree, String keyFileSnippets, String issuesSummary) {
    String filesSection =
        keyFileSnippets.isBlank()
            ? "(no key source files available — rely on file tree)"
            : keyFileSnippets;
    return String.format(
        """
=== ORIGINAL SPECIFICATION (this is what the user asked for) ===
%s

=== GENERATED FILE TREE (files that were created) ===
%s

=== KEY SOURCE FILES (actual code — use this to verify endpoint/feature existence) ===
%s

=== REVIEW ISSUES FOUND (critical and minor issues identified in code review) ===
%s

=== TASK ===
Based on the specification, file tree, source files, and review issues above:
1. Extract every explicit requirement from the specification
2. Check KEY SOURCE FILES content to verify each requirement is actually implemented
3. Mark as IMPLEMENTED only if you find the implementation in source files or file tree
4. Mark as MISSING only if you find NO evidence in any source file
5. Mark as PARTIAL if a review issue flags it incomplete, or if code exists but is clearly incomplete
6. For API specs: check KEY SOURCE FILES for route handlers matching each required endpoint
7. Assign a specAdherenceScore (0-100) — README/doc gaps max 5 pts penalty

Return your assessment as a JSON object. No markdown fences. No explanation outside the JSON.
""",
        originalGoal, fileTree, filesSection, issuesSummary);
  }

  private String buildIssuesSummary(List<ReviewIssue> issues) {
    if (issues == null || issues.isEmpty()) {
      return "No issues found during code review — code passed review.";
    }

    StringBuilder sb = new StringBuilder();
    long critical = issues.stream().filter(i -> i.getSeverity() == IssueSeverity.CRITICAL).count();
    long minor = issues.stream().filter(i -> i.getSeverity() == IssueSeverity.MINOR).count();
    sb.append(String.format("Total: %d critical, %d minor issues\n\n", critical, minor));

    issues.stream()
        .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
        .limit(10)
        .forEach(
            i ->
                sb.append(
                    String.format(
                        "[CRITICAL/%s] %s — %s (fixed=%s)\n",
                        i.getCategory(),
                        i.getId(),
                        i.getDescription(),
                        i.isAutoFixed() && i.isFixSucceeded() ? "YES" : "NO")));

    issues.stream()
        .filter(i -> i.getSeverity() == IssueSeverity.MINOR)
        .limit(5)
        .forEach(
            i ->
                sb.append(
                    String.format(
                        "[MINOR/%s] %s — %s\n", i.getCategory(), i.getId(), i.getDescription())));

    return sb.toString().trim();
  }

  private String getFileTree(String runId) {
    try {
      // Fix E (run a076c876, 2026-05-03): multi-repo workspaces have repos/<a>/,
      // repos/<b>/, repos/<c>/ subtrees. The flat listFilesRecursive sorts
      // alphabetically and truncates at 400 — for a 3-repo project with hundreds
      // of files per repo, the alphabetically-first repo fills the budget before
      // the third is reached. The accuracy LLM then "concludes" the missing repos
      // are absent and emits MISSING_FEATURE issues that tank architectureScore.
      // The per-repo helper allocates a separate budget per repos/<name>/ subdir
      // so every repo gets representation in the listing. Single-repo workspaces
      // fall through to the legacy flat listing inside the helper.
      return fileSystemTool.listFilesRecursivePerRepo(runId, 250, 800);
    } catch (Exception e) {
      log.warn(
          "[AccuracyAssessment] Could not get file tree for run {}: {}", runId, e.getMessage());
      return "(file tree unavailable)";
    }
  }

  /**
   * Reads key source files (routes, controllers, models, entry points) and returns their content as
   * annotated snippets for the accuracy scorer. Cap is whatever {@link #inferKeyFilePaths} hands
   * back — single-repo runs stay at 6, multi-repo runs scale up to {@code MULTI_REPO_TOTAL_CAP} (18
   * by default = 6 per repo × 3 repos).
   *
   * <p>P1.1 (2026-05-06, GA-readiness audit follow-up): the previous per-file cap was 2,500 chars,
   * which truncated FastAPI / Express routers with 5+ endpoints to imports + the first 2 handlers —
   * and produced 14× "controller exists but content not readable" lines in the run-{@code 0444c95f}
   * validation report (see {@code docs/plans/pending-ga-readiness-90-day-execution-plan.md} §P1.1).
   * The fix: per-file cap raised to 10,000 chars with head-plus-tail truncation so route handlers
   * near the end of long files stay visible. Total snippet bytes bounded at {@code
   * TOTAL_SNIPPETS_CHAR_CAP} (100K) to keep prompt token cost predictable. Files that come back as
   * an error string from {@link FileSystemTool#readFile} ("ACCESS DENIED:" / "Cannot read") are
   * skipped instead of being passed to the LLM as if they were source.
   */
  private String buildKeyFileSnippets(String runId, String fileTree) {
    List<String> candidates = inferKeyFilePaths(fileTree);
    if (candidates.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    int totalChars = 0;
    for (String path : candidates) {
      if (totalChars >= TOTAL_SNIPPETS_CHAR_CAP) {
        log.debug(
            "[AccuracyAssessment] Total snippet cap ({} chars) reached after {} files; remaining"
                + " skipped",
            TOTAL_SNIPPETS_CHAR_CAP,
            sb.length() == 0 ? 0 : (sb.toString().split("(?m)^### ").length - 1));
        break;
      }
      try {
        String content = fileSystemTool.readFile(runId, path);
        if (content == null || content.isBlank()) continue;
        if (isReadErrorMessage(content)) {
          log.debug(
              "[AccuracyAssessment] Skipping unreadable file {} for run {}: {}",
              path,
              runId,
              content.lines().findFirst().orElse(""));
          continue;
        }
        String snip = truncateForSnippet(content);
        sb.append("### ").append(path).append("\n```\n").append(snip).append("\n```\n\n");
        totalChars += snip.length();
      } catch (Exception e) {
        log.debug(
            "[AccuracyAssessment] Could not read key file {} for run {}: {}",
            path,
            runId,
            e.getMessage());
      }
    }
    return sb.toString();
  }

  /** Per-file content cap. Was 2,500; bumped 2026-05-06 to give multi-endpoint routers headroom. */
  static final int PER_FILE_CHAR_CAP = 10_000;

  /** Head bytes when truncating a file longer than {@link #PER_FILE_CHAR_CAP}. */
  static final int HEAD_CHARS = 6_000;

  /**
   * Tail bytes when truncating a file longer than {@link #PER_FILE_CHAR_CAP}. Keeps route handlers
   * near the end of long files visible to the LLM — the bug that emitted "controller content not
   * readable" 14× on run {@code 0444c95f}.
   */
  static final int TAIL_CHARS = 3_000;

  /**
   * Hard cap on total snippet bytes the assessor sends to the LLM. 100K chars ≈ 25K tokens — about
   * what a Sonnet PLANNING-tier call can absorb without crowding the spec + file-tree + issue
   * sections. With {@link #MULTI_REPO_TOTAL_CAP} = 18 files at 10K each = 180K worst case, this cap
   * cuts off the tail rather than crashing.
   */
  static final int TOTAL_SNIPPETS_CHAR_CAP = 100_000;

  /**
   * Truncate a file's content for snippet inclusion. Files ≤ {@link #PER_FILE_CHAR_CAP} are
   * returned as-is. Larger files get a head-plus-tail snippet with an explicit truncation marker so
   * the LLM never silently misses the bottom of a file.
   */
  static String truncateForSnippet(String content) {
    if (content == null) return "";
    if (content.length() <= PER_FILE_CHAR_CAP) return content;
    String head = content.substring(0, HEAD_CHARS);
    String tail = content.substring(content.length() - TAIL_CHARS);
    int omitted = content.length() - HEAD_CHARS - TAIL_CHARS;
    return head
        + "\n\n... [truncated "
        + omitted
        + " chars in the middle; file is "
        + content.length()
        + " chars total] ...\n\n"
        + tail;
  }

  /**
   * {@link FileSystemTool#readFile} returns its error text inline rather than throwing. Detect
   * those signals so we don't pass the error string to the LLM as if it were source code.
   */
  private static boolean isReadErrorMessage(String content) {
    if (content == null || content.length() > 256) return false;
    return content.startsWith("ACCESS DENIED:") || content.startsWith("Cannot read ");
  }

  /**
   * Per-repo read budget for multi-repo workspaces. Single-repo runs use {@link
   * #SINGLE_REPO_BUDGET}; runs whose file tree contains paths under {@code repos/<name>/} for two
   * or more distinct names get a per-repo budget of this size, capped at {@link
   * #MULTI_REPO_TOTAL_CAP} total files.
   */
  static final int PER_REPO_BUDGET = 6;

  /** Read budget for single-repo / no-repos-prefix workspaces. */
  static final int SINGLE_REPO_BUDGET = 6;

  /**
   * Hard cap on total files read for the accuracy assessment regardless of how many repos a run
   * touches. Keeps per-call token cost bounded (18 × 2,500 chars ≈ 45K chars ≈ 11K extra tokens).
   */
  static final int MULTI_REPO_TOTAL_CAP = 18;

  /**
   * Heuristically identifies key structural files from the file tree listing. Targets route
   * handlers, controllers, models, and entry points — the files most likely to contain endpoint
   * implementations the spec auditor needs to verify. Excludes test files, compiled output, and
   * vendored content (full list aligned with {@code VendoredPathGuard.VENDORED_DIRS}).
   *
   * <p>V162 (R-343 hotfix) added top-up: structural matches that don't fill the budget get padded
   * with the smallest non-structural source files so small projects (one structurally-named file +
   * the real logic in a sibling) don't see "file exists but content not readable" false-positives
   * across every spec requirement.
   *
   * <p>2026-05-02 multi-repo expansion: when the file tree contains paths under {@code
   * repos/<name>/} for two or more repos, allocate {@link #PER_REPO_BUDGET} per repo (capped at
   * {@link #MULTI_REPO_TOTAL_CAP} total). Closes the b171fa52 follow-up where a 3-repo workspace
   * had its 6-file budget burned on routes from one repo, leaving the assessor unable to confirm
   * spec items in the others ("ai.py router exists but content unread, cannot confirm" × N).
   */
  private List<String> inferKeyFilePaths(String fileTree) {
    if (fileTree == null || fileTree.isBlank()) return List.of();
    // Allow a broader extension set so shell, SQL, and config-as-code projects aren't
    // blind. Kotlin/Swift/Rust included for Android/iOS/systems code.
    java.util.regex.Pattern ext =
        java.util.regex.Pattern.compile(
            "(?i).*\\.(java|js|jsx|ts|tsx|py|go|rb|php|kt|kts|swift|rs|cs|scala|sh|sql)$");
    // exclusion list aligned with VendoredPathGuard.VENDORED_DIRS so the assessor's read budget
    // can never be wasted on vendored picks. workspace SKIP_DIRS already drops them, but
    // defense in depth: this regex also drops fixture / lock-file / generated paths.
    java.util.regex.Pattern exclude =
        java.util.regex.Pattern.compile(
            "(?i).*(test|spec|__pycache__|\\bvenv\\b|\\.venv|node_modules|target|build|dist|"
                + "\\.next|\\.gradle|coverage|bower_components|vendor|\\.idea|\\.vscode|"
                + "\\.pytest_cache|\\.mypy_cache|\\.tox|\\.nyc_output|\\.min\\.).*");
    java.util.regex.Pattern structural =
        java.util.regex.Pattern.compile(
            "(?i).*(route|router|controller|handler|resolver|service|model|schema|"
                + "index|main|app|server|api|entity|repository|dao).*");

    List<String> allSource =
        fileTree
            .lines()
            .map(String::trim)
            .map(l -> l.replaceAll("^[\\[DIR\\]│├└\\s─]+", "").trim())
            .filter(l -> !l.isBlank())
            .filter(l -> ext.matcher(l).matches())
            .filter(l -> !exclude.matcher(l).matches())
            .sorted(java.util.Comparator.comparingInt(String::length))
            .toList();

    if (allSource.isEmpty()) return List.of();

    // Group by repo subdir (paths starting with "repos/<name>/"). Files outside any repos/
    // prefix collapse into a single bucket "_root_" so a mixed workspace (some workspace-root
    // files + some per-repo files) still gets processed.
    java.util.regex.Pattern repoPrefix = java.util.regex.Pattern.compile("^repos/([^/]+)/");
    java.util.LinkedHashMap<String, java.util.List<String>> byRepo =
        new java.util.LinkedHashMap<>();
    for (String path : allSource) {
      java.util.regex.Matcher m = repoPrefix.matcher(path);
      String key = m.find() ? m.group(1) : "_root_";
      byRepo.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(path);
    }

    // Single bucket → flat budget. Preserves the V162 behavior for single-repo runs.
    if (byRepo.size() <= 1) {
      java.util.List<String> bucket = byRepo.values().iterator().next();
      return pickWithStructuralPreference(bucket, structural, SINGLE_REPO_BUDGET);
    }

    // Multi-repo → per-repo budget, capped at MULTI_REPO_TOTAL_CAP. Each repo gets at least
    // PER_REPO_BUDGET picks (or fewer if the cap runs out), so the LLM sees evidence from every
    // repo instead of 6 picks all from one.
    java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
    for (var entry : byRepo.entrySet()) {
      if (picked.size() >= MULTI_REPO_TOTAL_CAP) break;
      int repoBudget = Math.min(PER_REPO_BUDGET, MULTI_REPO_TOTAL_CAP - picked.size());
      java.util.List<String> repoPicks =
          pickWithStructuralPreference(entry.getValue(), structural, repoBudget);
      picked.addAll(repoPicks);
    }
    return java.util.List.copyOf(picked);
  }

  /**
   * Prefer structurally-named files (route, controller, service, …); cap structural picks at 10 so
   * a Spring Boot project with 30 controllers doesn't crowd out non-structural fallbacks. Top up
   * with the smallest non-structural source files until {@code budget} is reached so a project with
   * only one structurally-named file doesn't leave 5 slots empty (R-343 fix).
   *
   * <p>2026-05-02 — match the structural regex against the path with the {@code repos/<name>/}
   * prefix stripped, so the repo name itself doesn't trigger keywords. b171fa52 had a repo called
   * {@code pplai-api}; the original regex matched "api" inside the repo name and treated EVERY file
   * in pplai-api as structural — sorted by length, that meant {@code start.sh} + {@code
   * fix_files.py} won over {@code app/routers/ai.py}, which is exactly the file the accuracy
   * assessor needed to read. Stripping the prefix and re-matching against the repo-internal path
   * (e.g. {@code app/routers/ai.py}) makes the regex actually discriminate.
   *
   * <p>Boilerplate filenames ({@code index|main|app|server}.<ext>) are also DE-PRIORITISED by being
   * treated as non-structural for the structural-tier sort — they almost always exist (entry points
   * / Express server bootstrap / Next.js app shell) but rarely contain the business logic the
   * assessor needs to verify.
   */
  private static final java.util.regex.Pattern REPO_PREFIX_PATTERN =
      java.util.regex.Pattern.compile("^repos/[^/]+/");

  private static final java.util.regex.Pattern BOILERPLATE_FILENAME =
      java.util.regex.Pattern.compile("(?i)(^|/)(index|main|app|server)\\.[a-z0-9]+$");

  /**
   * TIER A — files inside a structural directory ({@code routers/}, {@code services/}, …). These
   * are nearly always the deliverable: the worker added a route to {@code app/routers/ai.py}, not
   * to a top-level {@code auth.py}. Anchored on the directory segment so a filename like {@code
   * Service.java} doesn't match (it would, in TIER B).
   */
  private static final java.util.regex.Pattern STRUCTURAL_DIR_PATTERN =
      java.util.regex.Pattern.compile(
          "(?i)(^|/)(routes?|routers?|controllers?|handlers?|resolvers?|services?|models?|"
              + "schemas?|entities|repositories|migrations?|api|apis|graphql|domain|usecases?|"
              + "components|hooks|pages)/");

  private static int structuralTier(String path, java.util.regex.Pattern filenameStructural) {
    String stripped = REPO_PREFIX_PATTERN.matcher(path).replaceFirst("");
    if (BOILERPLATE_FILENAME.matcher(stripped).find()) return 2; // bottom — entry-point fluff
    if (STRUCTURAL_DIR_PATTERN.matcher(stripped).find()) return 0; // top — under a structural dir
    if (filenameStructural.matcher(stripped).matches()) return 1; // middle — name only
    return 2; // non-structural
  }

  /**
   * Pick {@code budget} files from {@code source}, preferring structural directories over
   * structural filenames over non-structural fallbacks. Within a tier, shortest path wins as a
   * tiebreaker (smallest-first ordering preserved from caller via {@code source} sort).
   *
   * <p>2026-05-02 cap-at-10 from R-343 retained per tier to keep the read budget honest when a
   * Spring Boot project has 30 files in {@code controllers/}.
   */
  private static java.util.List<String> pickWithStructuralPreference(
      java.util.List<String> source, java.util.regex.Pattern structural, int budget) {
    if (budget <= 0 || source.isEmpty()) return java.util.List.of();
    java.util.List<String> tierA =
        source.stream().filter(p -> structuralTier(p, structural) == 0).limit(10).toList();
    java.util.List<String> tierB =
        source.stream().filter(p -> structuralTier(p, structural) == 1).limit(10).toList();
    java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
    for (String p : tierA) {
      if (picked.size() >= budget) break;
      picked.add(p);
    }
    for (String p : tierB) {
      if (picked.size() >= budget) break;
      picked.add(p);
    }
    // TIER C — top up with the smallest non-structural source files until budget is reached.
    for (String path : source) {
      if (picked.size() >= budget) break;
      picked.add(path);
    }
    return java.util.List.copyOf(picked);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Defaults and helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Conservative default when LLM call fails. Derives a rough score from review issue counts. */
  AccuracyReport defaultReport(List<ReviewIssue> issues) {
    long unfixedCritical =
        issues == null
            ? 0
            : issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
                .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
                .count();

    // Rough heuristic: each unfixed critical issue = -15 points
    int score = Math.max(0, 75 - (int) (unfixedCritical * 15));
    String grade = gradeFromScore(score);

    return AccuracyReport.builder()
        .specAdherenceScore(score)
        .overallAccuracyGrade(grade)
        .gradingNotes("Accuracy assessment unavailable — estimated from review issue count.")
        .implementedRequirements(List.of())
        .missingRequirements(List.of())
        .partialRequirements(List.of())
        .schemaAccuracy("MEDIUM")
        .testCoverageLevel("PARTIAL")
        .nonFunctionalCoverage("PARTIAL")
        .build();
  }

  private String gradeFromScore(int score) {
    if (score >= 90) return "A";
    if (score >= 80) return "B";
    if (score >= 70) return "C";
    if (score >= 60) return "D";
    return "F";
  }
}
