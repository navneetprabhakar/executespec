package com.navneet.executespec.codeintel;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Focused route and persistence-candidate assembly for generated project documents. */
final class ProjectGeneratedDocAssembly {

  private ProjectGeneratedDocAssembly() {}

  /**
   * Provider-dispatch lane for document generation: builds the bounded execution reference and the
   * trust-wrapped evidence messages, then runs the validated provider loop. Deterministic
   * zero-provider assembly (empty SETUP command projection) deliberately never enters this method,
   * so no execution context is created and no provider call can be admitted for that path.
   */
  static ProjectDocValidatedGeneration.Result dispatchProviderGeneration(
      LlmRouter llmRouter,
      Consumer<String> publicationSafetyCheck,
      String projectId,
      String projectName,
      DocType docType,
      String generatedByUser,
      ResolvedModel resolved,
      String exactModel,
      LlmExecutionReference confirmedActionReference,
      LlmExecutionPolicyFactory executionPolicyFactory,
      String systemPrompt,
      String userPayload,
      Set<String> sourceRepositories,
      String codemapEvidence,
      String durableJobId,
      Runnable providerDispatchFence) {
    List<Message> initialMessages =
        List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(PromptTrustBoundary.wrap("PROJECT_DOC_EVIDENCE", userPayload)));
    String runId = "project-doc-" + docType.name().toLowerCase() + "-" + projectId;
    if (executionPolicyFactory == null) {
      throw new IllegalStateException("Execution policy factory is required for project docs");
    }
    String contextId =
        durableJobId == null
            ? ProjectDocExecutionPolicy.contextId(java.util.UUID.randomUUID())
            : ProjectDocExecutionPolicy.durableContextId(durableJobId);
    LlmExecutionReference executionReference =
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
                generatedByUser,
                ProjectDocExecutionPolicy.ATTEMPT_BUDGET);
    return ProjectDocValidatedGeneration.generate(
        llmRouter,
        publicationSafetyCheck,
        projectId,
        projectName,
        docType,
        resolved,
        exactModel,
        runId,
        executionReference,
        initialMessages,
        sourceRepositories,
        codemapEvidence,
        providerDispatchFence);
  }

  static ResolvedModel route(
      ModelResolver resolver, LlmProvider provider, String exactModelOverride) {
    if (exactModelOverride == null) {
      return resolver.resolve("ProjectDocGeneratorService", provider);
    }
    return ResolvedModel.builder()
        .requestedProvider(provider)
        .actualProvider(provider)
        .model(exactModelOverride)
        .agentTier(AgentTier.WORKHORSE)
        .fallback(false)
        .build();
  }

  static ProjectGeneratedDoc candidate(
      String projectId,
      DocType docType,
      String content,
      String generatedBy,
      String model,
      int inputTokens,
      int outputTokens,
      double costUsd,
      Integer sourceArchitectureVersion,
      String sourceFingerprint) {
    return ProjectGeneratedDoc.builder()
        .projectId(projectId)
        .docType(docType)
        .content(content)
        .generatedBy(generatedBy)
        .modelUsed(model)
        .inputTokens(inputTokens)
        .outputTokens(outputTokens)
        .costUsd(costUsd)
        .sourceArchVersion(sourceArchitectureVersion)
        .sourceShasFingerprint(sourceFingerprint)
        .build();
  }
}
