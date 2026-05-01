package com.navneet.executespec.state.repository;

import java.util.Optional;

import com.navneet.executespec.state.entity.RunRepo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRepoRepository extends JpaRepository<RunRepo, String> {
  Optional<RunRepo> findByRunId(String runId);
}
