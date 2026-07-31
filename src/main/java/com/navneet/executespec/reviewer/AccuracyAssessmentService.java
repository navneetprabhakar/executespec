package com.navneet.executespec.reviewer;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.FileSystemTool;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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

  private final LlmRouter llmRouter;
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
against the FILE TREE, KEY SOURCE FILES, TEST FILES, and REVIEW ISSUES to determine how accurately the
generated code implements what was asked for.

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
  "specEvidenceLocations": ["<workspace-relative-production-or-test-path>:<start>-<end>", ...],
  "endpointEvidenceLocations": ["<workspace-relative-source-path>:<start>-<end>", ...],
  "testEvidenceLocations": ["<workspace-relative-test-path>:<start>-<end>", ...],
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

UNKNOWN is not a defect and is excluded from the implementation denominator, but unavailable
evidence cannot prove complete adherence. Any UNKNOWN requirement caps specAdherenceScore at 89/B.
Absence of evidence is not evidence of absence; it is also not evidence of completion.

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

  UNKNOWN means evidence is unavailable. Never put a requirement in UNKNOWN when your own text
  says the implementation is missing, lacks support, accepts no required field, does not enforce
  the contract, or behaves differently from the requirement. Those are MISSING or PARTIAL.

ENDPOINT COVERAGE: extract ALL endpoints from the spec (e.g., "POST /api/users"), then check
KEY SOURCE FILES content for the route handler — not just file names. If you cannot read the
router file, leave coverage at the percent you can confirm and note the unread file in
gradingNotes; do NOT default to 0%.

ADVERSARIAL CONTRACT EVIDENCE:
- Never infer an endpoint from a test name, service method, OpenAPI entry, or the phrase "implied".
  Only a readable registered route/handler is implementation evidence.
- "Stable errors without leaking internals" is IMPLEMENTED only when readable production error
  handling maps raw persistence/provider failures to a sanitized public response. A test that only
  checks for a `detail` field is insufficient; returning `str(exception)` is negative evidence.
- A runnable service is not proven when integration fixtures replace the connection factory,
  manually initialize schema, or set configuration absent from the documented run command. If the
  default/runtime wiring uses a different ephemeral store per connection, classify startup/runtime
  persistence as MISSING or PARTIAL.
- Schema initialization is implemented only when the documented production start path invokes it
  (for example via an application lifespan/startup hook) or a mandatory pre-start migration command.
  A standalone initializer used only by tests is negative evidence, not runtime wiring.
- Idempotency is not proven by replaying one method alone when one global key store is shared by
  several operations. Check that operation/request identity is part of the stored contract so a key
  cannot return another endpoint's response type. Replay must preserve the original response
  snapshot; returning a mutable entity whose later state changes is not "the original result".
- A compound requirement is IMPLEMENTED only when every clause is proven. A similarly named class,
  field, table, handler, or test is lexical evidence, not behavioral proof. In particular, a
  `version` column or unused versioned repository method does not prove that API requests accept an
  expected version and return 409 on mismatch.
- Stable public errors are not proven by custom exception handlers if an expected persistence or
  framework failure can still escape as a raw exception/500. Returning `str(exc)` is negative
  evidence whenever it can expose database/provider details.
- Check response contracts field by field. A GET item response that omits required available and
  reserved quantities is PARTIAL or MISSING even when the route itself exists.
- Migration support is not proven by one arbitrary DDL call. If version advancement always writes
  the same next version, classify repeated migration support as PARTIAL.
- "Authenticated customer" is not proven by accepting a caller-controlled customer-id header,
  query parameter, or request field. Require readable credential/session/token verification that
  derives a trusted principal, binds the idempotency scope to that principal, and enforces the same
  owner boundary on read/cancel/update routes. A raw `X-Customer-Id` used as identity is negative
  evidence, even when its variable name says authenticated.
- Validate numeric business inputs at the public request boundary. If zero or negative quantities
  can reach arithmetic such as `stock = stock - quantity`, non-negative stock is not enough: a
  negative request can increase stock and create an invalid reservation. Classify that contract as
  MISSING or PARTIAL and downgrade test coverage when the suite omits the adversarial case.
- A migration file in the tree does not prove migration-backed startup when the documented/default
  application path calls ORM `create_all`/schema auto-creation instead of the migration runner.
- Concurrent idempotency is not proven by a race test that happens to schedule the first store
  before other lookups. Require one atomic lookup + business mutation + first-decision persistence
  boundary, exact replay responses, and quota/state consumption exactly once. Raw delimiter-
  concatenated tenant/subject/idempotency keys are collision evidence unless components are escaped
  or length-prefixed.
