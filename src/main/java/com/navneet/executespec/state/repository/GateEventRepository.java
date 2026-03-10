package com.navneet.executespec.state.repository;

import com.navneet.executespec.state.entity.GateEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GateEventRepository extends JpaRepository<GateEvent, String> {

    List<GateEvent> findByRunIdOrderByOpenedAtAsc(String runId);
}
