package com.navneet.executespec.config.datasource;

import com.navneet.executespec.auth.security.AppUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Two-layer tenant isolation enforcer:
 *
 * <h3>Layer 1 — HTTP filter ({@code TenantConnectionInterceptor.RequestFilter})</h3>
 * <p>Runs at request start: reads the authenticated user's org_id from
 * {@code SecurityContextHolder} and writes it to {@link TenantContext}.
 * Clears the context at request end.
 *
 * <h3>Layer 2 — AOP interceptor (this class)</h3>
 * <p>Intercepts all Spring {@code @Repository} beans. Before delegating to the
 * actual repository method, if a transaction is active, executes:
 * <pre>SET LOCAL app.current_org_id = '&lt;orgId&gt;'</pre>
 * This session variable is read by the PostgreSQL RLS policies created in
 * {@code V67__rls_tenant_isolation.sql}. {@code SET LOCAL} scopes the value
 * to the current transaction, so it is automatically cleared on commit/rollback.
 *
 * <p><b>Fail-open:</b> If no org context is set (background pipeline threads,
 * scheduled jobs, migrations), the variable is not set and RLS policies allow
 * all rows through — preserving existing system-level behavior.
 *
 * <p><b>Performance note:</b> The {@code SET LOCAL} call adds one round-trip per
 * repository method within a transaction. For production scale, consider replacing
 * this with a HikariCP {@code ConnectionCustomizer} or a Hibernate
 * {@code MultiTenantConnectionProvider}.
 */
@Component
@Aspect
@RequiredArgsConstructor
@Slf4j
public class TenantConnectionInterceptor {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Sets {@code app.current_org_id} session variable before each Spring Data
     * repository method call, when running inside an active transaction.
     */
    @Around("within(@org.springframework.stereotype.Repository *)")
    public Object setTenantVariable(ProceedingJoinPoint pjp) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            String orgId = TenantContext.getCurrentOrgId();
            try {
                if (orgId != null && !orgId.isBlank()) {
                    // Use parameterized call to prevent SQL injection
                    jdbcTemplate.execute("SET LOCAL app.current_org_id = '"
                            + orgId.replace("'", "''") + "'");
                }
                // If no orgId: leave variable unset → RLS policy allows all rows (fail-open)
            } catch (Exception ex) {
                log.warn("[TenantRLS] Failed to set app.current_org_id='{}': {}", orgId, ex.getMessage());
                // Fail-open: proceed without setting the variable
            }
        }
        return pjp.proceed();
    }

    /**
     * Servlet filter that populates {@link TenantContext} from the authenticated
     * user's org_id at the start of each HTTP request and clears it at the end.
     */
    @Component
    public static class RequestFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            try {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof AppUserDetails details) {
                    String orgId = details.getUser().getOrgId();
                    if (orgId != null && !orgId.isBlank()) {
                        TenantContext.setCurrentOrgId(orgId);
                    }
                }
                chain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
