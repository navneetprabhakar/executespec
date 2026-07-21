package com.navneet.executespec.sandbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link SandboxProperties} from the sandbox package itself rather than from the shared
 * application class, so the sandbox domain owns its own configuration surface.
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfiguration {}
