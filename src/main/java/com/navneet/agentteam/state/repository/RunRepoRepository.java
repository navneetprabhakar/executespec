package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.RunRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RunRepoRepository extends JpaRepository<RunRepo, String> {
    Optional<RunRepo> findByRunId(String runId);
}
