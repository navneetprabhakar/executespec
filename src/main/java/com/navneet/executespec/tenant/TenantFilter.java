package com.navneet.executespec.tenant;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that extracts the tenant ID from the incoming request and
 * sets it in {@link TenantContext} for the duration of the request.
 *
 * <p>The tenant is read from the {@code X-Tenant-Id} HTTP header.
 * Falls back to {@code "default"} for backward compatibility with clients
 * that do not send the header (e.g., local development).
 */
@Component
@Order(1)
public class TenantFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;

        String tenantId = httpReq.getHeader("X-Tenant-Id");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default"; // Backward compatible for development
        }

        try {
            TenantContext.setTenantId(tenantId);
            chain.doFilter(req, resp);
        } finally {
            TenantContext.clear();
        }
    }
}
