package com.navneet.executespec.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Manages in-app notifications — create, list, mark-read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InAppNotificationService {

    private final NotificationRepository notificationRepository;

    public Notification create(String userId, String type, String title, String body, String link) {
        Notification n = Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .body(body)
                .link(link)
                .build();
        return notificationRepository.save(n);
    }

    public Page<Notification> getNotifications(String userId, int page, int size) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    public long getUnreadCount(String userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    public void markRead(String notificationId, String userId) {
        notificationRepository.markRead(notificationId, userId);
    }

    public void markAllRead(String userId) {
        notificationRepository.markAllRead(userId);
    }
}
