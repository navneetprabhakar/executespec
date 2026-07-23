package com.navneet.executespec.config.featureflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.service.ActivityAuditService;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class FeatureFlagServiceTest {

  private final FeatureFlagRepository repository = mock(FeatureFlagRepository.class);
  private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
  private final RedisMessageListenerContainer listenerContainer =
      mock(RedisMessageListenerContainer.class);
  private final ActivityAuditService activityAuditService = mock(ActivityAuditService.class);
  private final FeatureFlagService service =
      new FeatureFlagService(repository, redisTemplate, listenerContainer, activityAuditService);

  @Test
  void scheduledReloadSwapsToACompleteSnapshot() {
    when(repository.findAll()).thenReturn(List.of(flag("alpha", "true"), flag("beta", "7")));
    service.scheduledReload();
    assertThat(service.isEnabled("alpha")).isTrue();
    assertThat(service.getInt("beta", 0)).isEqualTo(7);

    when(repository.findAll()).thenReturn(List.of(flag("beta", "9")));
    service.scheduledReload();

    assertThat(service.isEnabled("alpha")).isFalse();
    assertThat(service.getInt("beta", 0)).isEqualTo(9);
  }

  @Test
  void redisSingleKeyUpdatePublishesIntoSnapshot() {
    when(repository.findAll()).thenReturn(List.of(flag("alpha", "false")));
    service.scheduledReload();
    when(repository.findByFlagKey("alpha")).thenReturn(Optional.of(flag("alpha", "true")));

    service.onMessage(
        new DefaultMessage(
            "executespec:feature-flag-update".getBytes(StandardCharsets.UTF_8),
            "alpha".getBytes(StandardCharsets.UTF_8)),
        null);

    assertThat(service.isEnabled("alpha")).isTrue();
  }

  @Test
  void updateWritesThroughLocalSnapshotAndPublishes() {
    FeatureFlag flag = flag("alpha", "false");
    when(repository.findByFlagKey("alpha")).thenReturn(Optional.of(flag));
    when(repository.save(flag)).thenReturn(flag);

    service.update("alpha", "true", "admin-1");

    assertThat(service.isEnabled("alpha")).isTrue();
    verify(redisTemplate).convertAndSend("executespec:feature-flag-update", "alpha");
  }

  // RR-063 (2026-07-22, P1) — flag values must be validated against their declared type.
  @Test
  void updateRejectsAValueThatDoesNotMatchTheDeclaredType() {
    FeatureFlag boolFlag =
        FeatureFlag.builder()
            .flagKey("gamma")
            .flagValue("false")
            .valueType(FeatureFlag.ValueType.BOOLEAN)
            .scope(FeatureFlag.Scope.PLATFORM)
            .description("gamma")
            .build();
    when(repository.findByFlagKey("gamma")).thenReturn(Optional.of(boolFlag));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.update("gamma", "maybe", "admin"))
        .isInstanceOf(IllegalArgumentException.class);
    // A rejected write never touches the repository or the cache.
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void validateValueForTypeEnforcesEachType() {
    // BOOLEAN
    FeatureFlagService.validateValueForType("k", FeatureFlag.ValueType.BOOLEAN, "true");
    FeatureFlagService.validateValueForType("k", FeatureFlag.ValueType.BOOLEAN, "FALSE");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> FeatureFlagService.validateValueForType("k", FeatureFlag.ValueType.BOOLEAN, "1"))
        .isInstanceOf(IllegalArgumentException.class);
    // INTEGER
    FeatureFlagService.validateValueForType("k", FeatureFlag.ValueType.INTEGER, "42");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> FeatureFlagService.validateValueForType("k", FeatureFlag.ValueType.INTEGER, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    // PERCENTAGE range
    FeatureFlagService.validateValueForType("k", FeatureFlag.ValueType.PERCENTAGE, "0");
    FeatureFlagService.validateValueForType("k", FeatureFlag.ValueType.PERCENTAGE, "100");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                FeatureFlagService.validateValueForType(
                    "k", FeatureFlag.ValueType.PERCENTAGE, "101"))
        .isInstanceOf(IllegalArgumentException.class);
    // STRING accepts anything non-null
    FeatureFlagService.validateValueForType("k", FeatureFlag.ValueType.STRING, "anything");
  }

  private static FeatureFlag flag(String key, String value) {
    return FeatureFlag.builder()
        .flagKey(key)
        .flagValue(value)
        .valueType(FeatureFlag.ValueType.STRING)
        .scope(FeatureFlag.Scope.PLATFORM)
        .description(key)
        .build();
  }
}
