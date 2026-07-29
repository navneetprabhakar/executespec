package com.navneet.executespec.repositoryintel;

import java.util.Objects;

/**
 * Server-derived actor and host-tenant identity for one repository read.
 *
 * <p>The constructor is deliberately package-private. Request DTO binders, controllers, provider
 * output, and callers outside the repository-intelligence authority package cannot mint this value.
 * A server-side authority adapter in this package must derive it from the authenticated principal
 * and resolved host tenant.
 */
public final class RepositoryAccessContext {

  private final String actorUserId;
  private final String tenantOrgId;

  RepositoryAccessContext(String actorUserId, String tenantOrgId) {
    this.actorUserId = required("actorUserId", actorUserId);
    this.tenantOrgId = required("tenantOrgId", tenantOrgId);
  }

  public String actorUserId() {
    return actorUserId;
  }

  public String tenantOrgId() {
    return tenantOrgId;
  }

  String stableKey() {
    return RepositoryIntelligenceIds.sha256(actorUserId, tenantOrgId);
  }

  private static String required(String name, String value) {
    String cleaned = RepositoryIntelligenceIds.clean(value);
    if (cleaned == null || cleaned.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return cleaned;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof RepositoryAccessContext that)) return false;
    return actorUserId.equals(that.actorUserId) && tenantOrgId.equals(that.tenantOrgId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(actorUserId, tenantOrgId);
  }

  @Override
  public String toString() {
    return "RepositoryAccessContext[actorUserId="
        + actorUserId
        + ", tenantOrgId="
        + tenantOrgId
        + "]";
  }
}
