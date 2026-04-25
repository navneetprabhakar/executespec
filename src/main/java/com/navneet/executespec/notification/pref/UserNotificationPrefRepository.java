package com.navneet.executespec.notification.pref;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserNotificationPrefRepository extends JpaRepository<UserNotificationPref, UUID> {
    List<UserNotificationPref> findByUserId(String userId);
    Optional<UserNotificationPref> findByUserIdAndEventTypeAndChannel(
            String userId, String eventType, String channel);
}
