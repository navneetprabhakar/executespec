package com.navneet.executespec.codeintel;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.agent.ToolProtocolLeakGuard;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

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
@Slf4j
public class ProjectDocGeneratorService {

  private final ProjectArchitectureService architectureService;
  private final ProjectWorkspaceService workspace;
  private final ProjectGeneratedDocRepository docRepo;
  private final ProjectRepository projectRepo;
  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final ProjectProviderResolver projectProviderResolver;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final PromptAssembler promptAssembler;
  private final GeneratedSecretScanner secretScanner;
  private final ProjectDocSourceBundleBuilder sourceBundleBuilder;
  private final ProjectGeneratedDocPublisher publisher;
  private final ConcurrentHashMap<GenerationKey, CompletableFuture<ProjectGeneratedDoc>> inFlight =
      new ConcurrentHashMap<>();

  /** v2.6.4 — compute USD cost from tokens × pricing matrix for the dashboard rollup. */
  private final com.navneet.executespec.cost.RunCostTracker costTracker;

  static final int SOURCE_BUNDLE_CHAR_LIMIT = 60_000;

  public static final String OUTPUT_LIMIT_REASON = "PROVIDER_OUTPUT_LIMIT";
  public static final String STALE_SOURCE_REASON = "STALE_SOURCE";
  public static final String UNSAFE_CONTENT_REASON = "UNSAFE_GENERATED_CONTENT";
  public static final String INVALID_CONTENT_REASON = "INVALID_GENERATED_CONTENT";
  public static final String PROVIDER_NON_COMPLETE_REASON = "PROVIDER_NON_COMPLETE_TERMINATION";

  private record GenerationKey(
      String projectId,
      DocType docType,
      Integer sourceArchVersion,
      String sourceFingerprint,
      LlmProvider provider,
      String model,
      String actor,
      String authorityContextId) {}

  record SourceSection(String heading, String content) {}

  /** A provider-limited document is diagnostic output, not a publishable document version. */
  public static final class IncompleteGenerationException extends IllegalStateException {
    public IncompleteGenerationException(DocType docType) {
      super(
          "Provider stopped at its output limit while generating "
              + docType
              + "; no document was saved. Retry after narrowing the project evidence.");
    }

    public String reason() {
      return OUTPUT_LIMIT_REASON;
    }
  }

  /**
   * The source changed while the provider was generating; obsolete output must not be published.
   */
  public static final class StaleSourceException extends IllegalStateException {
    public StaleSourceException() {
      super(
          "Project architecture changed during document generation; no document was saved. Retry.");
    }

    public String reason() {
      return STALE_SOURCE_REASON;
    }
  }

  /** Provider output contained credential-like or private protocol material. */
  public static final class UnsafeGeneratedDocumentException extends IllegalStateException {
    public UnsafeGeneratedDocumentException() {
      super("Generated document failed the publication safety check; no document was saved.");
    }

    public String reason() {
      return UNSAFE_CONTENT_REASON;
    }
  }

  /** Provider output did not satisfy the deterministic document contract. */
  public static final class InvalidGeneratedDocumentException extends IllegalStateException {
    public InvalidGeneratedDocumentException(String detail) {
      super(
          "Generated document is incomplete or malformed (" + detail + "); no document was saved.");
    }

    public String reason() {
      return INVALID_CONTENT_REASON;
    }
  }

  /** The provider explicitly reported a refusal/safety/error termination instead of completion. */
  public static final class ProviderNonCompleteGenerationException extends IllegalStateException {
    public ProviderNonCompleteGenerationException() {
      super("Provider did not complete the generated document; no document was saved. Retry.");
    }

    public String reason() {
      return PROVIDER_NON_COMPLETE_REASON;
    }
  }

  @Autowired
  public ProjectDocGeneratorService(
      ProjectArchitectureService architectureService,
      ProjectWorkspaceService workspace,
      ProjectGeneratedDocRepository docRepo,
      VersionRetentionService versionRetention,
      ProjectRepository projectRepo,
      ProjectRepoLinkRepository linkRepo,
      ProjectRepoRepository repoRepo,
      LlmRouter llmRouter,
      ModelResolver modelResolver,
      com.navneet.executespec.cost.RunCostTracker costTracker,
      ProjectProviderResolver projectProviderResolver,
      LlmExecutionPolicyFactory executionPolicyFactory,
      PromptAssembler promptAssembler,
      GeneratedSecretScanner secretScanner,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.architectureService = architectureService;
    this.workspace = workspace;
    this.docRepo = docRepo;
    this.projectRepo = projectRepo;
    this.llmRouter = llmRouter;
    this.modelResolver = modelResolver;
    this.costTracker = costTracker;
    this.projectProviderResolver = projectProviderResolver;
    this.executionPolicyFactory = executionPolicyFactory;
    this.promptAssembler = promptAssembler;
    this.secretScanner = secretScanner == null ? new GeneratedSecretScanner() : secretScanner;
    ObjectMapper sourceObjectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    this.sourceBundleBuilder =
        new ProjectDocSourceBundleBuilder(
            architectureService, linkRepo, repoRepo, sourceObjectMapper);
    this.publisher =
        new ProjectGeneratedDocPublisher(
            architectureService, docRepo, versionRetention, projectRepo, transactionManager);
  }

