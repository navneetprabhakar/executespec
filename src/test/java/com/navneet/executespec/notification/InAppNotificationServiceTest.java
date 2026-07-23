package com.navneet.executespec.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class InAppNotificationServiceTest {

  private final NotificationRepository repository = mock(NotificationRepository.class);
  private final InAppNotificationService service = new InAppNotificationService(repository);

  @Test
  void rejectsInvalidPageBeforeRepositoryQuery() {
    assertThatThrownBy(() -> service.getNotifications("user-1", -1, 20))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
    assertThatThrownBy(() -> service.getNotifications("user-1", 0, 101))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");

    verify(repository, never())
        .findByUserIdOrderByCreatedAtDesc(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }
}