- "Bounded memory" requires readable eviction of all live request-serving buckets/maps and a test
  that advances the injected clock then observes their size fall. Starting eviction on a detached
  store while the limiter constructs another store is MISSING. Cancellation checked only before a
  blocking core call is PARTIAL/MISSING when the core interface cannot receive cancellation.
- A configurable tenant header copied directly into context is caller-selected identity, not
  authenticated middleware. Require credential verification that derives the tenant and a negative
  test proving the raw tenant header alone is rejected.
- Cross-feature reachability is part of implementation. If an empty/loading/error branch hides the
  only create/retry control, classify the affected primary action as MISSING even when isolated CRUD
  tests pass after pre-seeding state. Optional demo data must not be the only way to start.
- Persisted/recovered state must enforce the complete invariant: every required collection member
  exists exactly once, stable identities are unique, and values rejected interactively (for example
  blank titles) are also rejected during restore. Field type checks alone are not safe recovery.
- Do not infer responsive layout, visible focus, or WCAG contrast from component presence or DOM
  order. Require readable CSS/computed/browser evidence. A README claim is not proof, and a viewport
  test that asserts the same DOM structure at every width is not coverage of responsive rendering.

SCORE COMPUTATION GUIDANCE:
- Base score primarily on the IMPLEMENTED count vs the IMPLEMENTED+MISSING+PARTIAL count.
  Formula heuristic: score ≈ 100 × (implemented + 0.5×partial) / max(1, implemented + missing + partial).
  UNKNOWN does not enter the denominator, but one or more UNKNOWN rows cap the score at 89 because
  the evidence cannot support an A-grade completeness claim.
- README and documentation gaps count at most 5 points total toward specAdherenceScore.
- The specAdherenceScore must primarily reflect FUNCTIONAL compliance (endpoints, features,
  business logic).
- If spec has no explicit endpoints (non-API project), set endpointCoverage to null.
- schemaAccuracy = HIGH if spec defines schemas and code matches them; MEDIUM if minor
  deviations; LOW if major mismatches.
- testCoverageLevel = grade against what the spec REQUIRED, using these bands precisely. Do NOT
  default to PARTIAL out of caution — pick the band the evidence supports:
    * NONE — no test files exist, tests don't exercise any spec target, tests reimplement the target
      logic locally instead of invoking production, or fixtures disable/mock away the security,
      validation, middleware, persistence, or service boundary they claim to prove.
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
  more thorough." Inspect the TEST FILES section for this judgment; the KEY SOURCE FILES section
  intentionally excludes tests so implementation and test evidence have independent budgets.
  A green command is execution evidence only. It does not upgrade copied-logic or
  requirement-bypassing tests into coverage.
