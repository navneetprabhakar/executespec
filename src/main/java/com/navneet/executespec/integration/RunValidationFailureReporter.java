package com.navneet.executespec.integration;

import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Persists and emits exact validation-failure evidence without expanding the validation runner. */
@Component
@RequiredArgsConstructor
@Slf4j
class RunValidationFailureReporter {

  private final PipelineRunRepository runRepo;
  private final SseBroadcaster broadcaster;
  private final ContextSanitizer contextSanitizer;

  void report(String runId, Throwable failure, String operationPhase) {
    ValidationFailureDiagnostic diagnostic = diagnostic(failure, contextSanitizer);
    persist(runId, diagnostic);
    try {
      broadcaster.emit(
          runId,
          RunEvent.validationFailed(
              runId,
              null,
              diagnostic.code(),
              diagnostic.reason(),
              operationPhase == null ? "validation" : operationPhase));
    } catch (RuntimeException eventFailure) {
      failure.addSuppressed(eventFailure);
      log.warn(
          "RunOrchestrator run={} validation failure event could not be emitted: {}",
          runId,
          eventFailure.getMessage());
    }
  }

  void clear(String runId) {
    try {
      runRepo
          .findById(runId)
          .filter(
              run ->
                  run.getFailureReason() != null
                      && run.getFailureReason().startsWith("VALIDATION_FAILED["))
          .ifPresent(
              run -> {
                run.setFailureReason(null);
                runRepo.save(run);
              });
    } catch (RuntimeException persistenceFailure) {
      log.warn(
          "RunOrchestrator run={} could not clear a stale validation failure diagnostic: {}",
          runId,
          persistenceFailure.getMessage());
    }
  }

  String persistedReasonWhenUnavailable(String runId, String statusReason) {
    if (!"VALIDATION_REPORT_UNAVAILABLE".equals(statusReason)) return null;
    return runRepo
        .findById(runId)
        .map(run -> run.getFailureReason())
        .filter(reason -> reason != null && reason.startsWith("VALIDATION_FAILED["))
        .orElse(null);
  }

  private void persist(String runId, ValidationFailureDiagnostic diagnostic) {
    try {
      runRepo
          .findById(runId)
          .ifPresent(
              run -> {
                run.setFailureReason(diagnostic.summary());
                runRepo.save(run);
              });
    } catch (RuntimeException persistenceFailure) {
      log.error(
          "RunOrchestrator run={} could not persist validation failure diagnostic code={}",
          runId,
          diagnostic.code(),
          persistenceFailure);
    }
  }

  static ValidationFailureDiagnostic diagnostic(Throwable failure, ContextSanitizer sanitizer) {
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
    String message = root.getMessage();
    if (message == null || message.isBlank()) message = root.getClass().getSimpleName();
    message = sanitizer.sanitizeOutput(message.replaceAll("[\\r\\n]+", " ").strip());
    if (message.length() > 1_800) message = message.substring(0, 1_800) + "...";

    String code = null;
    if (root
        instanceof BrownfieldHttpContractGuard.ContractCompatibilityException contractFailure) {
      code = contractFailure.code();
    }
    if (code == null || code.isBlank()) {
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("^([A-Z][A-Z0-9_]{2,79})(?::|$)").matcher(message);
      if (matcher.find()) code = matcher.group(1);
    }
    if (code == null || code.isBlank()) code = "VALIDATION_REPORT_GENERATION_FAILED";
    String reason = message;
    String prefix = code + ":";
    if (reason.startsWith(prefix)) reason = reason.substring(prefix.length()).strip();
    return new ValidationFailureDiagnostic(code, reason);
  }

  record ValidationFailureDiagnostic(String code, String reason) {
    ValidationFailureDiagnostic {
      code = code == null || code.isBlank() ? "VALIDATION_REPORT_GENERATION_FAILED" : code;
      reason = reason == null || reason.isBlank() ? "Validation report generation failed" : reason;
    }

    String summary() {
      return "VALIDATION_FAILED[" + code + "]: " + reason;
    }
  }
}
