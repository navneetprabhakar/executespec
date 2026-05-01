package com.navneet.executespec.config.featureflag;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

  Optional<FeatureFlag> findByFlagKey(String flagKey);
}
