package com.navneet.executespec.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Static accessor to the {@code currentProduct} request attribute set by
 * {@link ProductSurfaceFilter}. Use from anywhere in the request stack
 * (services, repositories) without plumbing the {@code HttpServletRequest}
 * through the call chain.
 *
 * <p>Example usage in a repository / service:
 *
 * <pre>{@code
 *   public List<PipelineRun> listForCurrentSurface(String userId) {
 *       String surface = ProductSurfaceContext.current();
 *       return pipelineRunRepository.findByUserIdAndProductId(userId, surface);
 *   }
 * }</pre>
 *
 * <p>Returns the {@code DEFAULT_SURFACE} ({@code executespec}) when called
 * outside an HTTP request context — safe for batch jobs, scheduled tasks,
 * test harness code, etc.
 *
 * <p>Phase 2 wiring approach: each repository that needs surface-scoped
 * data adds a {@code findByUserIdAndProductId(userId, surface)} variant
 * and the service layer routes to the new method via this accessor. We
 * deliberately don't auto-rewrite existing {@code findByUserId} calls —
 * that would silently break legacy reads (server-to-server jobs that
 * don't carry an Origin header). The {@code ProductSurfaceFilter} +
 * accessor sets the foundation; per-callsite migration is intentional.
 */
public final class ProductSurfaceContext {

    private ProductSurfaceContext() {
        throw new AssertionError("utility class");
    }

    /**
     * @return the current request's resolved surface, or
     *         {@link ProductSurfaceFilter#DEFAULT_SURFACE} when there is
     *         no active request.
     */
    public static String current() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return ProductSurfaceFilter.DEFAULT_SURFACE;
            HttpServletRequest req = attrs.getRequest();
            Object value = req.getAttribute(ProductSurfaceFilter.ATTR_CURRENT_PRODUCT);
            if (value == null) return ProductSurfaceFilter.DEFAULT_SURFACE;
            return value.toString();
        } catch (IllegalStateException stateEx) {
            // RequestContextHolder threw — no request bound to thread.
            return ProductSurfaceFilter.DEFAULT_SURFACE;
        }
    }

    /**
     * Convenience predicate for callsites that only care whether they're
     * on the consumer surface ({@code vibe}).
     */
    public static boolean isVibe() {
        return ProductSurfaceFilter.SURFACE_VIBE.equals(current());
    }

    /**
     * Convenience predicate for callsites that only care whether they're
     * on the B2B surface ({@code executespec}).
     */
    public static boolean isExecutespec() {
        return ProductSurfaceFilter.SURFACE_EXECUTESPEC.equals(current());
    }
}
