package com.navneet.executespec.codeintel;

import org.springframework.ai.chat.model.ChatResponse;

/** Fail-closed provider termination policy for publishable generated project documents. */
final class ProjectDocResponsePolicy {

  private ProjectDocResponsePolicy() {}

  static boolean wasTruncated(ChatResponse response) {
    if (response == null || response.getResults() == null) return false;
    return response.getResults().stream()
        .filter(java.util.Objects::nonNull)
        .map(org.springframework.ai.chat.model.Generation::getMetadata)
        .filter(java.util.Objects::nonNull)
        .map(org.springframework.ai.chat.metadata.ChatGenerationMetadata::getFinishReason)
        .filter(java.util.Objects::nonNull)
        .map(Object::toString)
        .map(reason -> reason.trim().toLowerCase(java.util.Locale.ROOT))
        .anyMatch(reason -> reason.equals("length") || reason.equals("max_tokens"));
  }

  static void requireComplete(ChatResponse response) {
    if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
      throw new ProjectDocGeneratorService.ProviderNonCompleteGenerationException();
    }
    for (var generation : response.getResults()) {
      if (generation == null || generation.getMetadata() == null) continue;
      Object reason = generation.getMetadata().getFinishReason();
      if (reason == null) continue;
      String normalized = String.valueOf(reason).trim().toLowerCase(java.util.Locale.ROOT);
      if (normalized.isBlank()
          || normalized.equals("stop")
          || normalized.equals("end_turn")
          || normalized.equals("complete")
          || normalized.equals("completed")) {
        continue;
      }
      throw new ProjectDocGeneratorService.ProviderNonCompleteGenerationException();
    }
  }
}
