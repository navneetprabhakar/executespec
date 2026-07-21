package com.navneet.executespec.standards.enforcement.lint;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link LintExecutionProperties} from the lint package itself, matching the pattern used by
 * {@code SandboxConfiguration}, so the RR-1051 controls travel with the code they govern.
 */
@Configuration
@EnableConfigurationProperties(LintExecutionProperties.class)
public class LintExecutionConfiguration {}
