package com.navneet.executespec.config.featureflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class FeatureFlagServiceTest {

  private final FeatureFlagRepository repository = mock(FeatureFlagRepository.class);
  private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
  private final RedisMessageListenerContainer listenerContainer =
      mock(RedisMessageListenerContainer.class);
  private final FeatureFlagService service =
      new FeatureFlagService(repository, redisTemplate, listenerContainer);

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
