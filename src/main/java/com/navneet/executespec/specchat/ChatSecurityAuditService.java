package com.navneet.executespec.specchat;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Writes security-relevant chat decisions to the append-only, hash-chained activity audit. The
 * metadata deliberately excludes prompts, tool arguments, results, credentials, and exception text;
 * those belong in scoped turn evidence, not an enterprise-wide audit log.
 */
@Service
@RequiredArgsConstructor
public class ChatSecurityAuditService {

  private final ActivityAuditService activityAuditService;

  public void record(
      String eventType,
      String sessionOrProjectId,
      String projectId,
      AppUserDetails principal,
      String tool,
      ChatToolPolicy.Capability capability,
      String outcome) {
    String userId =
        principal == null || principal.getUser() == null
            ? null
            : String.valueOf(principal.getUser().getId());
    String orgId =
        principal == null || principal.getUser() == null ? null : principal.getUser().getOrgId();
    String metadata =
        "{\"tool\":\""
            + safe(tool)
            + "\",\"capability\":\""
            + (capability == null ? "UNKNOWN" : capability.name())
            + "\",\"outcome\":\""
            + safe(outcome)
            + "\"}";
    activityAuditService.record(
        ActivityAudit.builder()
            .eventType(eventType)
            .entityType(projectId == null ? "CHAT_SESSION" : "PROJECT_CHAT")
            .entityId(
                sessionOrProjectId == null || sessionOrProjectId.isBlank()
                    ? "unknown"
                    : sessionOrProjectId)
            .orgId(orgId)
            .actorId(userId)
            .metadata(metadata)
            .build());
  }

  /** Confirmation lifecycle audit; hashes/identifiers only, never bearer tokens or target JSON. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordConfirmation(
      String eventType,
      String projectId,
      String hostOrgId,
      AppUserDetails principal,
      String confirmationId,
      String action,
      String provider,
      String model,
      String targetHash,
      String outcome) {
    String userId =
        principal == null || principal.getUser() == null
            ? null
            : String.valueOf(principal.getUser().getId());
    String metadata =
        "{\"confirmationId\":\""
            + safe(confirmationId)
            + "\",\"action\":\""
            + safe(action)
            + "\",\"provider\":\""
            + safe(provider)
            + "\",\"model\":\""
            + safe(model)
            + "\",\"targetHash\":\""
            + safe(targetHash)
            + "\",\"outcome\":\""
            + safe(outcome)
            + "\"}";
    activityAuditService.record(
        ActivityAudit.builder()
            .eventType(eventType)
            .entityType("PROJECT_CHAT")
            .entityId(
                confirmationId == null || confirmationId.isBlank()
                    ? (projectId == null ? "unknown" : projectId)
                    : confirmationId)
            .orgId(hostOrgId)
            .actorId(userId)
            .metadata(metadata)
            .build());
  }

  private static String safe(String value) {
    if (value == null) return "";
    return value.replace("\\", "_").replace("\"", "_").replaceAll("[\\r\\n]", "_");
  }
}
