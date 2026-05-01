package com.navneet.executespec.config.datasource;

/**
 * Thread-local holder for the current tenant's org_id.
 *
 * <p>Set at HTTP request start by {@link TenantConnectionInterceptor}, cleared at request end.
 * Virtual threads inherit this via {@code ContextSnapshotFactory} propagation.
 */
public final class TenantContext {

  private static final ThreadLocal<String> CURRENT_ORG_ID = new ThreadLocal<>();

  private TenantContext() {}

  public static void setCurrentOrgId(String orgId) {
    CURRENT_ORG_ID.set(orgId);
  }

  public static String getCurrentOrgId() {
    return CURRENT_ORG_ID.get();
  }

  public static void clear() {
    CURRENT_ORG_ID.remove();
  }
}
