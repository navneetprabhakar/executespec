package com.navneet.executespec.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link EventTrackingService} — focused on idempotency and the "fire once per user"
 * contract. Integration tests (real Postgres + real PostHog) live in a separate @SpringBootTest
 * class to be added when we have an analytics test profile.
 */
@ExtendWith(MockitoExtension.class)
class EventTrackingServiceTest {

  @Mock UserActivationEventRepository repo;
  @Mock PostHogClient posthog;
  @Mock ObjectProvider<PostHogClient> posthogProvider;

  EventTrackingService service;

  @BeforeEach
  void setUp() {
    service = new EventTrackingService(repo, posthogProvider, new ObjectMapper());
  }

  @Test
  void isFirstTime_returnsTrueWhenNoRowExists() {
    when(repo.existsByUserIdAndEventName("u1", "SIGNUP")).thenReturn(false);
    assertThat(service.isFirstTime("u1", EventName.SIGNUP)).isTrue();
  }

  @Test
  void isFirstTime_returnsFalseWhenRowExists() {
    when(repo.existsByUserIdAndEventName("u1", "SIGNUP")).thenReturn(true);
    assertThat(service.isFirstTime("u1", EventName.SIGNUP)).isFalse();
  }

  @Test
  void isFirstTime_returnsFalseForBlankInput() {
    assertThat(service.isFirstTime(null, EventName.SIGNUP)).isFalse();
    assertThat(service.isFirstTime("", EventName.SIGNUP)).isFalse();
    assertThat(service.isFirstTime("u1", null)).isFalse();
  }

  @Test
  void insertFirstTime_skipsExistingRow_returnsFalse() {
    when(repo.existsByUserIdAndEventName("u1", "SIGNUP")).thenReturn(true);
    boolean result = service.insertFirstTime("u1", EventName.SIGNUP, Map.of());
    assertThat(result).isFalse();
    verify(repo, never()).save(any());
  }

  @Test
  void insertFirstTime_savesNewRow_returnsTrue() {
    when(repo.existsByUserIdAndEventName("u1", "SIGNUP")).thenReturn(false);
    boolean result = service.insertFirstTime("u1", EventName.SIGNUP, Map.of("tier", "FREE"));
    assertThat(result).isTrue();

    ArgumentCaptor<UserActivationEvent> captor = ArgumentCaptor.forClass(UserActivationEvent.class);
    verify(repo).save(captor.capture());
    UserActivationEvent saved = captor.getValue();
    assertThat(saved.getUserId()).isEqualTo("u1");
    assertThat(saved.getEventName()).isEqualTo("SIGNUP");
    assertThat(saved.getPropertiesJson()).contains("FREE");
    assertThat(saved.getOccurredAt()).isNotNull();
  }

  @Test
  void insertFirstTime_handlesPKRaceGracefully_returnsFalse() {
    when(repo.existsByUserIdAndEventName("u1", "SIGNUP")).thenReturn(false);
    when(repo.save(any()))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

    boolean result = service.insertFirstTime("u1", EventName.SIGNUP, Map.of());
    assertThat(result).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void track_forwardsToPostHog_onFirstTime() {
    when(repo.existsByUserIdAndEventName("u1", "PROJECT_CREATED")).thenReturn(false);
    when(posthogProvider.getIfAvailable()).thenReturn(posthog);

    Map<String, Object> props = Map.of("tier", "PRO");
    service.track("u1", EventName.PROJECT_CREATED, props);

    verify(posthog).capture(eq("u1"), eq("PROJECT_CREATED"), eq(props));
  }

  @Test
  void track_skipsPostHog_onRepeatFiring() {
    when(repo.existsByUserIdAndEventName("u1", "SIGNUP")).thenReturn(true);

    service.track("u1", EventName.SIGNUP, Map.of());

    verify(posthogProvider, never()).getIfAvailable();
    verify(repo, never()).save(any());
  }

  @Test
  void track_isNoOpForBlankUserId() {
    service.track("", EventName.SIGNUP, Map.of());
    verifyNoInteractions(repo, posthogProvider);
  }

  @Test
  void track_survivesRepoException() {
    when(repo.existsByUserIdAndEventName(anyString(), anyString()))
        .thenThrow(new RuntimeException("db down"));

    // Should not throw
    service.track("u1", EventName.SIGNUP, Map.of());
  }
}
