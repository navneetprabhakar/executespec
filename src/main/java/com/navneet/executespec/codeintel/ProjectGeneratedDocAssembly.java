package com.navneet.executespec.codeintel;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

/** Focused route and persistence-candidate assembly for generated project documents. */
final class ProjectGeneratedDocAssembly {

  private ProjectGeneratedDocAssembly() {}

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
