package com.navneet.executespec.codeintel;

import java.util.List;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 2 of the 3-step architecture generation pipeline: codemap → per-repo HLD + LLD architecture
 * doc in natural language.
 *
 * <p>Separate from {@link ArchitectureDocGenerator} which does the Step 3 combined synthesis across
 * repos. The generator dispatches through the canonical {@link LlmRouter} via the LIGHTWEIGHT tier
 * (cheap, fast).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RepoArchitectureGenerator {

  // ObjectProvider (lazy) breaks the bean cycle llmRouter→toolRegistry→readFileTool→
  // projectArchitectureService→repoArchitectureGenerator→llmRouter (RL-2026-06-002 fix).
  private final ObjectProvider<LlmRouter> llmRouter;
  private final ModelResolver modelResolver;
  private final LlmExecutionPolicyFactory executionPolicyFactory;

  private static final String SYSTEM_PROMPT =
      """
You are writing the architecture document for a single repository in a
multi-repo project. The input is a code map (directory outline, routes,
models). Produce a single markdown document in natural language with:

# <repo full name> — Architecture

## 1. High-Level Design (HLD)
2-4 paragraphs describing evidenced repository inventory and only behavior shown by exact
source evidence. If purpose, project role, components, layers, or external dependencies are
not deterministically established, state that rather than inferring them.

      ## 2. Low-Level Design (LLD)
      Grouped inventory subsections for declarations and files surfaced in the codemap. Name each
      concrete declaration/file and cite its path. Describe narrow behavior only when a complete
      exact-source V1 entry visibly establishes it; otherwise do not assign responsibility.

      ## 3. Surface area
      - Public routes only from AVAILABLE deterministic HTTP evidence; heuristic route inventory is
        not exposure authority
      - Entity/model declarations without ownership or liveness claims
      - Job/scheduler declarations without execution or wiring claims

## 4. Integration points
List only relationships established by deterministic relationship evidence. Exported-function
evidence never establishes inbound consumers or cross-repository edges. Otherwise state
'not established by the supplied deterministic evidence'.

Rules:
- Natural language prose, not bullet-dumps, except where lists improve scanning.
- Do NOT invent files, classes, or routes that are not in the codemap.
- Deterministic evidence blocks are authoritative for the facts they cover. File and symbol
  inventory does not prove calls, dependencies, runtime flow, schema ownership, Compose
  application startup, or response-vs-view behavior. State "not established by the supplied
  deterministic evidence" instead of inferring those facts from framework conventions.
- Do not turn declaration co-occurrence into an integration or data-flow claim.
- An EXECUTESPEC_EXPORTED_FUNCTION_EVIDENCE_V1 entry is exact-source authority only for its
  named repository, revision, path, symbol, and code visibly present in Content-Json. Describe
  only behavior directly shown by a Truncated:false excerpt and cite repository, path, and
  line range. Truncated:true forbids completeness, absence, all-return-path, side-effect, or
  full-contract claims. Repository-Evidence-Truncated:true means unlisted exports may exist.
  A visible call expression does not establish callers, consumers, liveness, wiring, import
  identity, ownership, the callee's component/repository, or an integration edge.
- Cite file paths inline with backticks.
- Aim for 500-1000 words. Dense, scannable, citation-rich.
""";

  public record Result(String markdown, int inputTokens, int outputTokens, String modelUsed) {}

  /**
   * Generate the per-repo architecture doc from a codemap.
   *
   * @param repoFullName e.g. "pplai-app/api-server"
   * @param codeMapMarkdown the markdown produced by {@link CodeMapGenerator}
   * @param provider LLM provider; falls back to OPENAI on null
   */
  public Result generate(
      String projectId, String repoFullName, String codeMapMarkdown, LlmProvider provider) {
    return generate(projectId, repoFullName, codeMapMarkdown, provider, null);
  }

  public Result generate(
      String projectId,
      String repoFullName,
      String codeMapMarkdown,
      LlmProvider provider,
      String actorUserId) {
    return generate(projectId, repoFullName, codeMapMarkdown, provider, actorUserId, null);
  }

  public Result generate(
      String projectId,
      String repoFullName,
      String codeMapMarkdown,
      LlmProvider provider,
      String actorUserId,
      LlmExecutionReference confirmedActionReference) {
    return generateInternal(
        projectId,
        repoFullName,
        codeMapMarkdown,
        provider,
        actorUserId,
        confirmedActionReference,
        null);
  }

  Result generateWithOperationContext(
      String projectId,
      String repoFullName,
      String codeMapMarkdown,
      LlmProvider provider,
      String actorUserId,
      ExecutionContext operationContext) {
    if (operationContext == null) throw new IllegalArgumentException("operationContext required");
    return generateInternal(
        projectId, repoFullName, codeMapMarkdown, provider, actorUserId, null, operationContext);
  }

  private Result generateInternal(
      String projectId,
      String repoFullName,
      String codeMapMarkdown,
      LlmProvider provider,
      String actorUserId,
      LlmExecutionReference confirmedActionReference,
      ExecutionContext operationContext) {
    if (codeMapMarkdown == null || codeMapMarkdown.isBlank()) {
      throw new IllegalArgumentException("code map is empty for " + repoFullName);
    }

    if (provider == null) throw new IllegalArgumentException("provider required");
    LlmProvider effective = provider;
    ResolvedModel resolved = modelResolver.resolve("RepoArchitectureGenerator", effective);
    String exactModel = RunModelOverrides.lookup(effective, resolved.getAgentTier());

    String userPayload = "Repository: " + repoFullName + "\n\n" + "Code map:\n\n" + codeMapMarkdown;

    List<Message> messages =
        List.of(
            new SystemMessage(SYSTEM_PROMPT),
            new UserMessage(PromptTrustBoundary.wrap("REPO_ARCHITECTURE_EVIDENCE", userPayload)));
    String contextId = "repo-arch-" + java.util.UUID.randomUUID();
    var executionReference =
        operationContext != null
            ? null
            : (confirmedActionReference != null
                ? confirmedActionReference
                : executionPolicyFactory.forProject(
                    projectId,
                    "repo-architecture",
                    "ARCHITECTURE",
                    contextId,
                    contextId,
                    effective.name(),
                    exactModel,
                    actorUserId));

    // All providers use the canonical text router; architecture generation has no tool callbacks.
    ChatResponse response =
        operationContext == null
            ? ArchLlmDispatch.dispatch(
                llmRouter.getObject(),
                resolved,
                messages,
                "repo-arch-" + safeSlug(repoFullName),
                "repo-arch",
                executionReference)
            : ArchLlmDispatch.dispatch(
                llmRouter.getObject(),
                resolved,
                messages,
                "repo-arch-" + safeSlug(repoFullName),
                "repo-arch",
                operationContext);

    requireCompleteTermination(response, "repository architecture for " + repoFullName);
    String text = response.getResult().getOutput().getText();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("LLM returned empty arch content for " + repoFullName);
    }
    Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
    int inTok = usage == null ? 0 : safeInt(usage.getPromptTokens());
    int outTok = usage == null ? 0 : safeInt(usage.getCompletionTokens());
    log.info(
        "Generated per-repo arch for {} ({} in / {} out tokens, model={})",
        repoFullName,
        inTok,
        outTok,
        resolved.getModel());
    String grounded = GeneratedSignatureGrounding.sanitize(text.trim(), codeMapMarkdown);
    grounded = GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(grounded, codeMapMarkdown);
    grounded = GeneratedProjectFactGrounding.sanitize(grounded, codeMapMarkdown);
    return new Result(
        DeterministicArchitectureEvidence.appendFromCodemap(grounded, codeMapMarkdown),
        inTok,
        outTok,
        resolved.getModel());
  }

  static void requireCompleteTermination(ChatResponse response, String operation) {
    Object reason =
        response == null
                || response.getResult() == null
                || response.getResult().getMetadata() == null
            ? null
            : response.getResult().getMetadata().getFinishReason();
    if (reason == null) return;
    String normalized = String.valueOf(reason).trim().toLowerCase(java.util.Locale.ROOT);
    if (normalized.isBlank()
        || normalized.equals("stop")
        || normalized.equals("end_turn")
        || normalized.equals("complete")
        || normalized.equals("completed")) {
      return;
    }
    throw new ProviderOutputIncompleteException(
        operation + " terminated before completion (finishReason=" + normalized + ")");
  }

  static final class ProviderOutputIncompleteException extends RuntimeException {
    ProviderOutputIncompleteException(String message) {
      super(message);
    }
  }

  private static String safeSlug(String s) {
    if (s == null) return "unknown";
    return s.toLowerCase().replaceAll("[^a-z0-9]+", "-");
  }

  private static int safeInt(Integer i) {
    return i == null ? 0 : i;
  }
}
