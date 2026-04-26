package com.navneet.executespec.notification.pref;

import com.navneet.executespec.activity.ActivityEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPrefServiceTest {

    @Mock UserNotificationPrefRepository prefRepo;

    @Test
    void explicitPref_winsOverDefault() {
        NotificationPrefService svc = new NotificationPrefService(prefRepo);
        String userId = UUID.randomUUID().toString();
        // Default for RUN_FAILED is true; user explicitly disables → false
        when(prefRepo.findByUserIdAndEventTypeAndChannel(userId, "RUN_FAILED", "IN_APP"))
                .thenReturn(Optional.of(UserNotificationPref.builder()
                        .userId(userId).eventType("RUN_FAILED")
                        .channel("IN_APP").enabled(false).build()));
        assertThat(svc.shouldNotify(userId, ActivityEventType.RUN_FAILED,
                NotificationPrefService.Channel.IN_APP)).isFalse();
    }

    @Test
    void absenceFallsBackToDefault() {
        NotificationPrefService svc = new NotificationPrefService(prefRepo);
        String userId = UUID.randomUUID().toString();
        when(prefRepo.findByUserIdAndEventTypeAndChannel(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        // RUN_FAILED defaults to enabled
        assertThat(svc.shouldNotify(userId, ActivityEventType.RUN_FAILED,
                NotificationPrefService.Channel.IN_APP)).isTrue();
        // CODEMAP_REGENERATED defaults to off (routine background)
        assertThat(svc.shouldNotify(userId, ActivityEventType.CODEMAP_REGENERATED,
                NotificationPrefService.Channel.IN_APP)).isFalse();
    }

    @Test
    void emailDefaultsOff_exceptForExplicitNotifyByDefaultSet() {
        NotificationPrefService svc = new NotificationPrefService(prefRepo);
        when(prefRepo.findByUserIdAndEventTypeAndChannel(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        String userId = UUID.randomUUID().toString();
        // Routine HIGH_SIGNAL event → email off by default
        assertThat(svc.shouldNotify(userId, ActivityEventType.PR_OPENED,
                NotificationPrefService.Channel.EMAIL)).isFalse();
        // Explicit notify-by-default event → email on by default
        assertThat(svc.shouldNotify(userId, ActivityEventType.RUN_FAILED,
                NotificationPrefService.Channel.EMAIL)).isTrue();
    }

    @Test
    void inAppHighSignal_defaultsOn() {
        NotificationPrefService svc = new NotificationPrefService(prefRepo);
        when(prefRepo.findByUserIdAndEventTypeAndChannel(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        String userId = UUID.randomUUID().toString();
        // PR_MERGED is HIGH_SIGNAL + not in explicit-off set → in-app on
        assertThat(svc.shouldNotify(userId, ActivityEventType.PR_MERGED,
                NotificationPrefService.Channel.IN_APP)).isTrue();
    }

    @Test
    void adminToggleEvents_default_inApp_off() {
        NotificationPrefService svc = new NotificationPrefService(prefRepo);
        lenient().when(prefRepo.findByUserIdAndEventTypeAndChannel(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        String userId = UUID.randomUUID().toString();
        // ADMIN_TOGGLE-tier events default to off (not HIGH_SIGNAL)
        assertThat(svc.shouldNotify(userId, ActivityEventType.AUTO_DETECTION_RAN,
                NotificationPrefService.Channel.IN_APP)).isFalse();
    }

    @Test
    void nullArgs_returnFalse() {
        NotificationPrefService svc = new NotificationPrefService(prefRepo);
        assertThat(svc.shouldNotify(null, ActivityEventType.RUN_FAILED,
                NotificationPrefService.Channel.IN_APP)).isFalse();
        assertThat(svc.shouldNotify("u1", null,
                NotificationPrefService.Channel.IN_APP)).isFalse();
        assertThat(svc.shouldNotify("u1", ActivityEventType.RUN_FAILED, null)).isFalse();
    }
}
