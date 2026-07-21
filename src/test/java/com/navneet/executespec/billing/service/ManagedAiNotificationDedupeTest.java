package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.billing.entity.ManagedAiStateNotification;
import com.navneet.executespec.billing.repository.ManagedAiStateNotificationRepository;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ManagedAiNotificationDedupeTest {

  private final ManagedAiStateNotificationRepository repository =
      mock(ManagedAiStateNotificationRepository.class);
  private final ManagedAiNotificationDedupe dedupe = new ManagedAiNotificationDedupe(repository);

  @Test
  void claimWinsWhenInsertSucceeds() {
    when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    assertThat(dedupe.claim(record())).isTrue();
  }

  @Test
  void claimLosesWhenUniqueIndexRejectsTheInsert() {
    // A concurrent transition already committed this (period, to_state, channel) row.
    when(repository.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThat(dedupe.claim(record())).isFalse();
  }

  private static ManagedAiStateNotification record() {
    return ManagedAiStateNotification.builder()
        .id("n1")
        .periodId("period-1")
        .userId("u1")
        .toState(ManagedAiRoutingPolicy.CHEAP_ONLY)
        .channel(ManagedAiNotificationPolicy.CHANNEL_IN_APP)
        .sentAt(java.time.Instant.parse("2026-06-01T00:00:00Z"))
        .build();
  }
}
