package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.ManagedAiStateNotification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedAiStateNotificationRepository
    extends JpaRepository<ManagedAiStateNotification, String> {

  boolean existsByPeriodIdAndToStateAndChannel(String periodId, String toState, String channel);
}
