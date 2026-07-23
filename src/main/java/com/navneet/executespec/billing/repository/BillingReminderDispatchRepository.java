package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.BillingReminderDispatch;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingReminderDispatchRepository
    extends JpaRepository<BillingReminderDispatch, String> {}
