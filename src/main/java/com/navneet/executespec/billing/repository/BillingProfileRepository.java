package com.navneet.executespec.billing.repository;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingProfile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BillingProfileRepository extends JpaRepository<BillingProfile, UUID> {

  Optional<BillingProfile> findByUserId(String userId);

  Optional<BillingProfile> findByOrgId(String orgId);
}
