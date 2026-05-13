package com.navneet.executespec.codeintel;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates two user-invoked, project-level documents:
 *
 * <ul>
 *   <li>{@link DocType#EXPLANATION} — narrative "what is this project" doc for onboarding a new
 *       team member. Covers the repos, their role, the key architectural patterns, the data flow.
 *   <li>{@link DocType#SETUP} — actionable local-setup README. Covers clone commands, env vars,
 *       per-repo bootstrap, first-run checks.
 * </ul>
 *
 * <p>Both docs are derived deterministically from the stored {@link ProjectArchitectureDoc} +
 * per-repo codemaps. An LLM polish pass turns the source into a human-readable document. Output is
 * persisted both to {@link ProjectGeneratedDocRepository} (authoritative) AND to disk at {@code
 * workspace/projects/<id>/docs/<type>.md} (fast read cache).
 *
 * <p>Callers must have already run analyse() on the project — if no architecture doc exists we
 * throw so the UI can prompt the user to run it first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDocGeneratorService {

  private final ProjectArchitectureService architectureService;
  private final ProjectWorkspaceService workspace;
  private final ProjectGeneratedDocRepository docRepo;
  private final VersionRetentionService versionRetention;
  private final ProjectRepository projectRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final FallbackLlmRouter fallbackLlmRouter;
  private final ModelResolver modelResolver;

  /** v2.6.4 — compute USD cost from tokens × pricing matrix for the dashboard rollup. */
  private final com.navneet.executespec.cost.RunCostTracker costTracker;

  private static final String EXPLANATION_SYSTEM =
      """
      You are writing a project explanation document for a new developer joining the team.
      You will be given the project's architecture document, codemaps for each linked repo,
      and project metadata. Produce a single markdown document with these sections:

      # <Project name>
      <one-paragraph elevator pitch>

      ## Repositories
      One bullet per repo: name, purpose, primary tech stack.

      ## High-level architecture
      2-4 paragraphs describing how the pieces fit together. No diagrams. Reference
      specific components from the codemap where relevant.

      ## Key data flows
      Numbered list of 3-6 end-to-end flows (e.g. "User signs up" → HTTP route → service
      → DB write → event fired → ...). Cite file paths from the codemap as evidence.

      ## Where to contribute
      Short list of common change patterns (add a feature, fix a bug, add a test) and
      the files a contributor touches first.

      Rules:
      - Plain markdown. No backticks around section headings.
      - Do not invent components. If the architecture / codemap doesn't show it, don't
        claim it exists.
      - Aim for 600-1200 words. Dense, scannable, citation-rich.
      """;

  private static final String SETUP_SYSTEM =
      """
      You are writing a local-setup README for a new developer joining the team.
      You will be given the project's architecture document, codemaps for each linked
      repo, and project metadata. Produce a single markdown document with these sections:

      # Setting up <Project name>

      ## Prerequisites
      Bulleted list: runtimes, tools, local services (docker, postgres, redis, node, etc).
      Infer from the codemap's detected stack.

      ## Clone
      Exact `git clone` commands, one per repo, with the correct owner/name (infer from
      the architecture doc or leave `<owner>` as a placeholder if unsure).

      ## Environment variables
      Table of env vars required by each repo (grep the codemap for references). Columns:
      var | repo | purpose | example.

      ## First-run per repo
      For each repo, show the commands to install deps, migrate the DB if relevant,
      start the service in dev mode. Label each block with the repo name.

      ## Running the full stack locally
      Recommended order of services to bring up + health-check URLs.

      ## Troubleshooting
      3-5 common setup errors and their fix.

      Rules:
      - Use fenced code blocks for every command. Prefer `bash` for shell, `yaml` for
        config.
      - Do NOT invent env vars. Only include ones visible in the codemaps / architecture.
      - Aim for 500-1200 words.
      """;

  /**
   * Generate (or regenerate) the requested doc type for the given project. Synchronous — typical
   * runtime is 15-40s depending on project size.
   *
   * @param projectId project to document
   * @param docType EXPLANATION or SETUP
   * @param generatedByUser user who initiated the request (for audit); may be null
   * @param provider LLM provider override; null uses project default
   * @return the persisted document
   */
  @Transactional
  public ProjectGeneratedDoc generate(
      String projectId, DocType docType, String generatedByUser, LlmProvider provider) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("project not found: " + projectId));

    Optional<ProjectArchitectureDoc> archOpt = architectureService.latest(projectId);
    if (archOpt.isEmpty()) {
      throw new IllegalStateException("project has no architecture doc yet — call /analyse first");
    }
    ProjectArchitectureDoc arch = archOpt.get();

    String systemPrompt =
        switch (docType) {
          case EXPLANATION -> EXPLANATION_SYSTEM;
          case SETUP -> SETUP_SYSTEM;
        };

    String userPayload = buildSourceBundle(project, arch);

    LlmProvider effective = provider != null ? provider : resolveProvider(project);
    ResolvedModel resolved = modelResolver.resolve("ProjectDocGeneratorService", effective);

    Prompt prompt =
        new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPayload)));

    ChatResponse response =
        fallbackLlmRouter.callWithFallback(
            resolved.providerName(),
            resolved.getModel(),
            prompt,
            "project-doc-" + docType.name().toLowerCase() + "-" + projectId,
            "project-doc-" + docType.name().toLowerCase(),
            FallbackLlmRouter.FallbackMode.IMMEDIATE,
            0,
            false);

    String content = response.getResult().getOutput().getText();
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("LLM returned empty content for " + docType);
    }
    content = content.trim();

    Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
    int inTok = usage == null ? 0 : safeInt(usage.getPromptTokens());
    int outTok = usage == null ? 0 : safeInt(usage.getCompletionTokens());

    int nextVersion =
        docRepo
            .findFirstByProjectIdAndDocTypeOrderByVersionDesc(projectId, docType)
            .map(d -> d.getVersion() + 1)
            .orElse(1);

    // v2.6.4 (V174) — compute USD cost so the project dashboard can roll doc-gen spend up.
    double cost =
        costTracker.computeCost(resolved.providerName(), resolved.getModel(), inTok, outTok);
    ProjectGeneratedDoc doc =
        ProjectGeneratedDoc.builder()
            .projectId(projectId)
            .docType(docType)
            .content(content)
            .version(nextVersion)
            .generatedBy(generatedByUser)
            .modelUsed(resolved.getModel())
            .inputTokens(inTok)
            .outputTokens(outTok)
            .costUsd(cost)
            .build();
    doc = docRepo.save(doc);
    versionRetention.pruneGeneratedDoc(projectId, docType);

    // Mirror to disk so the UI can use the same read path as summary.md.
    try {
      String rel = "docs/" + docType.name().toLowerCase() + ".md";
      workspace.writeRawFile(projectId, rel, content);
    } catch (Exception e) {
      log.warn("[{}] doc-gen ok but disk mirror failed: {}", projectId, e.getMessage());
    }

    log.info(
        "[{}] Generated {} doc v{} ({} in / {} out tokens, model={})",
        projectId,
        docType,
        nextVersion,
        inTok,
        outTok,
        resolved.getModel());
    return doc;
  }

  /** Latest persisted doc of the given type, or empty if not yet generated. */
  public Optional<ProjectGeneratedDoc> latest(String projectId, DocType docType) {
    return docRepo.findFirstByProjectIdAndDocTypeOrderByVersionDesc(projectId, docType);
  }

  /**
   * Build the source bundle fed to the LLM: project name + description + repo list +
   * architecture.md + per-repo codemaps.
   */
  private String buildSourceBundle(Project project, ProjectArchitectureDoc arch) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Project metadata\n");
    sb.append("- Name: ").append(nullToDash(project.getName())).append('\n');
    sb.append("- Description: ").append(nullToDash(project.getDescription())).append('\n');
    sb.append("- GitHub owner: ").append(nullToDash(project.getGithubOwner())).append('\n');

    List<ProjectRepoLink> links = linkRepo.findByProjectId(project.getId());
    sb.append("\n# Linked repositories\n");
    for (ProjectRepoLink link : links) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null) continue;
      sb.append("- ")
          .append(repo.getGithubRepoFullName())
          .append(" (default branch: ")
          .append(repo.getDefaultBranch() == null ? "main" : repo.getDefaultBranch())
          .append(")\n");
    }

    sb.append("\n# Architecture document (v").append(arch.getVersion()).append(")\n");
    sb.append(arch.getContent() == null ? "" : arch.getContent());

    sb.append("\n\n# Per-repo codemaps\n");
    for (ProjectRepoLink link : links) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null) continue;
      architectureService
          .latestCodeMap(project.getId(), repo.getId())
          .ifPresent(
              cm -> {
                sb.append("\n## ").append(repo.getGithubRepoFullName()).append(" codemap\n");
                sb.append(cm.getContent() == null ? "" : cm.getContent());
              });
    }

    // Cap to ~60k chars to avoid blowing context budgets on giant projects.
    if (sb.length() > 60_000) {
      return sb.substring(0, 60_000) + "\n\n[truncated at 60k chars]\n";
    }
    return sb.toString();
  }

  private LlmProvider resolveProvider(Project project) {
    LlmProvider p = project.getAiProvider();
    return p != null ? p : LlmProvider.OPENAI;
  }

  private static String nullToDash(String s) {
    return s == null || s.isBlank() ? "—" : s;
  }

  private static int safeInt(Integer i) {
    return i == null ? 0 : i;
  }
}
