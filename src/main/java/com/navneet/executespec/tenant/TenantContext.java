package com.navneet.executespec.tenant;

/**
 * Thread-local tenant context. Set by {@link TenantFilter} at the start of every HTTP request and
 * cleared in the finally block to prevent context leakage across requests.
 */
public class TenantContext {

  private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

  public static void setTenantId(String tenantId) {
    CURRENT_TENANT.set(tenantId);
  }

  public static String getTenantId() {
    String id = CURRENT_TENANT.get();
    return id != null ? id : "default"; // safe fallback for async/internal paths
  }

  public static void clear() {
    CURRENT_TENANT.remove();
  }

  private TenantContext() {}
}
