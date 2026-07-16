package com.navneet.executespec.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables background jobs outside the local integration profile.
 *
 * <p>Several scheduled jobs poll provider status pages or process deployment/outbox state. A local
 * login or integration boot must not trigger those side effects, so scheduling is absent whenever
 * the {@code local} profile is active.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local")
@EnableScheduling
public class SchedulingProfileConfig {}
