package com.navneet.executespec.codeintel;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Executes and validates the bounded same-route corrective loop for generated project documents.
 */
@Slf4j
final class ProjectDocValidatedGeneration {

  private static final int MAX_ATTEMPTS = 2;

  private ProjectDocValidatedGeneration() {}

  static Result generate(
      LlmRouter llmRouter,
      Consumer<String> publicationSafetyCheck,
      String projectId,
      DocType docType,
      ResolvedModel resolved,
      String exactModel,
      String runId,
      LlmExecutionReference executionReference,
      List<Message> initialMessages,
      Set<String> sourceRepositories,
      String codemapEvidence,
      Runnable providerDispatchFence) {
    List<Message> messages = initialMessages;
    long totalInputTokens = 0;
    long totalOutputTokens = 0;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      providerDispatchFence.run();
      ChatResponse response =
          llmRouter.callTextStrict(
              resolved.providerName(),
              exactModel,
              messages,
              runId,
              "project-doc-" + docType.name().toLowerCase(),
              attempt == 1 ? "generate" : "generate-corrective-retry",
              null,
              executionReference);
      if (ProjectDocGeneratorService.responseWasTruncated(response)) {
        log.warn(
            "[{}] Refusing truncated {} document response (reason={})",
            projectId,
            docType,
            ProjectDocGeneratorService.OUTPUT_LIMIT_REASON);
        throw new ProjectDocGeneratorService.IncompleteGenerationException(docType);
      }
      ProjectDocResponsePolicy.requireComplete(response);
      String content = responseText(response);
      publicationSafetyCheck.accept(content);
      String groundedContent = GeneratedSignatureGrounding.sanitize(content, codemapEvidence);
      groundedContent =
          GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(groundedContent, codemapEvidence);
      Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
      totalInputTokens += usage == null ? 0 : safeInt(usage.getPromptTokens());
      totalOutputTokens += usage == null ? 0 : safeInt(usage.getCompletionTokens());

      Optional<String> failure =
          ProjectGeneratedDocValidator.validate(
              docType, groundedContent, sourceRepositories, codemapEvidence);
      if (failure.isEmpty()) {
        return new Result(groundedContent, safeInt(totalInputTokens), safeInt(totalOutputTokens));
      }
      String reason = failure.orElseThrow();
      if (attempt == MAX_ATTEMPTS) {
        throw new ProjectDocGeneratorService.InvalidGeneratedDocumentException(reason);
      }
      log.warn(
          "[{}] {} document failed deterministic validation; retrying once on the same route: {}",
          projectId,
          docType,
          reason);
      messages =
          List.of(
              initialMessages.get(0),
              initialMessages.get(1),
              new UserMessage(
                  "Regenerate the entire document. The previous response failed deterministic"
                      + " publication validation. Fix the reported contract violation and return"
                      + " only the complete corrected Markdown document."
                      + mandatoryHeadingInstruction(docType)
                      + correctiveGroundingInstruction(reason)
                      + "\n\n"
                      + PromptTrustBoundary.wrap("PROJECT_DOC_VALIDATION_ERROR", reason)));
    }
    throw new IllegalStateException("unreachable project document validation loop");
  }

  private static String responseText(ChatResponse response) {
    return response.getResult() == null
            || response.getResult().getOutput() == null
            || response.getResult().getOutput().getText() == null
        ? ""
        : response.getResult().getOutput().getText().trim();
  }

  private static String correctiveGroundingInstruction(String reason) {
    if (reason == null || !reason.startsWith("unsupported ")) return "";
    return """


This is a whole-document grounding correction, not a request to patch only the named \
sentence. Review every section and remove ALL of the following:
- directional class/method claims using calls, invokes, delegates to, dispatches to, routes to, \
works with, interacts with, collaborates with/forms a collaboration, depends on, uses, or exposed by;
- Mermaid flowchart arrows and sequence-diagram messages that imply call or flow direction;
- inactive, unused, dead, stubbed, unwired, unreachable, not-called, not-used, hinted, placeholder, \
prototype, candidate, not-fully-integrated, relies-on, or hinted-at-by labels.
Replace them with non-directional declaration inventory such as "Relevant declared symbols \
include ..." and do not substitute a different unproved behavior. Preserve every required \
heading and meaningful section body.\
""";
  }

  private static String mandatoryHeadingInstruction(DocType docType) {
    return switch (docType) {
      case EXPLANATION ->
          """


The corrected EXPLANATION must retain each of these exact H2 heading lines exactly once:
## Repositories
## High-level architecture
## Key data flows
## Where to contribute
Do not rename, delete, merge, reorder, or demote any of these headings. Keep meaningful \
content beneath every one.
""";
      case SETUP ->
          """


The corrected SETUP document must retain each required heading exactly as supplied \
by the system instructions. Do not rename, delete, merge, or demote them.
""";
    };
  }

  private static int safeInt(Integer value) {
    return value == null ? 0 : value;
  }

  private static int safeInt(long value) {
    return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
  }

  record Result(String content, int inputTokens, int outputTokens) {}
}
