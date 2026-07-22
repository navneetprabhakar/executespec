package com.navneet.executespec.config.featureflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.service.ActivityAuditService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** RR-1032 — a global feature-flag mutation writes an immutable before/after audit row. */
@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceAuditTest {

  @Mock private FeatureFlagRepository repository;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private RedisMessageListenerContainer listenerContainer;
  @Mock private ActivityAuditService activityAuditService;

  @Test
  void update_recordsImmutableBeforeAfterAudit() {
    FeatureFlag existing =
        FeatureFlag.builder()
            .flagKey("ci.autofix.enabled")
            .flagValue("false")
            .valueType(FeatureFlag.ValueType.BOOLEAN)
            .scope(FeatureFlag.Scope.PLATFORM)
            .build();
    when(repository.findByFlagKey("ci.autofix.enabled")).thenReturn(Optional.of(existing));
    when(repository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));

    FeatureFlagService service =
        new FeatureFlagService(repository, redisTemplate, listenerContainer, activityAuditService);
    service.update("ci.autofix.enabled", "true", "admin-1");

    ArgumentCaptor<ActivityAudit> captor = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(activityAuditService).record(captor.capture());
    ActivityAudit audit = captor.getValue();
    assertThat(audit.getEventType()).isEqualTo("FEATURE_FLAG_UPDATED");
    assertThat(audit.getEntityId()).isEqualTo("ci.autofix.enabled");
    assertThat(audit.getActorId()).isEqualTo("admin-1");
    assertThat(audit.getOldValue()).isEqualTo("false");
    assertThat(audit.getNewValue()).isEqualTo("true");
  }
}
