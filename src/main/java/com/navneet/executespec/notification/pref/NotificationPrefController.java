package com.navneet.executespec.notification.pref;

import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.notification.pref.NotificationPrefService.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * M-003 (Wave 2) — exposes user-scoped notification preferences over
 * REST so the Settings page can render the toggle matrix and persist
 * changes. The full preference matrix is (event-type × channel); rows
 * default to either ON or OFF per
 * {@link NotificationPrefService.NotificationPrefDefaults}, and the
 * user can override per cell.
 *
 * <p>Two endpoints:
 * <ul>
 *   <li>{@code GET /api/notifications/preferences} — returns one row per
 *       (event-type, channel) pair carrying the current effective
 *       state, the default state, whether the user has explicitly
 *       overridden it, and the event tier (so the UI can group by
 *       importance).</li>
 *   <li>{@code PUT /api/notifications/preferences} — upserts a single
 *       cell. Body: {@code {eventType, channel, enabled}}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications/preferences")
@RequiredArgsConstructor
public class NotificationPrefController {

    private final NotificationPrefService prefService;

    @GetMapping
    public ResponseEntity<List<PrefRow>> list(@AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String userId = principal.getUser().getId();
        List<UserNotificationPref> overrides = prefService.listOverrides(userId);

        List<PrefRow> rows = new ArrayList<>();
        for (ActivityEventType type : ActivityEventType.values()) {
            // AUDIT-only events aren't user-routable as notifications;
            // they live in the audit log, not the inbox/email path.
            if (type.tier() == ActivityEventType.Tier.AUDIT_ONLY) continue;
            for (Channel channel : Channel.values()) {
                Optional<UserNotificationPref> override = overrides.stream()
                        .filter(o -> o.getEventType().equals(type.name()) && o.getChannel().equals(channel.name()))
                        .findFirst();
                boolean defaultEnabled = NotificationPrefService.NotificationPrefDefaults
                        .defaultEnabled(type, channel);
                boolean effective = override.map(UserNotificationPref::isEnabled).orElse(defaultEnabled);
                rows.add(new PrefRow(
                        type.name(),
                        type.tier().name(),
                        channel.name(),
                        effective,
                        defaultEnabled,
                        override.isPresent()
                ));
            }
        }
        return ResponseEntity.ok(rows);
    }

    @PutMapping
    public ResponseEntity<PrefRow> update(@AuthenticationPrincipal AppUserDetails principal,
                                          @RequestBody PrefUpdate body) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (body == null || body.eventType() == null || body.channel() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventType, channel, and enabled are required");
        }

        ActivityEventType type;
        Channel channel;
        try {
            type = ActivityEventType.valueOf(body.eventType());
            channel = Channel.valueOf(body.channel());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown eventType or channel");
        }
        if (type.tier() == ActivityEventType.Tier.AUDIT_ONLY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "AUDIT_ONLY events are not user-routable");
        }

        String userId = principal.getUser().getId();
        UserNotificationPref saved = prefService.setPreference(userId, type, channel, body.enabled());
        boolean defaultEnabled = NotificationPrefService.NotificationPrefDefaults
                .defaultEnabled(type, channel);
        return ResponseEntity.ok(new PrefRow(
                type.name(),
                type.tier().name(),
                channel.name(),
                saved.isEnabled(),
                defaultEnabled,
                true));
    }

    /** One row in the matrix. */
    public record PrefRow(
            String eventType,
            String tier,          // HIGH_SIGNAL | ADMIN_TOGGLE
            String channel,       // IN_APP | EMAIL
            boolean enabled,      // effective (override → override; else default)
            boolean defaultEnabled,
            boolean override      // true if the user has an explicit row
    ) {}

    /** Inbound payload from PUT. */
    public record PrefUpdate(String eventType, String channel, boolean enabled) {}
}
