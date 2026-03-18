package com.navneet.executespec.state.repository;

import com.navneet.executespec.state.entity.PlanVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, String> {
    List<PlanVersion> findByRunIdOrderByVersionDesc(String runId);
}
