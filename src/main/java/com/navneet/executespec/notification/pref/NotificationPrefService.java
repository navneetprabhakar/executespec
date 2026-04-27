package com.navneet.executespec.notification.pref;

import com.navneet.executespec.activity.ActivityEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * E4.5 — resolves whether a user should receive a notification for
 * a given event type on a given channel. Per-user override
 * ({@link UserNotificationPref}) wins; absence falls back to
 * {@link NotificationPrefDefaults} which encodes the "notify for things
 * that need attention; don't notify for routine" defaults from
 * plan §4.5.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPrefService {

    private final UserNotificationPrefRepository prefRepo;

    public boolean shouldNotify(String userId, ActivityEventType type, Channel channel) {
        if (userId == null || type == null || channel == null) return false;
        // Explicit override wins
        Optional<UserNotificationPref> existing = prefRepo
                .findByUserIdAndEventTypeAndChannel(userId, type.name(), channel.name());
        if (existing.isPresent()) return existing.get().isEnabled();
        // No row → default
        return NotificationPrefDefaults.defaultEnabled(type, channel);
    }

    /** All explicit override rows for a user — the matrix UI seeds itself
     *  from this so checkbox state reflects what the DB actually says
     *  vs. what the defaults would do. */
    public List<UserNotificationPref> listOverrides(String userId) {
        if (userId == null) return List.of();
        return prefRepo.findByUserId(userId);
    }

    /** Idempotent upsert — used by the Settings UI when a user toggles
     *  a checkbox. Writes a row regardless of whether the new value
     *  matches the default, so the UI always knows the user's intent
     *  (and so a future default change doesn't silently flip a user's
     *  expressed preference). */
    @Transactional
    public UserNotificationPref setPreference(String userId, ActivityEventType type, Channel channel, boolean enabled) {
        if (userId == null || type == null || channel == null) {
            throw new IllegalArgumentException("userId, type, and channel are required");
        }
        UserNotificationPref row = prefRepo
                .findByUserIdAndEventTypeAndChannel(userId, type.name(), channel.name())
                .orElseGet(() -> UserNotificationPref.builder()
                        .userId(userId)
                        .eventType(type.name())
                        .channel(channel.name())
                        .build());
        row.setEnabled(enabled);
        row.setUpdatedAt(Instant.now());
        return prefRepo.save(row);
    }

    public enum Channel { IN_APP, EMAIL }

    /**
     * Encodes the sensible-default per-event-type behaviour from plan
     * §4.5: events that demand attention default to ON; routine
     * background events default to OFF.
     */
    public static final class NotificationPrefDefaults {

        /** Events whose default is to notify regardless of channel. */
        private static final Set<ActivityEventType> NOTIFY_ON_BY_DEFAULT = EnumSet.of(
                ActivityEventType.RUN_FAILED,
                ActivityEventType.RUN_PLAN_READY,             // user must approve
                ActivityEventType.LAUNCH_PAUSED,              // needs intervention
                ActivityEventType.CONTRACT_BREAKING_CHANGE,   // needs explicit ack
                ActivityEventType.CONTRACT_DRIFT_DETECTED,
                ActivityEventType.STANDARDS_VIOLATION_DETECTED,
                ActivityEventType.QUOTA_APPROACHING_LIMIT,
                ActivityEventType.EXECUTESPEC_BOOTSTRAP_FAILED,
                ActivityEventType.MEMBER_ROLE_CHANGED);

        /** Background events that default to OFF (in-app stays as a passive
         *  feed entry; the user doesn't want a ping for routine activity). */
        private static final Set<ActivityEventType> EXPLICITLY_OFF = EnumSet.of(
                ActivityEventType.AUTO_DETECTION_RAN,
                ActivityEventType.CODEMAP_REGENERATED,
                ActivityEventType.MEMORY_LEDGER_COMPACTED);

        private NotificationPrefDefaults() {}

        public static boolean defaultEnabled(ActivityEventType type, Channel channel) {
            // EMAIL defaults to OFF for everything except the explicit
            // notify-by-default set — most users prefer in-app over email.
            if (channel == Channel.EMAIL) return NOTIFY_ON_BY_DEFAULT.contains(type);
            // IN_APP: notify-by-default OR (HIGH_SIGNAL minus explicit OFF)
            if (NOTIFY_ON_BY_DEFAULT.contains(type)) return true;
            if (EXPLICITLY_OFF.contains(type)) return false;
            return type.tier() == ActivityEventType.Tier.HIGH_SIGNAL;
        }
    }
}
