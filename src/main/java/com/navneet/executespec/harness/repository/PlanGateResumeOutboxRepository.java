package com.navneet.executespec.harness.repository;

import java.util.Optional;

import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for the V346 one-continuation-per-run outbox. */
public interface PlanGateResumeOutboxRepository
    extends JpaRepository<PlanGateResumeOutbox, String> {

  Optional<PlanGateResumeOutbox> findByGateKey(String gateKey);
}
