package com.navneet.executespec.execution.repository;

import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionContextHandover;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionContextHandoverRepository
    extends JpaRepository<ExecutionContextHandover, String> {
  List<ExecutionContextHandover> findByToSolutionId(String toSolutionId);

  // RR-996 — deterministic order (oldest→newest, id as tiebreaker) so the assembled downstream
  // prompt is stable across retries/recovery instead of depending on unordered row iteration.
  List<ExecutionContextHandover> findByToSolutionIdOrderByCreatedAtAscIdAsc(String toSolutionId);

  // RR-996 — used to idempotently replace an existing (from,to,type) contract instead of blindly
  // inserting a duplicate row on every retry.
  List<ExecutionContextHandover> findByFromSolutionIdAndToSolutionIdAndContractType(
      String fromSolutionId, String toSolutionId, String contractType);
}