- Return valid JSON. Do not wrap in markdown fences.
- specEvidenceLocations must cite the readable production or test lines supporting the requirement
  classifications. endpointEvidenceLocations must independently cite implemented/missing endpoint
  claims. testEvidenceLocations must independently cite the tests supporting testCoverageLevel.
  Never reuse one dimension's citation as proof for another dimension. Do not cite a file you did
  not receive. The server re-reads and validates every location; invalid citations are discarded.
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
    return assess(runId, provider, model, originalGoal, List.of(), issues, null);
  }

  /**
   * Run the accuracy assessment against the canonical requirement ledger persisted with the plan.
   * The original goal remains available as context, but the assessor must not silently drop or
   * rewrite planner-extracted requirements while reconstructing them from prose.
   */
  public AccuracyReport assess(
      String runId,
      String provider,
      ResolvedModel model,
      String originalGoal,
      List<RequirementSpec> requirements,
      List<ReviewIssue> issues) {
    return assess(runId, provider, model, originalGoal, requirements, issues, null);
  }

  public AccuracyReport assess(
      String runId,
      String provider,
      ResolvedModel model,
      String originalGoal,
      List<RequirementSpec> requirements,
      List<ReviewIssue> issues,
      ExecutionContext operationContext) {
    log.info(
        "[AccuracyAssessment] Starting assessment for run {} using {}", runId, model.getModel());

    try {
      String fileTree = getFileTree(runId);
      String keyFileSnippets = buildKeyFileSnippets(runId, fileTree);
      String testFileSnippets = buildTestFileSnippets(runId, fileTree);
      String configFileSnippets = buildConfigFileSnippets(runId, fileTree);
      String issuesSummary = buildIssuesSummary(issues);
      String userMessage =
          buildAssessmentPrompt(
              originalGoal,
              requirements,
              fileTree,
              keyFileSnippets,
              testFileSnippets,
              configFileSnippets,
              issuesSummary);

      List<Message> messages = new ArrayList<>();
      messages.add(new SystemMessage(SYSTEM_PROMPT));
      messages.add(
          new UserMessage(PromptTrustBoundary.wrap("ACCURACY_ASSESSMENT_EVIDENCE", userMessage)));

      ChatResponse response =
          callAssessment(
              provider, model.getModel(), messages, runId, "assessment", operationContext);

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
          ChatResponse retryResponse =
              callAssessment(
                  provider,
                  model.getModel(),
                  messages,
                  runId,
                  "assessment-retry",
                  operationContext);
          String retryText = retryResponse.getResult().getOutput().getText();
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

      // UNKNOWN is only valid for absent evidence. Some model responses put a positively observed
      // defect in unknownRequirements (for example, "release accepts no idempotency key"). Repair
      // that contradiction deterministically so an admitted hard gap cannot retain an A grade.
      removeInventedEndpointCoverage(originalGoal, requirements, report);
      AccuracyReportTruthPolicy.enforceConsistency(report);
      AccuracyReportTruthPolicy.enforceDeterministicFailures(report, issues);
      AccuracyEvidenceCitationPolicy.validate(fileSystemTool, runId, report);

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

  private ChatResponse callAssessment(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String stepId,
      ExecutionContext operationContext) {
    if (operationContext == null) {
      return llmRouter.callText(
          provider, model, messages, runId, "AccuracyAssessmentService", stepId);
    }
    return llmRouter.call(
        LlmRequest.builder()
            .provider(provider)
            .model(model)
            .messages(messages)
            .context(operationContext.child("accuracy-" + stepId))
            .toolsOverride(List.of())
            .agentRole("AccuracyAssessmentService")
            .stepId(stepId)
            .build());
  }

  // Layer 4 — drop requirements whose evidence is execution-time / VCS-only.
  // The pipeline's post-run snapshot can't show whether `git commit` happened,
  // whether a push landed, or whether a `--check` invocation exited 0. Those
  // steps run AFTER the file-evidence snapshot used by the accuracy LLM, so
  // the LLM marks them MISSING even when they did happen. Audit run 6024f510
  // (Black formatting fix) verifiedScore tanked from a near-CLEAN ~85 to 43
  // because three execution-time requirements (commit message, push, --check
  // exit code) all dropped to MISSING and synthesized CRITICAL issues.

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
  // Package-private for RR-292 regression pinning (ValidationReport-free unit test).
  void filterExecutionTimeRequirements(String runId, AccuracyReport report) {
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
          "[AccuracyAssessment] run {}: reclassified {} of {} missingRequirements as"
              + " execution-time/VCS UNKNOWN (not verifiable from workspace snapshot): {}",
          runId,
          dropped.size(),
          missing.size(),
          dropped);
      report.setMissingRequirements(kept);
      // RR-292 — do NOT delete an execution-time / VCS requirement from the assessment. Its truth
      // is unknown from the workspace snapshot, not proven done: no VCS / command / build / test /
      // publication evidence is consulted here, so a user who explicitly asked for a commit / push
      // /
      // PR / successful verification must not silently lose the requirement (which would remove its
      // critical penalty and could upgrade the score). Retain it as UNKNOWN with a marker instead.
      java.util.List<String> existingUnknown =
          report.getUnknownRequirements() != null
              ? new java.util.ArrayList<>(report.getUnknownRequirements())
              : new java.util.ArrayList<>();
      for (String req : dropped) {
        existingUnknown.add(req + " [execution-time/VCS — not verifiable from workspace snapshot]");
      }
      report.setUnknownRequirements(existingUnknown);
    }
  }

  // Prompt builder

  private String buildAssessmentPrompt(
      String originalGoal,
      List<RequirementSpec> requirements,
      String fileTree,
      String keyFileSnippets,
      String testFileSnippets,
      String configFileSnippets,
      String issuesSummary) {
    String filesSection =
        keyFileSnippets.isBlank()
            ? "(no key source files available — rely on file tree)"
            : keyFileSnippets;
    String testsSection =
        testFileSnippets.isBlank() ? "(no readable test files available)" : testFileSnippets;
    String configSection =
        configFileSnippets.isBlank()
            ? "(no readable workflow/config files available)"
            : configFileSnippets;
    String requirementLedger = renderRequirementLedger(requirements);
    return String.format(
        """
=== ORIGINAL SPECIFICATION (this is what the user asked for) ===
%s

=== CANONICAL REQUIREMENT LEDGER (classify every row; preserve IDs and full clauses) ===
%s

=== GENERATED FILE TREE (files that were created) ===
%s

=== KEY SOURCE FILES (actual code — use this to verify endpoint/feature existence) ===
%s

=== TEST FILES (actual test code — use this to grade testCoverageLevel) ===
%s

=== WORKFLOW AND CONFIG FILES (actual bounded config evidence) ===
%s

=== REVIEW ISSUES FOUND (critical and minor issues identified in code review) ===
%s

=== TASK ===
Based on the specification, file tree, source files, and review issues above:
1. Classify every canonical requirement ledger row. If the ledger is empty, extract every explicit
   requirement from the original specification instead
2. Check KEY SOURCE FILES content to verify each requirement is actually implemented
3. Mark as IMPLEMENTED only if you find the implementation in source files or file tree
4. Mark as MISSING when readable evidence contradicts the requirement or shows the required
   behavior is absent; use UNKNOWN only when the relevant evidence was not provided
5. Mark as PARTIAL if a review issue flags it incomplete, or if code exists but is clearly incomplete
6. For API specs: check KEY SOURCE FILES for route handlers matching each required endpoint
7. Assign a specAdherenceScore (0-100) — README/doc gaps max 5 pts penalty

Return your assessment as a JSON object. No markdown fences. No explanation outside the JSON.
""",
        originalGoal,
        requirementLedger,
        fileTree,
        filesSection,
        testsSection,
        configSection,
        issuesSummary);
  }

  String renderRequirementLedger(List<RequirementSpec> requirements) {
    if (requirements == null || requirements.isEmpty()) {
      return "(legacy/empty plan ledger — extract requirements from the original specification)";
    }
    return requirements.stream()
        .filter(requirement -> requirement != null && requirement.text() != null)
        .map(
            requirement ->
                String.format(
                    "- %s [%s] %s",
                    requirement.id() == null ? "(no-id)" : requirement.id(),
                    requirement.kind(),
                    requirement.text()))
        .collect(java.util.stream.Collectors.joining("\n"));
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
   * back — single-repo runs stay bounded at 10, multi-repo runs scale up to {@code
   * MULTI_REPO_TOTAL_CAP}.
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

  /**
   * Reads a bounded, independent sample of generated test files for the coverage judgment.
   * Production-file inference intentionally excludes tests so they cannot consume the scarce
   * implementation-evidence budget. Before this separate lane existed, the assessor was still asked
   * to grade test coverage and therefore reported NONE even for concrete passing suites.
   */
  private String buildTestFileSnippets(String runId, String fileTree) {
    List<String> candidates = inferTestFilePaths(fileTree);
    if (candidates.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    int totalChars = 0;
    for (String path : candidates) {
      if (totalChars >= TEST_SNIPPETS_CHAR_CAP) break;
      try {
        String content = fileSystemTool.readFile(runId, path);
        if (content == null || content.isBlank() || isReadErrorMessage(content)) continue;
        String snip = truncateForSnippet(content);
        int remaining = TEST_SNIPPETS_CHAR_CAP - totalChars;
        if (snip.length() > remaining) snip = snip.substring(0, remaining);
        sb.append("### ").append(path).append("\n```\n").append(snip).append("\n```\n\n");
        totalChars += snip.length();
      } catch (Exception e) {
        log.debug(
            "[AccuracyAssessment] Could not read test file {} for run {}: {}",
            path,
            runId,
            e.getMessage());
      }
    }
    return sb.toString();
  }

  /**
   * Reads workflow and configuration evidence through an independent budget. CI/CD requirements
   * must not lose their only evidence because controller/service source files consumed the source
   * budget. YAML is parsed with SnakeYAML's safe constructor and bounded loader options before its
   * raw, truncated content is shown to the assessor; malformed YAML is labelled rather than
   * silently treated as a valid workflow.
   */
  private String buildConfigFileSnippets(String runId, String fileTree) {
    List<String> candidates = inferConfigEvidencePaths(fileTree);
    if (candidates.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    int totalChars = 0;
    for (String path : candidates) {
      if (totalChars >= CONFIG_SNIPPETS_CHAR_CAP) break;
      try {
        String content = fileSystemTool.readFile(runId, path);
        if (content == null || content.isBlank() || isReadErrorMessage(content)) continue;
        String yamlProblem = isYaml(path) ? safeYamlProblem(content) : null;
        String snip = truncateForSnippet(content);
        int remaining = CONFIG_SNIPPETS_CHAR_CAP - totalChars;
        if (snip.length() > remaining) snip = snip.substring(0, remaining);
        sb.append("### ").append(path).append("\n");
        if (yamlProblem != null) {
          sb.append("[INVALID YAML: ").append(yamlProblem).append("]\n");
        }
        sb.append("```\n").append(snip).append("\n```\n\n");
        totalChars += snip.length();
      } catch (Exception e) {
        log.debug(
            "[AccuracyAssessment] Could not read config file {} for run {}: {}",
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

  /** Independent cap for test evidence so it cannot crowd implementation evidence from context. */
  static final int TEST_SNIPPETS_CHAR_CAP = 30_000;

  /** Maximum test files included in one assessment. */
  static final int TEST_FILE_CAP = 6;

  /** Independent bounded config/workflow lane. */
  static final int CONFIG_SNIPPETS_CHAR_CAP = 30_000;

  /** Maximum workflow/config files included in one assessment. */
  static final int CONFIG_FILE_CAP = 8;

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
  static final int PER_REPO_BUDGET = 8;

  /** Read budget for single-repo / no-repos-prefix workspaces. */
  static final int SINGLE_REPO_BUDGET = 10;

  /**
   * Hard cap on total files read for the accuracy assessment regardless of how many repos a run
   * touches. Keeps per-call token cost bounded (18 × 2,500 chars ≈ 45K chars ≈ 11K extra tokens).
   */
  static final int MULTI_REPO_TOTAL_CAP = 24;

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

  /** Selects common test-file conventions while excluding build, cache, and vendored trees. */
  private List<String> inferTestFilePaths(String fileTree) {
    if (fileTree == null || fileTree.isBlank()) return List.of();
    java.util.regex.Pattern sourceExtension =
        java.util.regex.Pattern.compile(
            "(?i).*\\.(java|js|jsx|ts|tsx|py|go|rb|php|kt|kts|swift|rs|cs|scala|sh)$");
    java.util.regex.Pattern excludedTree =
        java.util.regex.Pattern.compile(
            "(?i).*(?:^|/)(?:__pycache__|venv|\\.venv|node_modules|target|build|dist|"
                + "\\.next|\\.gradle|coverage|bower_components|vendor|\\.idea|\\.vscode|"
                + "\\.pytest_cache|\\.mypy_cache|\\.tox|\\.nyc_output)(?:/|$).*");
    java.util.regex.Pattern testConvention =
        java.util.regex.Pattern.compile(
            "(?i)(?:^|/)(?:tests?|__tests__)(?:/|$)|"
                + "(?:^|/)(?:test_[^/]+|[^/]+_test|[^/]+\\.test|[^/]+\\.spec|[^/]*Test)\\.[^/]+$");

    return fileTree
        .lines()
        .map(String::trim)
        .map(l -> l.replaceAll("^[\\[DIR\\]│├└\\s─]+", "").trim())
        .filter(l -> !l.isBlank())
        .filter(l -> sourceExtension.matcher(l).matches())
        .filter(l -> !excludedTree.matcher(l).matches())
        .filter(l -> testConvention.matcher(l).find())
        .sorted(java.util.Comparator.comparingInt(String::length).thenComparing(s -> s))
        .limit(TEST_FILE_CAP)
        .toList();
  }

  /**
   * Selects workflow/config evidence independently from production and test source. Exclusions are
   * path-segment based: {@code build.yml} and {@code test.yml} are valid workflow filenames, while
   * {@code build/generated.yml} and {@code target/checks.yml} remain excluded.
   */
  private List<String> inferConfigEvidencePaths(String fileTree) {
    if (fileTree == null || fileTree.isBlank()) return List.of();
    java.util.regex.Pattern excludedTree =
        java.util.regex.Pattern.compile(
            "(?i)(?:^|/)(?:__pycache__|venv|\\.venv|node_modules|target|build|dist|"
                + "\\.next|\\.gradle|coverage|bower_components|vendor|\\.idea|\\.vscode|"
                + "\\.pytest_cache|\\.mypy_cache|\\.tox|\\.nyc_output)(?:/|$)");
    java.util.regex.Pattern configName =
        java.util.regex.Pattern.compile(
            "(?i)(?:^|/)(?:Dockerfile(?:\\.[^/]+)?|compose[^/]*\\.ya?ml|"
                + "[^/]+\\.(?:ya?ml|json|toml|properties|xml))$");

    return fileTree
        .lines()
        .map(String::trim)
        .map(l -> l.replaceAll("^[\\[DIR\\]│├└\\s─]+", "").trim())
        .filter(l -> !l.isBlank())
        .filter(l -> configName.matcher(l).find())
        .filter(l -> !excludedTree.matcher(l).find())
        .sorted(
            java.util.Comparator.comparingInt(AccuracyAssessmentService::configEvidencePriority)
                .thenComparingInt(String::length)
                .thenComparing(s -> s))
        .limit(CONFIG_FILE_CAP)
        .toList();
  }

  private static int configEvidencePriority(String path) {
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    if (lower.matches(".*(?:^|/)\\.github/workflows/[^/]+\\.ya?ml$")) return 0;
    if (lower.matches(".*(?:^|/)(?:workflows?|ci|actions?|deploy(?:ment)?s?)(?:/|\\.).*")) return 1;
    if (lower.matches(".*(?:^|/)(?:dockerfile|compose[^/]*\\.ya?ml)$")) return 2;
    return 3;
  }

  private static boolean isYaml(String path) {
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".yml") || lower.endsWith(".yaml");
  }

  /**
   * Returns null for valid YAML, otherwise a bounded diagnostic. SafeConstructor rejects arbitrary
   * Java tags; loader bounds prevent alias expansion, excessive nesting, and oversized documents.
   */
  static String safeYamlProblem(String content) {
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(20);
      options.setNestingDepthLimit(50);
      options.setCodePointLimit(CONFIG_SNIPPETS_CHAR_CAP);
      new Yaml(new SafeConstructor(options)).load(content);
      return null;
    } catch (RuntimeException ex) {
      String message = ex.getMessage();
      if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
      String singleLine = message.replaceAll("\\s+", " ").trim();
      return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 180) + "...";
    }
  }

  private static final java.util.regex.Pattern EXPLICIT_HTTP_ENDPOINT =
      java.util.regex.Pattern.compile(
          "(?i)\\b(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+/" + "[A-Za-z0-9_{}:*?&=./~-]*");

  /**
   * EndpointCoverage is LLM output. A workflow/CI-only spec has no endpoint denominator, so remove
   * an invented endpoint block unless the admitted goal or requirement ledger contains an explicit
   * HTTP method + path contract.
   */
  static void removeInventedEndpointCoverage(
      String originalGoal, List<RequirementSpec> requirements, AccuracyReport report) {
    if (report == null || report.getEndpointCoverage() == null) return;
    StringBuilder admitted = new StringBuilder(originalGoal == null ? "" : originalGoal);
    if (requirements != null) {
      requirements.stream()
          .filter(r -> r != null && r.text() != null)
          .forEach(r -> admitted.append('\n').append(r.text()));
    }
    if (!EXPLICIT_HTTP_ENDPOINT.matcher(admitted).find()) {
      report.setEndpointCoverage(null);
    }
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

  private static final java.util.regex.Pattern RUNTIME_EVIDENCE_FILENAME =
      java.util.regex.Pattern.compile(
          "(?i)(^|/)(index|main|app|server|database|db|config|settings|security|auth|middleware)\\.[a-z0-9]+$");

  private static final int RUNTIME_EVIDENCE_SLOTS = 3;

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
   * Pick {@code budget} files from {@code source}, preferring structural directories and filenames
   * while reserving bounded slots for the default runtime path. Without that reservation, route,
   * service, model, and migration files can consume the entire sample while the application
   * entrypoint, database initialization, or authentication middleware is omitted. That exact shape
   * allowed a FastAPI run whose tests manually called {@code create_all} to receive 97/100 even
   * though production startup bypassed its required Alembic migration.
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
    int structuralBudget = Math.max(0, budget - RUNTIME_EVIDENCE_SLOTS);
    for (String p : tierA) {
      if (picked.size() >= structuralBudget) break;
      picked.add(p);
    }
    for (String p : tierB) {
      if (picked.size() >= structuralBudget) break;
      picked.add(p);
    }
    source.stream()
        .filter(p -> RUNTIME_EVIDENCE_FILENAME.matcher(p).find())
        .filter(p -> !picked.contains(p))
        .limit(RUNTIME_EVIDENCE_SLOTS)
        .forEach(picked::add);
    // If fewer runtime files exist, restore unused reserved slots to structural evidence.
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

  // Defaults and helpers

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
