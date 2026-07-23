package com.navneet.executespec.sharing.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.entity.ProjectShareLinkAudit;
import com.navneet.executespec.sharing.repository.ProjectShareLinkAuditRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Writes secret-free public-share lifecycle/access facts to the V287 append-only ledger. */
@Service
public class ProjectShareLinkAuditService {

  private final ProjectShareLinkAuditRepository repository;
  private final byte[] auditKey;

  public ProjectShareLinkAuditService(
      ProjectShareLinkAuditRepository repository,
      @Value("${app.security.audit-hash-secret:${app.jwt.secret}}") String auditSecret) {
    this.repository = repository;
    this.auditKey = auditSecret.getBytes(StandardCharsets.UTF_8);
  }

  public void record(
      ProjectShareLink link,
      String eventType,
      String outcome,
      String actorUserId,
      AccessContext accessContext) {
    if (link == null) throw new IllegalArgumentException("share link required");
    AccessContext access = accessContext == null ? AccessContext.none() : accessContext;
    repository.save(
        ProjectShareLinkAudit.builder()
            .id(UUID.randomUUID())
            .linkId(link.getId())
            .projectId(link.getProjectId())
            .eventType(eventType)
            .outcome(outcome)
            .surface(link.getSurface().name())
            .actorUserId(blankToNull(actorUserId))
            .clientIpHash(hmac(access.clientIp()))
            .userAgentHash(hmac(access.userAgent()))
            .occurredAt(Instant.now())
            .build());
  }

  public List<ProjectShareLinkAudit> latest(UUID linkId) {
    return repository.findTop100ByLinkIdOrderByOccurredAtDesc(linkId);
  }

  private String hmac(String value) {
    String normalized = blankToNull(value);
    if (normalized == null) return null;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(auditKey, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException impossible) {
      throw new IllegalStateException("HmacSHA256 unavailable", impossible);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Raw request attributes exist only in memory; the audit row stores keyed hashes. */
  public record AccessContext(String clientIp, String userAgent) {
    public static AccessContext none() {
      return new AccessContext(null, null);
    }
  }
}