  /** Compatibility constructor for focused tests that do not boot a transaction manager. */
  ProjectDocGeneratorService(
      ProjectArchitectureService architectureService,
      ProjectWorkspaceService workspace,
      ProjectGeneratedDocRepository docRepo,
      VersionRetentionService versionRetention,
      ProjectRepository projectRepo,
      ProjectRepoLinkRepository linkRepo,
      ProjectRepoRepository repoRepo,
      LlmRouter llmRouter,
      ModelResolver modelResolver,
      com.navneet.executespec.cost.RunCostTracker costTracker,
      ProjectProviderResolver projectProviderResolver,
      LlmExecutionPolicyFactory executionPolicyFactory,
      PromptAssembler promptAssembler) {
    this(
        architectureService,
        workspace,
        docRepo,
        versionRetention,
        projectRepo,
        linkRepo,
        repoRepo,
        llmRouter,
        modelResolver,
        costTracker,
        projectProviderResolver,
        executionPolicyFactory,
        promptAssembler,
        new GeneratedSecretScanner(),
        new ObjectMapper(),
        null);
  }

  /** Compatibility constructor for package tests and older callers. */
  public ProjectDocGeneratorService(
      ProjectArchitectureService architectureService,
      ProjectWorkspaceService workspace,
      ProjectGeneratedDocRepository docRepo,
      VersionRetentionService versionRetention,
      ProjectRepository projectRepo,
      ProjectRepoLinkRepository linkRepo,
      ProjectRepoRepository repoRepo,
      LlmRouter llmRouter,
      ModelResolver modelResolver,
      com.navneet.executespec.cost.RunCostTracker costTracker) {
    this(
        architectureService,
        workspace,
        docRepo,
        versionRetention,
        projectRepo,
        linkRepo,
        repoRepo,
        llmRouter,
        modelResolver,
        costTracker,
        null,
        null,
        null,
        new GeneratedSecretScanner(),
        new ObjectMapper(),
        null);
  }

  /** Generate or regenerate a project document synchronously. */
  public ProjectGeneratedDoc generate(
      String projectId, DocType docType, String generatedByUser, LlmProvider provider) {
    return generate(projectId, docType, generatedByUser, provider, null);
  }

