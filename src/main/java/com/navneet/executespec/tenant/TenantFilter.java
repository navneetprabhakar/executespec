package com.navneet.executespec.tenant;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.navneet.executespec.auth.security.AppUserDetails;

import lombok.extern.slf4j.Slf4j;

/**
 * Servlet filter that extracts the tenant ID from the incoming request and sets it in {@link
 * TenantContext} for the duration of the request.
 *
 * <p><strong>Audit 2026-05-12 review items B #19 + #20:</strong> the {@code X-Tenant-Id} header is
 * now (a) validated against an alphanumeric/hyphen regex (max 64 chars) to refuse path-traversal
 * or shell-metachar payloads, AND (b) cross-checked against the authenticated user's {@code
 * orgId} so an attacker can't spoof a different tenant via a client-controlled header. When the
 * user is not authenticated yet (login / register endpoints), the header is accepted but
 * still regex-validated.
 */
@Component
@Order(1)
@Slf4j
public class TenantFilter implements Filter {

  // 64-char cap is generous — real tenant IDs are UUIDs (36) or shortcodes (≤16).
  private static final Pattern VALID_TENANT_ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");
  private static final String DEFAULT_TENANT = "default";

  @Override
  public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest) req;
    HttpServletResponse httpResp = (HttpServletResponse) resp;

    String tenantId = httpReq.getHeader("X-Tenant-Id");
    if (tenantId == null || tenantId.isBlank()) {
      tenantId = DEFAULT_TENANT;
    } else if (!VALID_TENANT_ID.matcher(tenantId).matches()) {
      log.warn(
          "[TenantFilter] Rejected invalid X-Tenant-Id header on {} {}: '{}'",
          httpReq.getMethod(),
          httpReq.getRequestURI(),
          tenantId);
      httpResp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid X-Tenant-Id header");
      return;
    }

    // Cross-check against authenticated principal: an attacker MUST NOT claim a tenant
    // different from the one their JWT was issued for. SUPER_ADMIN bypass kept so the
    // operator dashboard can scope queries.
    if (!DEFAULT_TENANT.equals(tenantId)) {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null
          && auth.isAuthenticated()
          && auth.getPrincipal() instanceof AppUserDetails ud) {
        boolean isSuperAdmin = "SUPER_ADMIN".equals(ud.getUser().getGlobalRole());
        String callerOrgId = ud.getUser().getOrgId();
        if (!isSuperAdmin && callerOrgId != null && !callerOrgId.equals(tenantId)) {
          log.warn(
              "[TenantFilter] Caller orgId='{}' tried to claim X-Tenant-Id='{}' on {} {}",
              callerOrgId,
              tenantId,
              httpReq.getMethod(),
              httpReq.getRequestURI());
          httpResp.sendError(
              HttpServletResponse.SC_FORBIDDEN,
              "X-Tenant-Id does not match authenticated principal");
          return;
        }
      }
    }

    try {
      TenantContext.setTenantId(tenantId);
      chain.doFilter(req, resp);
    } finally {
      TenantContext.clear();
    }
  }
}
