package com.navneet.executespec.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * Configures ShedLock for distributed scheduled-job locking.
 *
 * <p>ShedLock ensures that scheduled jobs (e.g. {@link
 * com.navneet.executespec.deployment.WorkspaceArchiveJob}) run on exactly one instance at a time
 * across the MIG, preventing duplicate execution when multiple GCE VMs are running.
 *
 * <p>Uses the Redis provider so no DB schema migration is needed — the lock is stored as a Redis
 * key and expires automatically after {@code lockAtMostFor}.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulingConfig {

  @Bean
  public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
    return new RedisLockProvider(connectionFactory);
  }
}
