package com.navneet.executespec.tenant;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges {@link TenantContext} into Micrometer's context propagation system.
 *
 * <p>When virtual threads are spawned (e.g., in {@code SequentialTaskExecutor}),
 * the context-propagation library captures all registered ThreadLocal accessors
 * and restores them in the child thread. This ensures the tenant ID is available
 * in every span across the pipeline.
 */
public class TenantContextThreadLocalAccessor implements ThreadLocalAccessor<String> {

    public static final String KEY = "executespec.tenantId";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return TenantContext.getTenantId();
    }

    @Override
    public void setValue(String value) {
        TenantContext.setTenantId(value);
    }

    @Override
    public void setValue() {
        TenantContext.clear();
    }
}
