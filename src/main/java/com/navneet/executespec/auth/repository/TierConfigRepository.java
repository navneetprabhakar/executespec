package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.TierConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TierConfigRepository extends JpaRepository<TierConfig, String> {
}
