package com.navneet.executespec.codeintel;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.navneet.executespec.agent.LlmRequest;
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
      String projectName,
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
      AtomicBoolean currentCallStarted = new AtomicBoolean();
      ChatResponse response;
      try {
        response =
            llmRouter.callTextStrict(
                resolved.providerName(),
                exactModel,
                messages,
                runId,
                "project-doc-" + docType.name().toLowerCase(),
                attempt == 1 ? "generate" : "generate-corrective-retry",
                null,
                executionReference,
                LlmRequest.RetryProfile.INTERACTIVE,
                () -> {
                  providerDispatchFence.run();
                  currentCallStarted.set(true);
                });
      } catch (RuntimeException failure) {
        if (!currentCallStarted.get()) {
          throw new ProjectDocGeneratorService.PreTransportGenerationException(failure);
        }
        throw failure;
      }
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
      Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
      totalInputTokens += usage == null ? 0 : safeInt(usage.getPromptTokens());
      totalOutputTokens += usage == null ? 0 : safeInt(usage.getCompletionTokens());

      String groundedContent = content;
      Optional<String> failure = ProjectGeneratedDocValidator.validateProviderShape(content);
      if (failure.isEmpty()) {
        groundedContent = GeneratedSignatureGrounding.sanitize(content, codemapEvidence);
        groundedContent =
            GeneratedSignatureGrounding.sanitizeUnsupportedBehavior(
                groundedContent, codemapEvidence);
        groundedContent = GeneratedProjectFactGrounding.sanitize(groundedContent, codemapEvidence);
        if (docType == DocType.EXPLANATION) {
          groundedContent =
              ProjectExplanationOperationalGrounding.append(
                  groundedContent, codemapEvidence, sourceRepositories);
        } else if (docType == DocType.SETUP) {
          groundedContent =
              ProjectSetupOperationalGrounding.assemble(
                  projectName, codemapEvidence, sourceRepositories);
        }
        // Deterministic grounding is still customer-visible publication content. Scan the final
        // assembled document as well as the provider response so an evidenced command cannot
        // reintroduce a credential or tool-protocol payload after the first safety boundary.
        publicationSafetyCheck.accept(groundedContent);
        failure =
            ProjectGeneratedDocValidator.validate(
                docType, groundedContent, sourceRepositories, codemapEvidence);
      }
      if (failure.isEmpty()) {
        return new Result(groundedContent, safeInt(totalInputTokens), safeInt(totalOutputTokens));
      }
      String reason = failure.orElseThrow();
      if (attempt == MAX_ATTEMPTS) {
        throw new ProjectDocGeneratorService.InvalidGeneratedDocumentException(
            ProjectGeneratedDocFailureCode.fromReason(reason), reason);
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
    if (response == null || response.getResults() == null) return "";
    return response.getResults().stream()
        .filter(java.util.Objects::nonNull)
        .map(org.springframework.ai.chat.model.Generation::getOutput)
        .filter(java.util.Objects::nonNull)
        .map(output -> output.getText() == null ? "" : output.getText().trim())
        .filter(text -> !text.isBlank())
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static String correctiveGroundingInstruction(String reason) {
    if (reason != null
        && reason.startsWith(
            "setup command is not present in encoded exact-revision setup evidence")) {
      return """


This SETUP correction must use any supplied `#### Safe deterministic command projection` as the
complete allow-list for non-clone commands. If that projection is absent or empty, no non-clone
command is authorized. Review the entire document and remove each command-like instruction from
every fenced block regardless of its language label, every console prompt, every inline-code span,
and every indented-code block unless that exact command is allow-listed. Keep each projected
command only under the unambiguous exact linked-repository name that supplied it; do not place a
repository command at project-wide scope or under another repository. Keep one literal fenced
`git clone` command per linked repository. Do not infer
`install`, `ci`, `build`, `test`, `start`, `dev`, migration, or health-check commands from a
manifest, lockfile, framework, or ecosystem convention. Where an expected workflow command is not
allow-listed, say that it is not established by the supplied deterministic evidence. Do not
replace a removed command with a placeholder command. Preserve every required heading and
meaningful section body.\
""";
    }
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
## Verified local workflow
## Troubleshooting
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
