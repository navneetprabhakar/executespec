package com.navneet.executespec.config;

import com.navneet.executespec.tenant.TenantContextThreadLocalAccessor;
import com.navneet.executespec.tool.WorkspaceContextThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures OpenTelemetry observability: tracing, metrics, and context propagation.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Activates thread-local accessors for virtual thread context propagation</li>
 *   <li>Registers {@link TenantContextThreadLocalAccessor} and
 *       {@link WorkspaceContextThreadLocalAccessor} so that tenant/workspace context
 *       auto-propagates across virtual threads spawned by
 *       {@code SequentialTaskExecutor} and {@code PipelineHarness}</li>
 *   <li>Enables {@code @Observed} annotation support via {@link ObservedAspect}</li>
 * </ul>
 */
@Configuration
@Slf4j
public class ObservabilityConfig {

    @PostConstruct
    void initContextPropagation() {
        // Register custom ThreadLocal accessors so context-propagation library
        // automatically captures/restores them across virtual thread boundaries.
        ContextRegistry registry = ContextRegistry.getInstance();
        registry.registerThreadLocalAccessor(new TenantContextThreadLocalAccessor());
        registry.registerThreadLocalAccessor(new WorkspaceContextThreadLocalAccessor());

        log.info("OTel context propagation initialized — TenantContext + WorkspaceContext registered");
    }

    /**
     * Enables the {@code @Observed} annotation on Spring beans for declarative
     * observation/span creation. Requires {@code management.observations.annotations.enabled=true}.
     */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}
