package com.navneet.executespec.state.repository;

import java.util.List;

import com.navneet.executespec.state.entity.CompactionEvent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompactionEventRepository extends JpaRepository<CompactionEvent, String> {

  List<CompactionEvent> findByRunIdOrderByOccurredAtAsc(String runId);

  List<CompactionEvent> findByTaskIdOrderByOccurredAtAsc(String taskId);
}
