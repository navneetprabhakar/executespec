package com.navneet.agentteam.auth.repository;

import com.navneet.agentteam.auth.entity.TierConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TierConfigRepository extends JpaRepository<TierConfig, String> {
}
