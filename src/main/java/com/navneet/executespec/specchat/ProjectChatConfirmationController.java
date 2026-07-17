package com.navneet.executespec.specchat;

import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Structured Project Chat confirmation contract shared by Web and CLI clients. */
@RestController
@RequestMapping("/api/projects/{projectId}/chat/confirmations")
@RequiredArgsConstructor
public class ProjectChatConfirmationController {

  private final ProjectChatConfirmationService confirmationService;
  private final ProjectAccessGuard projectAccessGuard;
  private final ChatSecurityAuditService auditService;

  public record ConfirmationRequest(
      String action, String provider, String model, Map<String, String> target) {
    ProjectChatConfirmationService.ActionScope toScope() {
      return new ProjectChatConfirmationService.ActionScope(action, provider, model, target);
    }
  }

  public record ConsumeRequest(
      String confirmationToken,
      String action,
      String provider,
      String model,
      Map<String, String> target) {
    ProjectChatConfirmationService.ActionScope toScope() {
      return new ProjectChatConfirmationService.ActionScope(action, provider, model, target);
    }
  }

  @PostMapping
  public ResponseEntity<ProjectChatConfirmationService.IssuedConfirmation> issue(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String projectId,
      @RequestBody ConfirmationRequest request) {
    // Controller + service both enforce this boundary. This keeps direct/internal callers and
    // future transport adapters equally safe and makes PROJECT_VIEWER denial explicit.
    try {
      projectAccessGuard.assertCanWrite(projectId, principal);
    } catch (RuntimeException denied) {
      auditDenied(projectId, principal, null, "CHAT_CONFIRMATION_ISSUE_DENIED");
      throw denied;
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            confirmationService.issue(
                projectId, principal, request == null ? null : request.toScope()));
  }

  @PostMapping("/{confirmationId}/consume")
  public ResponseEntity<ProjectChatConfirmationService.ExecutionResult> consume(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String projectId,
      @PathVariable UUID confirmationId,
      @RequestBody ConsumeRequest request) {
    try {
      projectAccessGuard.assertCanWrite(projectId, principal);
    } catch (RuntimeException denied) {
      auditDenied(projectId, principal, confirmationId, "CHAT_CONFIRMATION_CONSUME_DENIED");
      throw denied;
    }
    return ResponseEntity.ok(
        confirmationService.consumeAndExecute(
            projectId,
            confirmationId,
            principal,
            request == null ? null : request.confirmationToken(),
            request == null ? null : request.toScope()));
  }

  private void auditDenied(
      String projectId, AppUserDetails principal, UUID confirmationId, String deniedEvent) {
    auditService.recordConfirmation(
        deniedEvent,
        projectId,
        null,
        principal,
        confirmationId == null ? null : confirmationId.toString(),
        null,
        null,
        null,
        null,
        "DENIED");
  }
}
