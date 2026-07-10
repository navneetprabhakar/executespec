package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

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
 * Step 3 of the 3-step architecture generation pipeline: combine the per-repo architecture docs
 * into a single project-wide narrative.
 *
 * <p>Previously this class concatenated codemaps deterministically. It now synthesises a true
 * cross-repo architecture by LLM, reading the per-repo HLD + LLD docs produced by {@link
 * RepoArchitectureGenerator} as input.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArchitectureDocGenerator {

  // ObjectProvider (lazy) breaks the bean cycle through projectArchitectureService
  // (RL-2026-06-002).
  private final ObjectProvider<LlmRouter> llmRouter;
  private final ModelResolver modelResolver;

  private static final String SYSTEM_PROMPT =
      """
      You are writing the combined architecture document for a multi-repo
      project. The input is a set of per-repo architecture docs (HLD + LLD)
      already produced. Your job is to synthesise a single project-wide
      document that stitches them together into one coherent narrative.

      Produce a markdown document with:

      # <project name or id> — Combined architecture

      ## 1. Overview
      2-3 paragraphs: what the project does end-to-end, which repos exist
      and their role in one sentence each, and the high-level request /
      data flow between them.

      ## 2. Repository layer
      One subsection per repo. For each, SUMMARISE (don't copy verbatim)
      its HLD in 1-2 paragraphs. Link conceptually — call out where
      request paths cross repo boundaries.

      ## 3. Cross-repo data flows
      Numbered list of 3-6 concrete end-to-end flows (user action →
      repo A endpoint → repo B service → storage → ...). Reference file
      paths from the per-repo docs so a reviewer can verify.

      ## 4. Shared contracts
      - APIs consumed / produced between repos
      - Shared models / DTOs
      - Shared infra (DBs, queues, caches)

      ## 5. Open questions
      Items that the per-repo docs surfaced with 'not evident from the
      codemap' — collect those here so reviewers know what to annotate.

      Rules:
      - Do NOT invent routes, classes, or integrations not present in the
        per-repo docs.
      - Prefer prose to bulletised dumps.
      - If only one repo is in scope, produce the document anyway but
        note that cross-repo flows degenerate to intra-repo flows.
      - Aim for 700-1500 words total.
      """;

  public record Result(String markdown, int inputTokens, int outputTokens, String modelUsed) {}

  /**
   * Produce the combined architecture markdown. {@code repoArchDocs} must be ordered the same as
   * {@code repoFullNames} — the caller is responsible for pairing them.
   */
  public Result generate(
      String projectId,
      List<RepoArchitectureDoc> repoArchDocs,
      List<String> repoFullNames,
      LlmProvider provider) {
    if (repoArchDocs == null || repoArchDocs.isEmpty()) {
      throw new IllegalArgumentException(
          "combined arch needs at least one per-repo arch doc; got 0");
    }

    LlmProvider effective = provider != null ? provider : LlmProvider.OPENAI;
    ResolvedModel resolved = modelResolver.resolve("ArchitectureDocGenerator", effective);

    String userPayload = buildPayload(projectId, repoArchDocs, repoFullNames);

    List<Message> messages =
        List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userPayload));

    // All providers use the canonical text router; architecture generation has no tool callbacks.
    ChatResponse response =
        ArchLlmDispatch.dispatch(
            llmRouter.getObject(),
            resolved,
            messages,
            "combined-arch-" + projectId,
            "combined-arch");

    String text = response.getResult().getOutput().getText();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException(
          "LLM returned empty combined-arch content for project " + projectId);
    }
    Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
    int inTok = usage == null ? 0 : safeInt(usage.getPromptTokens());
    int outTok = usage == null ? 0 : safeInt(usage.getCompletionTokens());
    log.info(
        "Generated combined arch for project {} ({} in / {} out tokens, model={})",
        projectId,
        inTok,
        outTok,
        resolved.getModel());
    return new Result(text.trim(), inTok, outTok, resolved.getModel());
  }

  private String buildPayload(
      String projectId, List<RepoArchitectureDoc> docs, List<String> repoFullNames) {
    StringBuilder sb = new StringBuilder();
    sb.append("Project id: ").append(projectId).append("\n");
    sb.append("Repositories in scope:\n");
    for (String r : repoFullNames) sb.append("  - ").append(r).append('\n');
    sb.append("\n");

    for (int i = 0; i < docs.size(); i++) {
      RepoArchitectureDoc d = docs.get(i);
      String name = i < repoFullNames.size() ? repoFullNames.get(i) : ("repo-" + d.getRepoId());
      sb.append("\n==========\n");
      sb.append("# Per-repo arch: ").append(name).append("\n\n");
      sb.append(d.getContent() == null ? "" : d.getContent());
      sb.append('\n');
    }

    // Cap to ~80k chars to keep planner context budgets in check.
    if (sb.length() > 80_000) {
      return sb.substring(0, 80_000) + "\n\n[truncated at 80k chars]\n";
    }
    return sb.toString();
  }

  /**
   * Legacy no-LLM fallback — used when a reviewer wants a purely deterministic combined doc (no
   * token cost) or when the LLM call fails. Kept as a static utility so callers can deliberately
   * choose this path.
   */
  public static String deterministicCombined(String projectId, Map<String, String> archByRepo) {
    StringBuilder md = new StringBuilder();
    md.append("# Project architecture (deterministic)\n\n");
    md.append("Project `").append(projectId).append("`. ");
    md.append(archByRepo.size()).append(" per-repo arch doc(s) concatenated.\n\n");
    List<String> repos = new ArrayList<>(archByRepo.keySet());
    md.append("## Repositories in scope\n\n");
    repos.forEach(r -> md.append("- ").append(r).append('\n'));
    md.append('\n');
    md.append("## Per-repo architecture\n\n");
    for (String r : repos) {
      md.append("---\n\n### ").append(r).append("\n\n");
      md.append(archByRepo.get(r)).append('\n');
    }
    return md.toString();
  }

  private static int safeInt(Integer i) {
    return i == null ? 0 : i;
  }
}
