package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.CompactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompactionEventRepository extends JpaRepository<CompactionEvent, String> {

    List<CompactionEvent> findByRunIdOrderByOccurredAtAsc(String runId);

    List<CompactionEvent> findByTaskIdOrderByOccurredAtAsc(String taskId);
}