  /** Confirmed action path with one shared exact-route budget context. */
  public ProjectGeneratedDoc generate(
      String projectId,
      DocType docType,
      String generatedByUser,
      LlmProvider provider,
      LlmExecutionReference confirmedActionReference) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("project not found: " + projectId));

    Optional<ProjectArchitectureDoc> archOpt = architectureService.latest(projectId);
    if (archOpt.isEmpty()) {
      throw new IllegalStateException("project has no architecture doc yet — call /analyse first");
    }
    ProjectArchitectureDoc arch = archOpt.get();

    // PR-Q (2026-05-13) — short-circuit cache. Same idea as PR-K's fingerprint short-circuit
    // on combined-arch regen, one layer up: if the most recent doc of this type was generated
    // against the SAME arch version AND the SAME per-repo SHA fingerprint, return it instead
    // of burning a 15-40s LLM call. Cheap composite key (Integer + short String) — no
    // additional query cost over the existing latest-version lookup the cost-aware code path
    // does anyway.
    Integer sourceArchVersion = arch.getVersion();
    String sourceFingerprint = arch.getGeneratedFromShas(); // already a canonical JSON string
    if (sourceArchVersion != null && sourceFingerprint != null) {
      Optional<ProjectGeneratedDoc> cached =
          docRepo
              .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                  projectId, docType, sourceArchVersion, sourceFingerprint)
              .filter(d -> d.getContent() != null && !d.getContent().isBlank());
      if (cached.isPresent()) {
        assertSafeForCustomer(cached.get().getContent());
        log.info(
            "[{}] doc-gen short-circuit type={} — reusing v{} (arch v{} + SHAs unchanged); skipped"
                + " LLM",
            projectId,
            docType,
            cached.get().getVersion(),
            sourceArchVersion);
        return cached.get();
      }
    }

    if (promptAssembler == null) {
      throw new IllegalStateException("Prompt assembler is required for project docs");
    }
    String systemPrompt = promptAssembler.assemble(promptPath(docType));

    String userPayload = buildSourceBundle(project, arch);
    LlmProvider effective = provider != null ? provider : resolveProvider(project);
    ResolvedModel resolved = modelResolver.resolve("ProjectDocGeneratorService", effective);
    String exactModel = resolved.getModel();
    GenerationKey generationKey =
        new GenerationKey(
            projectId,
            docType,
            sourceArchVersion,
            sourceFingerprint,
            resolved.getActualProvider(),
            exactModel,
            generatedByUser,
            confirmedActionReference == null ? null : confirmedActionReference.contextId());
    return singleFlight(
        generationKey,
        () ->
            generateOnce(
                project,
                docType,
                generatedByUser,
                effective,
                resolved,
                exactModel,
                confirmedActionReference,
                systemPrompt,
                userPayload,
                sourceArchVersion,
                sourceFingerprint));
  }

  private ProjectGeneratedDoc generateOnce(
      Project project,
      DocType docType,
      String generatedByUser,
      LlmProvider effective,
      ResolvedModel resolved,
      String exactModel,
      LlmExecutionReference confirmedActionReference,
      String systemPrompt,
      String userPayload,
      Integer sourceArchVersion,
      String sourceFingerprint) {
    String projectId = project.getId();
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(PromptTrustBoundary.wrap("PROJECT_DOC_EVIDENCE", userPayload))));

    String runId = "project-doc-" + docType.name().toLowerCase() + "-" + projectId;
    if (executionPolicyFactory == null) {
      throw new IllegalStateException("Execution policy factory is required for project docs");
    }
    String contextId = projectDocContextId(java.util.UUID.randomUUID());
    var executionReference =
        confirmedActionReference != null
            ? confirmedActionReference
            : executionPolicyFactory.forProject(
                projectId,
                "project-doc",
                "PROJECT_DOC",
                contextId,
                contextId,
                resolved.getActualProvider().name(),
                exactModel,
                generatedByUser);
    ChatResponse response =
        llmRouter.callText(
            resolved.providerName(),
            exactModel,
            prompt.getInstructions(),
            runId,
            "project-doc-" + docType.name().toLowerCase(),
            "generate",
            executionReference);

    if (responseWasTruncated(response)) {
      log.warn(
          "[{}] Refusing truncated {} document response (reason={})",
          projectId,
          docType,
          OUTPUT_LIMIT_REASON);
      throw new IncompleteGenerationException(docType);
    }
    ProjectDocResponsePolicy.requireComplete(response);
    String content = response.getResult().getOutput().getText();
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("LLM returned empty content for " + docType);
    }
    content = content.trim();
    validatePublishableContent(docType, content, sourceFingerprint);

    Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
    int inTok = usage == null ? 0 : safeInt(usage.getPromptTokens());
    int outTok = usage == null ? 0 : safeInt(usage.getCompletionTokens());

    // v2.6.4 (V174) — compute USD cost so the project dashboard can roll doc-gen spend up.
    double cost = costTracker.computeCost(resolved.providerName(), exactModel, inTok, outTok);
    ProjectGeneratedDoc candidate =
        ProjectGeneratedDoc.builder()
            .projectId(projectId)
            .docType(docType)
            .content(content)
            .generatedBy(generatedByUser)
            .modelUsed(exactModel)
            .inputTokens(inTok)
            .outputTokens(outTok)
            .costUsd(cost)
            // PR-Q (2026-05-13) — persist the cache key so the next /generate call can decide
            // whether to short-circuit. sourceArchVersion + sourceFingerprint were computed
            // above for the cache-hit check; reuse the same values here so the round-trip is
            // a true round-trip (no risk of a one-iteration drift).
            .sourceArchVersion(sourceArchVersion)
            .sourceShasFingerprint(sourceFingerprint)
            .build();
    ProjectGeneratedDoc doc = persistGeneratedDoc(candidate);

    // Mirror to disk so the UI can use the same read path as summary.md.
    try {
      String rel = "docs/" + docType.name().toLowerCase() + ".md";
      workspace.writeRawFile(projectId, rel, doc.getContent());
    } catch (Exception e) {
      log.warn("[{}] doc-gen ok but disk mirror failed: {}", projectId, e.getMessage());
    }

    log.info(
        "[{}] Generated {} doc v{} ({} in / {} out tokens, model={})",
        projectId,
        docType,
        doc.getVersion(),
        inTok,
        outTok,
        exactModel);
    return doc;
  }

  private ProjectGeneratedDoc persistGeneratedDoc(ProjectGeneratedDoc candidate) {
    return publisher.publish(candidate);
  }

  private ProjectGeneratedDoc singleFlight(
      GenerationKey key, Supplier<ProjectGeneratedDoc> generation) {
    CompletableFuture<ProjectGeneratedDoc> mine = new CompletableFuture<>();
    CompletableFuture<ProjectGeneratedDoc> active = inFlight.putIfAbsent(key, mine);
    if (active != null) {
      try {
        return active.join();
      } catch (CompletionException e) {
        throw rethrow(e.getCause());
      }
    }
    try {
      ProjectGeneratedDoc generated = generation.get();
      mine.complete(generated);
      return generated;
    } catch (Throwable failure) {
      mine.completeExceptionally(failure);
      throw rethrow(failure);
    } finally {
      inFlight.remove(key, mine);
    }
  }

  private static RuntimeException rethrow(Throwable failure) {
    return failure instanceof RuntimeException runtime
        ? runtime
        : new IllegalStateException("Project document generation failed", failure);
  }

  /**
   * Uniform customer-publication boundary used by generate, display, download, and project chat.
   */
  public void assertSafeForCustomer(String content) {
    if (secretScanner.scan(content).isPresent()
        || ToolProtocolLeakGuard.containsProtocolMarkup(content)) {
      throw new UnsafeGeneratedDocumentException();
    }
  }

  private void validatePublishableContent(
      DocType docType, String content, String sourceFingerprint) {
    assertSafeForCustomer(content);
    ProjectGeneratedDocValidator.validate(
            docType, content, parseSourceShas(sourceFingerprint).keySet())
        .ifPresent(
            reason -> {
              throw new InvalidGeneratedDocumentException(reason);
            });
  }

  /** Latest persisted doc of the given type, or empty if not yet generated. */
  public Optional<ProjectGeneratedDoc> latest(String projectId, DocType docType) {
    return docRepo.findFirstByProjectIdAndDocTypeOrderByVersionDesc(projectId, docType);
  }

  /**
   * Build the source bundle fed to the LLM: project name + description + repo list +
   * architecture.md + per-repo codemaps.
   */
  String buildSourceBundle(Project project, ProjectArchitectureDoc arch) {
    return sourceBundleBuilder.build(project, arch);
  }

  private java.util.Map<String, String> parseSourceShas(String sourceFingerprint) {
    return sourceBundleBuilder.parseSourceShas(sourceFingerprint);
  }

  /**
   * Budgets every architecture/repository section independently. The former whole-prefix cap could
   * omit every later repository; this water-filling allocation guarantees each section a share,
   * then redistributes unused capacity from short sections to longer ones.
   */
  static String fairBudgetSourceSections(List<SourceSection> sections) {
    List<ProjectDocSourceBudgeter.Section> budgetSections =
        sections == null
            ? List.of()
            : sections.stream()
                .map(
                    section ->
                        new ProjectDocSourceBudgeter.Section(section.heading(), section.content()))
                .toList();
    return ProjectDocSourceBudgeter.budget(budgetSections, SOURCE_BUNDLE_CHAR_LIMIT);
  }

  private static String promptPath(DocType docType) {
    return switch (docType) {
      case EXPLANATION -> "project-doc-explanation.md";
      case SETUP -> "project-doc-setup.md";
    };
  }

  static boolean responseWasTruncated(ChatResponse response) {
    return ProjectDocResponsePolicy.wasTruncated(response);
  }

  /**
   * Backward-compatible single-section cap used by focused callers. Multi-repository generation
   * uses {@link #fairBudgetSourceSections(List)} so later repositories cannot be prefix-starved.
   */
  static String capSourceBundle(String source) {
    return fairBudgetSourceSections(List.of(new SourceSection("# Source evidence", source)));
  }

  /**
   * Builds the durable source/correlation/idempotency identity for one document-generation call.
   *
   * <p>The former value prefixed the full project-doc run id to a UUID. For EXPLANATION documents
   * that produced 97 characters and failed the database's {@code correlation_id VARCHAR(96)}
   * constraint before any provider call. Project and feature already have dedicated context
   * columns, so keep this identity compact and collision-resistant instead of duplicating them.
   */
  static String projectDocContextId(java.util.UUID nonce) {
    return "project-doc:" + java.util.Objects.requireNonNull(nonce, "nonce");
  }

  private LlmProvider resolveProvider(Project project) {
    if (projectProviderResolver != null) return projectProviderResolver.resolve(project);
    if (project == null || project.getAiProvider() == null) {
      throw new IllegalStateException("Project provider is required for document generation");
    }
    return project.getAiProvider();
  }

  private static int safeInt(Integer i) {
    return i == null ? 0 : i;
  }
}
